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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Синхронизация нескольких устройств: мягкое удаление, выборка изменений и защита
 * от дублей при повторной отправке.
 *
 * Зачем: раньше DELETE стирал строку физически. Телефон, который был офлайн, никак
 * не узнал бы об удалении — запись «воскресла» бы при следующей синхронизации.
 */
class SyncTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_sync;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, Projects, Entries, EntryStructured) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(EntryStructured, Entries, Projects, Users) }
    }

    private suspend fun token(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun addEntry(client: HttpClient, token: String, text: String, id: String? = null): EntryDto {
        val idPart = if (id != null) ""","id":"$id"""" else ""
        val r = client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-08-12","rawText":"$text"$idPart}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json.decodeFromString(r.bodyAsText())
    }

    private suspend fun changes(client: HttpClient, token: String, since: String? = null): EntryChangesDto {
        val q = if (since != null) "?since=$since" else ""
        val r = client.get("/api/entries/changes$q") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json.decodeFromString(r.bodyAsText())
    }

    @Test
    fun `удалённая запись исчезает из ленты, но остаётся надгробием для синхронизации`() = testApplication {
        application { module() }
        val t = token(client, "s1@b.co")
        val entry = addEntry(client, t, "запись под удаление")

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/entries/${entry.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
        }.status)

        // В ленте её нет, по прямому id — 404.
        assertEquals("[]", client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText())
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/entries/${entry.id}") { header(HttpHeaders.Authorization, "Bearer $t") }.status,
        )

        // А в синхронизации — приходит с пометкой удаления.
        val tombstone = changes(client, t).entries.single()
        assertEquals(entry.id, tombstone.id)
        assertTrue(tombstone.deleted, "запись должна быть помечена удалённой")
        assertEquals("", tombstone.rawText, "текст удалённой записи хранить незачем")
    }

    @Test
    fun `при удалении стирается и разбор ИИ`() = testApplication {
        application { module() }
        val t = token(client, "s2@b.co")
        val entry = addEntry(client, t, "запись со структурой")
        EntryStructuredRepository.save(
            UUID.fromString(entry.id),
            StructuredDto(summary = "секретная суть", tags = listOf("тег")),
            "test-model",
        )

        client.delete("/api/entries/${entry.id}") { header(HttpHeaders.Authorization, "Bearer $t") }

        assertNull(EntryStructuredRepository.forEntry(UUID.fromString(entry.id)))
        assertNull(changes(client, t).entries.single().structured)
    }

    @Test
    fun `since отдаёт только то, что изменилось позже`() = testApplication {
        application { module() }
        val t = token(client, "s3@b.co")
        addEntry(client, t, "первая")

        val firstSync = changes(client, t)
        assertEquals(1, firstSync.entries.size)

        // Ничего не менялось — ответ пустой.
        assertEquals(0, changes(client, t, firstSync.serverTime).entries.size)

        addEntry(client, t, "вторая")
        val second = changes(client, t, firstSync.serverTime)
        assertEquals(1, second.entries.size, "должна прийти только новая запись")
        assertTrue(second.entries.single().rawText.contains("вторая"))
    }

    @Test
    fun `изменения приходят по возрастанию времени и отдаются порциями`() = testApplication {
        application { module() }
        val t = token(client, "s4@b.co")
        repeat(3) { addEntry(client, t, "запись $it") }

        val page = client.get("/api/entries/changes?limit=2") { header(HttpHeaders.Authorization, "Bearer $t") }
        val first = json.decodeFromString<EntryChangesDto>(page.bodyAsText())
        assertEquals(2, first.entries.size)
        assertTrue(first.hasMore, "порция заполнена — клиенту надо прийти ещё раз")
        assertTrue(first.entries[0].updatedAt <= first.entries[1].updatedAt, "порядок по возрастанию updatedAt")

        val rest = changes(client, t, first.entries.last().updatedAt)
        assertEquals(1, rest.entries.size)
        assertFalse(rest.hasMore)
    }

    @Test
    fun `повторная отправка с тем же id не создаёт дубль`() = testApplication {
        application { module() }
        val t = token(client, "s5@b.co")
        val clientId = UUID.randomUUID().toString()

        val first = addEntry(client, t, "отправлено при плохой сети", clientId)
        val second = addEntry(client, t, "отправлено при плохой сети", clientId)

        assertEquals(first.id, second.id)
        assertEquals(clientId, first.id, "сервер должен уважать id клиента")
        val feed = json.decodeFromString<List<EntryDto>>(
            client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText(),
        )
        assertEquals(1, feed.size, "дубля быть не должно")
    }

    @Test
    fun `нельзя занять id чужой записи`() = testApplication {
        application { module() }
        val owner = token(client, "s6@b.co")
        val stranger = token(client, "s7@b.co")
        val mine = addEntry(client, owner, "моя запись")

        val res = client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $stranger")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-08-12","rawText":"подмена","id":"${mine.id}"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, res.status)
        // Чужая запись не пострадала.
        val still = client.get("/api/entries/${mine.id}") { header(HttpHeaders.Authorization, "Bearer $owner") }
        assertTrue(still.bodyAsText().contains("моя запись"))
    }

    @Test
    fun `кривой since — 400`() = testApplication {
        application { module() }
        val t = token(client, "s8@b.co")
        val res = client.get("/api/entries/changes?since=вчера") { header(HttpHeaders.Authorization, "Bearer $t") }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `воркер не берёт удалённые записи`() = testApplication {
        application { module() }
        val t = token(client, "s9@b.co")
        val entry = addEntry(client, t, "удалю до обработки")
        client.delete("/api/entries/${entry.id}") { header(HttpHeaders.Authorization, "Bearer $t") }

        val fake = object : AiStructurer {
            override val modelName = "fake"
            override suspend fun structure(rawText: String) = StructuredDto(summary = "не должно случиться")
        }
        assertFalse(runBlocking { AiWorker(fake, limiter = { true }).runOnce() }, "надгробие не должно попасть в очередь")
    }
}
