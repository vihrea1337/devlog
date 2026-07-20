package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlin.test.assertTrue

/**
 * Интеграционные тесты отчётов на H2. ИИ выключен (нет ключа) → отчёт собирается
 * детерминированным черновиком, поэтому тесты стабильны и не ходят в сеть.
 */
class ReportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_reports;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, Projects, Entries, EntryStructured, Reports) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(Reports, EntryStructured, Entries, Projects, Users) }
    }

    private suspend fun registerToken(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun addEntry(client: HttpClient, token: String, text: String) {
        client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-07-20","rawText":"$text"}""")
        }
    }

    private suspend fun makeReport(client: HttpClient, token: String): ReportDto {
        val r = client.post("/api/reports") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"2026-07-01","periodEnd":"2026-07-31"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        return json.decodeFromString<ReportDto>(r.bodyAsText())
    }

    @Test
    fun `отчёт собирается и содержит текст записи`() = testApplication {
        application { module() }
        val token = registerToken(client, "r1@b.co")
        addEntry(client, token, "починил сборку CI")

        val report = makeReport(client, token)
        assertTrue(report.contentMd.contains("починил сборку CI"))
        assertTrue(report.contentMd.contains("Отчёт за"))
    }

    @Test
    fun `отчёт есть в списке и доступен по id`() = testApplication {
        application { module() }
        val token = registerToken(client, "r2@b.co")
        addEntry(client, token, "работа")
        val report = makeReport(client, token)

        val list = client.get("/api/reports") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertTrue(list.bodyAsText().contains(report.id))

        val one = client.get("/api/reports/${report.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, one.status)
    }

    @Test
    fun `шаринг даёт публичную ссылку, доступную без токена`() = testApplication {
        application { module() }
        val token = registerToken(client, "r3@b.co")
        addEntry(client, token, "секретная работа для отчёта")
        val report = makeReport(client, token)

        val share = client.post("/api/reports/${report.id}/share") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, share.status)
        val shareToken = json.decodeFromString<ShareResponse>(share.bodyAsText()).shareToken

        // Публичная страница — без заголовка Authorization.
        val pub = client.get("/r/$shareToken")
        assertEquals(HttpStatusCode.OK, pub.status)
        assertTrue(pub.bodyAsText().contains("секретная работа для отчёта"))
    }

    @Test
    fun `чужой отчёт не отдаётся по id`() = testApplication {
        application { module() }
        val tokenA = registerToken(client, "ra@b.co")
        val tokenB = registerToken(client, "rb@b.co")
        addEntry(client, tokenA, "работа A")
        val reportA = makeReport(client, tokenA)

        val direct = client.get("/api/reports/${reportA.id}") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.NotFound, direct.status)
    }
}
