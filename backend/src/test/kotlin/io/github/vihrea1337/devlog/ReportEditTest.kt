package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Работа с готовым отчётом: правка текста перед отправкой, удаление, отзыв публичной ссылки.
 * Отчёт о своей работе не отдают работодателю не глядя — значит, его надо уметь править,
 * а выданную ссылку — закрывать.
 */
class ReportEditTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_report_edit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, Projects, Entries, EntryStructured, Reports) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(Reports, EntryStructured, Entries, Projects, Users) }
    }

    private suspend fun token(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun makeReport(client: HttpClient, token: String): ReportDto {
        client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-08-05","rawText":"чинил сборку"}""")
        }
        val r = client.post("/api/reports") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"2026-08-01","periodEnd":"2026-08-31"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        return json.decodeFromString(r.bodyAsText())
    }

    @Test
    fun `отчёт приходит и в Markdown, и в готовом HTML`() = testApplication {
        application { module() }
        val t = token(client, "rh@b.co")

        val report = makeReport(client, t)

        assertTrue(report.contentMd.contains("#"), "Markdown с заголовками")
        assertTrue(report.contentHtml.contains("<h1>"), "HTML для предпросмотра: ${report.contentHtml.take(120)}")
    }

    @Test
    fun `текст отчёта можно поправить перед отправкой`() = testApplication {
        application { module() }
        val t = token(client, "re@b.co")
        val report = makeReport(client, t)

        val res = client.put("/api/reports/${report.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"contentMd":"# Мой отчёт\n\n- сделал важное"}""")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val updated = json.decodeFromString<ReportDto>(res.bodyAsText())
        assertTrue(updated.contentMd.contains("сделал важное"))
        assertTrue(updated.contentHtml.contains("<li>сделал важное</li>"), "HTML пересобрался: ${updated.contentHtml}")

        // Правка сохранилась, а не осталась только в ответе.
        val again = client.get("/api/reports/${report.id}") { header(HttpHeaders.Authorization, "Bearer $t") }
        assertTrue(json.decodeFromString<ReportDto>(again.bodyAsText()).contentMd.contains("сделал важное"))
    }

    @Test
    fun `пустой текст отчёта не принимаем`() = testApplication {
        application { module() }
        val t = token(client, "re2@b.co")
        val report = makeReport(client, t)

        val res = client.put("/api/reports/${report.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"contentMd":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `чужой отчёт не поправить и не удалить`() = testApplication {
        application { module() }
        val owner = token(client, "ro2@b.co")
        val stranger = token(client, "rs2@b.co")
        val report = makeReport(client, owner)

        val edit = client.put("/api/reports/${report.id}") {
            header(HttpHeaders.Authorization, "Bearer $stranger")
            contentType(ContentType.Application.Json)
            setBody("""{"contentMd":"подменил отчёт"}""")
        }
        assertEquals(HttpStatusCode.NotFound, edit.status)

        val del = client.delete("/api/reports/${report.id}") {
            header(HttpHeaders.Authorization, "Bearer $stranger")
        }
        assertEquals(HttpStatusCode.NotFound, del.status)
    }

    @Test
    fun `ссылку можно выдать и отозвать`() = testApplication {
        application { module() }
        val t = token(client, "rsh@b.co")
        val report = makeReport(client, t)

        val share = client.post("/api/reports/${report.id}/share") { header(HttpHeaders.Authorization, "Bearer $t") }
        val shareToken = json.decodeFromString<ShareResponse>(share.bodyAsText()).shareToken
        assertEquals(HttpStatusCode.OK, client.get("/r/$shareToken").status)

        val revoke = client.delete("/api/reports/${report.id}/share") {
            header(HttpHeaders.Authorization, "Bearer $t")
        }
        assertEquals(HttpStatusCode.NoContent, revoke.status)

        // Ссылка перестала работать, а токен снят с отчёта.
        assertEquals(HttpStatusCode.NotFound, client.get("/r/$shareToken").status)
        val after = client.get("/api/reports/${report.id}") { header(HttpHeaders.Authorization, "Bearer $t") }
        assertNull(json.decodeFromString<ReportDto>(after.bodyAsText()).shareToken)
    }

    @Test
    fun `отчёт удаляется, записи остаются`() = testApplication {
        application { module() }
        val t = token(client, "rd@b.co")
        val report = makeReport(client, t)

        val del = client.delete("/api/reports/${report.id}") { header(HttpHeaders.Authorization, "Bearer $t") }
        assertEquals(HttpStatusCode.NoContent, del.status)

        assertEquals("[]", client.get("/api/reports") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText())
        val entries = client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $t") }
        assertTrue(entries.bodyAsText().contains("чинил сборку"), "записи не должны пострадать")
    }
}
