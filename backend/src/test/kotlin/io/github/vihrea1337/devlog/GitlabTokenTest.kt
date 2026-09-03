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
import kotlin.test.assertTrue

/**
 * Личный токен GitLab: привязка/отвязка через API, шифрование в БД (тот же TokenCipher,
 * что у GitHub — см. GithubTokenTest для его отдельных юнит-тестов, здесь их не дублируем),
 * и то, что токен никогда не возвращается обратно клиенту — только факт привязки.
 */
class GitlabTokenTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_gltoken;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
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

    @Test
    fun `привязка токена помечает gitlabTokenBound и не возвращает сам токен`() = testApplication {
        application { module() }
        val t = token(client, "gltoken1@b.co")

        val r = client.post("/api/me/gitlab-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"glpat-supersecret"}""")
        }

        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("\"gitlabTokenBound\":true"))
        assertFalse(body.contains("glpat-supersecret"), "сам токен не должен попадать в ответ")
    }

    @Test
    fun `в базе токен хранится не в открытом виде`() = testApplication {
        application { module() }
        val t = token(client, "gltoken2@b.co")

        client.post("/api/me/gitlab-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"glpat-plaintext-check"}""")
        }

        val stored = transaction {
            GitlabIntegrations.selectAll().first()[GitlabIntegrations.encryptedToken]
        }
        assertFalse(stored.contains("glpat-plaintext-check"), "в столбце не должно быть открытого текста")
    }

    @Test
    fun `отвязка снимает gitlabTokenBound`() = testApplication {
        application { module() }
        val t = token(client, "gltoken3@b.co")
        client.post("/api/me/gitlab-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"glpat-x"}""")
        }

        val r = client.delete("/api/me/gitlab-token") { header(HttpHeaders.Authorization, "Bearer $t") }

        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"gitlabTokenBound\":false"))
    }

    @Test
    fun `повторная привязка заменяет токен, а не добавляет вторую запись`() = testApplication {
        application { module() }
        val t = token(client, "gltoken4@b.co")
        val userId = json.decodeFromString<AuthResponse>(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"gltoken4@b.co","password":"secret1"}""")
            }.bodyAsText(),
        ).user.id

        client.post("/api/me/gitlab-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"glpat-first"}""")
        }
        client.post("/api/me/gitlab-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"glpat-second"}""")
        }

        val rows = transaction {
            GitlabIntegrations.selectAll().where { GitlabIntegrations.userId eq UUID.fromString(userId) }.count()
        }
        assertEquals(1, rows, "должна остаться одна строка, а не две")
        assertEquals("glpat-second", GitlabTokenRepository.tokenFor(UUID.fromString(userId)))
    }

    @Test
    fun `пустой токен — 400`() = testApplication {
        application { module() }
        val t = token(client, "gltoken5@b.co")

        val r = client.post("/api/me/gitlab-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `непривязанный токен даёт gitlabTokenBound false по умолчанию`() = testApplication {
        application { module() }
        val t = token(client, "gltoken6@b.co")

        val r = client.get("/api/me") { header(HttpHeaders.Authorization, "Bearer $t") }

        assertTrue(r.bodyAsText().contains("\"gitlabTokenBound\":false"))
    }
}
