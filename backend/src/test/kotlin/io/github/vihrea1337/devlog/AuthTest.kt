package io.github.vihrea1337.devlog

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
 * Интеграционные тесты авторизации на H2 (база в памяти, живёт только на время теста).
 * Проверяем весь путь: регистрация → выдача токена → доступ к /api/me по токену,
 * вход по паролю, дубликат email, доступ без токена. Реальный Postgres/Docker не нужен.
 */
class AuthTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_auth;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(Users) }
    }

    @Test
    fun `регистрация выдаёт токен и с ним работает me`() = testApplication {
        application { module() }

        val reg = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"Test@Example.com","password":"secret1","displayName":"Тест"}""")
        }
        assertEquals(HttpStatusCode.OK, reg.status)
        val token = json.decodeFromString<AuthResponse>(reg.bodyAsText()).token

        val me = client.get("/api/me") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("Тест"))
    }

    @Test
    fun `me без токена отвечает 401`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
    }

    @Test
    fun `вход с верным паролем ок, с неверным 401`() = testApplication {
        application { module() }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.co","password":"secret1","displayName":"A"}""")
        }

        val ok = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.co","password":"secret1"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)

        val bad = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.co","password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
    }

    @Test
    fun `повторная регистрация того же email отвечает 409`() = testApplication {
        application { module() }
        val payload = """{"email":"dup@b.co","password":"secret1","displayName":"D"}"""
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val second = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
    }
}
