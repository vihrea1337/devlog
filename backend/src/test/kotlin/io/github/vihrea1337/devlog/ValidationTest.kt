package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Проверки того, что сервер отвечает на кривой запрос понятным 400, а не 500,
 * и что нельзя дотянуться до чужого проекта.
 *
 * Почему это важно: раньше `LocalDate.parse("вчера")` кидал исключение, StatusPages ловил его
 * и отвечал «внутренняя ошибка сервера» — клиент не понимал, что виноват он сам.
 * А `projectId` вообще не проверялся на владельца: своей записью можно было указать ЧУЖОЙ
 * проект и обойти его выключатель ИИ (конфиденциальный текст ушёл бы в Groq).
 */
class ValidationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_validation;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
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

    private suspend fun postEntry(client: HttpClient, token: String, body: String) =
        client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `кривая дата — 400 с объяснением, а не 500`() = testApplication {
        application { module() }
        val t = token(client, "date@b.co")

        val res = postEntry(client, t, """{"occurredOn":"вчера","rawText":"что-то делал"}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("дата"), "в ответе должно быть сказано, что не так: ${res.bodyAsText()}")
    }

    @Test
    fun `кривой id проекта — 400`() = testApplication {
        application { module() }
        val t = token(client, "uuid@b.co")

        val res = postEntry(client, t, """{"occurredOn":"2026-08-12","rawText":"работа","projectId":"не-uuid"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `нельзя привязать запись к чужому проекту (обход выключателя ИИ)`() = testApplication {
        application { module() }
        val owner = token(client, "owner@b.co")
        val stranger = token(client, "stranger@b.co")

        // Владелец создаёт конфиденциальный проект с выключенным ИИ.
        val projRes = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Секретный клиент","aiEnabled":false}""")
        }
        val projectId = json.decodeFromString<ProjectDto>(projRes.bodyAsText()).id

        // Чужой пользователь пытается положить запись в этот проект.
        val res = postEntry(
            client,
            stranger,
            """{"occurredOn":"2026-08-12","rawText":"чужая запись","projectId":"$projectId"}""",
        )

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("Проект не найден"))

        // И запись не создалась вовсе.
        val list = client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $stranger") }
        assertEquals("[]", list.bodyAsText())
    }

    @Test
    fun `свой проект привязать можно`() = testApplication {
        application { module() }
        val t = token(client, "mine@b.co")
        val projRes = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Мой проект"}""")
        }
        val projectId = json.decodeFromString<ProjectDto>(projRes.bodyAsText()).id

        val res = postEntry(client, t, """{"occurredOn":"2026-08-12","rawText":"работа","projectId":"$projectId"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(projectId, json.decodeFromString<EntryDto>(res.bodyAsText()).projectId)
    }

    @Test
    fun `слишком длинная заметка — 400`() = testApplication {
        application { module() }
        val t = token(client, "long@b.co")
        val huge = "а".repeat(MAX_RAW_TEXT_LENGTH + 1)

        val res = postEntry(client, t, """{"occurredOn":"2026-08-12","rawText":"$huge"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `пустая заметка — 400`() = testApplication {
        application { module() }
        val t = token(client, "empty@b.co")

        val res = postEntry(client, t, """{"occurredOn":"2026-08-12","rawText":"   "}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `битый JSON — 400, а не падение сервера`() = testApplication {
        application { module() }
        val t = token(client, "json@b.co")

        val res = postEntry(client, t, """{"rawText":}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `правка текста возвращает запись в очередь на обработку`() = testApplication {
        application { module() }
        val t = token(client, "edit@b.co")
        val created = json.decodeFromString<EntryDto>(
            postEntry(client, t, """{"occurredOn":"2026-08-12","rawText":"первый вариант"}""").bodyAsText(),
        )
        // Имитируем, что ИИ уже обработал запись.
        EntryRepository.markAiDone(java.util.UUID.fromString(created.id))

        val res = client.put("/api/entries/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"rawText":"переписал заметку"}""")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val updated = json.decodeFromString<EntryDto>(res.bodyAsText())
        assertEquals("переписал заметку", updated.rawText)
        assertEquals("queued", updated.status, "текст изменился — старая структура неверна, нужна переобработка")
    }

    @Test
    fun `правка только даты не трогает статус обработки`() = testApplication {
        application { module() }
        val t = token(client, "date2@b.co")
        val created = json.decodeFromString<EntryDto>(
            postEntry(client, t, """{"occurredOn":"2026-08-12","rawText":"текст"}""").bodyAsText(),
        )
        EntryRepository.markAiDone(java.util.UUID.fromString(created.id))

        val res = client.put("/api/entries/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-08-01"}""")
        }

        val updated = json.decodeFromString<EntryDto>(res.bodyAsText())
        assertEquals("2026-08-01", updated.occurredOn)
        assertEquals("structured", updated.status)
    }

    @Test
    fun `отчёт с периодом наоборот — 400`() = testApplication {
        application { module() }
        val t = token(client, "report@b.co")

        val res = client.post("/api/reports") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"2026-08-31","periodEnd":"2026-08-01"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `отчёт по чужому проекту не собрать`() = testApplication {
        application { module() }
        val owner = token(client, "ro@b.co")
        val stranger = token(client, "rs@b.co")
        val projRes = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Чужой"}""")
        }
        val projectId = json.decodeFromString<ProjectDto>(projRes.bodyAsText()).id

        val res = client.post("/api/reports") {
            header(HttpHeaders.Authorization, "Bearer $stranger")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"2026-08-01","periodEnd":"2026-08-31","projectId":"$projectId"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `кривой параметр в адресе ленты — 400`() = testApplication {
        application { module() }
        val t = token(client, "query@b.co")

        val res = client.get("/api/entries?from=позавчера") { header(HttpHeaders.Authorization, "Bearer $t") }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertNotEquals(HttpStatusCode.InternalServerError, res.status)
    }
}
