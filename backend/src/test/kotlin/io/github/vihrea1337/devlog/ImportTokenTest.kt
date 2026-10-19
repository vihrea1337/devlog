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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Токены для скриптов (ImportTokens.kt): выдаются один раз, хранятся хешем, пускают ТОЛЬКО
 * в импорт, отзываются. Это граница безопасности — проверяем и то, куда токен не пускает.
 */
class ImportTokenTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_importtokens;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, GithubIntegrations, GitlabIntegrations, ImportTokens, Projects, Entries, EntryStructured, Reports) }
    }

    @AfterTest
    fun teardown() {
        DemoAccess.email = null
        transaction { SchemaUtils.drop(Reports, EntryStructured, Entries, Projects, ImportTokens, GitlabIntegrations, GithubIntegrations, Users) }
    }

    private suspend fun register(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun issue(client: HttpClient, jwt: String, name: String = "Домашний ПК") = client.post("/api/me/import-tokens") {
        header(HttpHeaders.Authorization, "Bearer $jwt")
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name"}""")
    }

    private suspend fun importDay(client: HttpClient, bearer: String) = client.post("/api/import/day-files") {
        header(HttpHeaders.Authorization, "Bearer $bearer")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(DayFilesImportRequest.serializer(), DayFilesImportRequest(listOf(DayFileDto("2026-10-19.md", "## Сделано\n- экран")))))
    }

    @Test
    fun `токен скрипта импортирует файлы дня от имени владельца`() = testApplication {
        application { module() }
        val jwt = register(client, "tok1@b.co")
        val created = json.decodeFromString<CreatedImportTokenDto>(issue(client, jwt).bodyAsText())
        assertTrue(created.token.startsWith("dli_"))

        val r = importDay(client, created.token)

        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val feed = json.decodeFromString<List<EntryDto>>(client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $jwt") }.bodyAsText())
        assertEquals(1, feed.size, "запись у владельца токена")
        val listed = json.decodeFromString<List<ImportTokenDto>>(client.get("/api/me/import-tokens") { header(HttpHeaders.Authorization, "Bearer $jwt") }.bodyAsText())
        assertNotNull(listed.single().lastUsedAt, "использование отмечено")
    }

    @Test
    fun `токен скрипта не пускает никуда, кроме импорта`() = testApplication {
        application { module() }
        val jwt = register(client, "tok2@b.co")
        val token = json.decodeFromString<CreatedImportTokenDto>(issue(client, jwt).bodyAsText()).token
        val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/entries", auth).status, "читать записи нельзя")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/reports", auth).status, "читать отчёты нельзя")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me", auth).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me/import-tokens", auth).status, "выпускать токены токеном нельзя")
        val gh = client.post("/api/import/github") {
            auth()
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"2026-10-01","periodEnd":"2026-10-02"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, gh.status, "импорт GitHub ходит в сеть с токеном пользователя — только обычный вход")
    }

    @Test
    fun `в базе хранится только хеш, отозванный токен перестаёт работать`() = testApplication {
        application { module() }
        val jwt = register(client, "tok3@b.co")
        val created = json.decodeFromString<CreatedImportTokenDto>(issue(client, jwt).bodyAsText())

        val stored = transaction { ImportTokens.selectAll().single()[ImportTokens.tokenHash] }
        assertFalse(stored.contains(created.token.removePrefix("dli_")), "сам токен в базе не лежит")
        assertFalse(json.encodeToString(ImportTokenDto.serializer(), created.info).contains(created.token), "в описании токена нет")

        val del = client.delete("/api/me/import-tokens/${created.info.id}") { header(HttpHeaders.Authorization, "Bearer $jwt") }
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertEquals(HttpStatusCode.Unauthorized, importDay(client, created.token).status)
    }

    @Test
    fun `чужой токен не отозвать, выдуманный не принимается, лимит и имя проверяются`() = testApplication {
        application { module() }
        val owner = register(client, "tok4@b.co")
        val stranger = register(client, "tok5@b.co")
        val created = json.decodeFromString<CreatedImportTokenDto>(issue(client, owner).bodyAsText())

        val del = client.delete("/api/me/import-tokens/${created.info.id}") { header(HttpHeaders.Authorization, "Bearer $stranger") }
        assertEquals(HttpStatusCode.NotFound, del.status)
        assertEquals(HttpStatusCode.Unauthorized, importDay(client, "dli_madeUpTokenValue").status)
        assertNull(ImportTokenRepository.userFor("не-токен"))
        assertEquals(HttpStatusCode.BadRequest, issue(client, owner, name = " ").status)

        repeat(ImportTokenRepository.MAX_PER_USER - 1) { assertEquals(HttpStatusCode.OK, issue(client, owner).status) }
        assertEquals(HttpStatusCode.BadRequest, issue(client, owner).status, "одиннадцатый — уже нет")
    }

    @Test
    fun `демо-вход «только просмотр» токен выпустить не может`() = testApplication {
        application { module() }
        register(client, "demo-tok@b.co")
        DemoAccess.email = "demo-tok@b.co"
        val demo = json.decodeFromString<AuthResponse>(client.post("/api/auth/demo").bodyAsText()).token

        assertEquals(HttpStatusCode.Forbidden, issue(client, demo).status)
    }
}
