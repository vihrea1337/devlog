package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Личный токен GitHub: привязка/отвязка через API, шифрование в БД, и то, что токен
 * никогда не возвращается обратно клиенту — только факт привязки (githubTokenBound).
 */
class GithubTokenTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_ghtoken;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, GithubIntegrations, GitlabIntegrations) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(GitlabIntegrations, GithubIntegrations, Users) }
    }

    private suspend fun token(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    // --- TokenCipher: чистое шифрование, без сети и БД ---

    @Test
    fun `TokenCipher расшифровывает то, что сам зашифровал`() {
        val original = "ghp_1234567890abcdefABCDEF"
        val encrypted = TokenCipher.encrypt(original)

        assertNotEquals(original, encrypted, "в БД не должен лежать открытый текст")
        assertEquals(original, TokenCipher.decrypt(encrypted))
    }

    @Test
    fun `TokenCipher даёт разный шифротекст на один и тот же токен (случайный IV)`() {
        val a = TokenCipher.encrypt("secret")
        val b = TokenCipher.encrypt("secret")
        assertNotEquals(a, b)
        assertEquals("secret", TokenCipher.decrypt(a))
        assertEquals("secret", TokenCipher.decrypt(b))
    }

    @Test
    fun `TokenCipher decrypt на мусорной строке возвращает null, а не роняет`() {
        assertNull(TokenCipher.decrypt("не base64 и вообще не шифр"))
    }

    // --- HTTP: привязка/отвязка, отсутствие эха токена ---

    @Test
    fun `привязка токена помечает githubTokenBound и не возвращает сам токен`() = testApplication {
        application { module() }
        val t = token(client, "ghtoken1@b.co")

        val r = client.post("/api/me/github-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"ghp_supersecret"}""")
        }

        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("\"githubTokenBound\":true"))
        assertFalse(body.contains("ghp_supersecret"), "сам токен не должен попадать в ответ")
    }

    @Test
    fun `в базе токен хранится не в открытом виде`() = testApplication {
        application { module() }
        val t = token(client, "ghtoken2@b.co")

        client.post("/api/me/github-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"ghp_plaintext_check"}""")
        }

        val stored = transaction {
            GithubIntegrations.selectAll().first()[GithubIntegrations.encryptedToken]
        }
        assertFalse(stored.contains("ghp_plaintext_check"), "в столбце не должно быть открытого текста")
    }

    @Test
    fun `отвязка снимает githubTokenBound`() = testApplication {
        application { module() }
        val t = token(client, "ghtoken3@b.co")
        client.post("/api/me/github-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"ghp_x"}""")
        }

        val r = client.delete("/api/me/github-token") { header(HttpHeaders.Authorization, "Bearer $t") }

        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"githubTokenBound\":false"))
    }

    @Test
    fun `повторная привязка заменяет токен, а не добавляет вторую запись`() = testApplication {
        application { module() }
        val t = token(client, "ghtoken4@b.co")
        val userId = json.decodeFromString<AuthResponse>(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"ghtoken4@b.co","password":"secret1"}""")
            }.bodyAsText(),
        ).user.id

        client.post("/api/me/github-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"ghp_first"}""")
        }
        client.post("/api/me/github-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"ghp_second"}""")
        }

        val rows = transaction {
            GithubIntegrations.selectAll().where { GithubIntegrations.userId eq UUID.fromString(userId) }.count()
        }
        assertEquals(1, rows, "должна остаться одна строка, а не две")
        assertEquals("ghp_second", GithubTokenRepository.tokenFor(UUID.fromString(userId)))
    }

    @Test
    fun `пустой токен — 400`() = testApplication {
        application { module() }
        val t = token(client, "ghtoken5@b.co")

        val r = client.post("/api/me/github-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `непривязанный токен даёт githubTokenBound false по умолчанию`() = testApplication {
        application { module() }
        val t = token(client, "ghtoken6@b.co")

        val r = client.get("/api/me") { header(HttpHeaders.Authorization, "Bearer $t") }

        assertTrue(r.bodyAsText().contains("\"githubTokenBound\":false"))
    }
}
