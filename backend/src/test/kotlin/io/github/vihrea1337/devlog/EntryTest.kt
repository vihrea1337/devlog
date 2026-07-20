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
import kotlin.test.assertTrue

/**
 * Интеграционные тесты записей на H2. Проверяем полный CRUD и — главное — изоляцию:
 * один пользователь не видит и не трогает записи другого. Structured (ИИ) здесь не участвует.
 */
class EntryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_entries;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, Projects, Entries) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(Entries, Projects, Users) }
    }

    private suspend fun registerToken(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun createEntry(client: HttpClient, token: String, text: String): EntryDto {
        val r = client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-07-20","rawText":"$text"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        return json.decodeFromString<EntryDto>(r.bodyAsText())
    }

    @Test
    fun `создание, список и чтение записи`() = testApplication {
        application { module() }
        val token = registerToken(client, "u1@b.co")

        val created = createEntry(client, token, "Чинил баг с виджетом")
        assertEquals("queued", created.status)
        assertEquals("manual", created.source)

        val list = client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("виджет"))

        val one = client.get("/api/entries/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, one.status)
    }

    @Test
    fun `правка меняет текст`() = testApplication {
        application { module() }
        val token = registerToken(client, "u2@b.co")
        val created = createEntry(client, token, "старый текст")

        val upd = client.put("/api/entries/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"rawText":"новый текст"}""")
        }
        assertEquals(HttpStatusCode.OK, upd.status)
        assertTrue(json.decodeFromString<EntryDto>(upd.bodyAsText()).rawText == "новый текст")
    }

    @Test
    fun `удаление убирает запись`() = testApplication {
        application { module() }
        val token = registerToken(client, "u3@b.co")
        val created = createEntry(client, token, "на удаление")

        val del = client.delete("/api/entries/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NoContent, del.status)

        val gone = client.get("/api/entries/${created.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NotFound, gone.status)
    }

    @Test
    fun `чужую запись не видно (изоляция пользователей)`() = testApplication {
        application { module() }
        val tokenA = registerToken(client, "a@b.co")
        val tokenB = registerToken(client, "b@b.co")

        val entryA = createEntry(client, tokenA, "секрет A")

        // B не видит запись A ни в списке, ни по прямому id.
        val listB = client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals("[]", listB.bodyAsText())

        val directB = client.get("/api/entries/${entryA.id}") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.NotFound, directB.status)
    }

    @Test
    fun `без токена — 401`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/entries").status)
    }
}
