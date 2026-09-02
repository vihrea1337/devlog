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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
        transaction { SchemaUtils.create(Users, GithubIntegrations, Projects, Entries, EntryStructured) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(EntryStructured, Entries, Projects, GithubIntegrations, Users) }
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

    private suspend fun changes(client: HttpClient, token: String, since: String? = null, limit: Int? = null): EntryChangesDto {
        val params = listOfNotNull(
            since?.let { "since=$it" },
            limit?.let { "limit=$it" },
        )
        val q = if (params.isEmpty()) "" else "?" + params.joinToString("&")
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

        // Курсор для следующей страницы — serverTime из ответа (как делает реальный клиент),
        // а не сырой updatedAt последней записи: несколько записей из repeat(3) вполне могут
        // получить одинаковый updatedAt (быстрое создание подряд), и тогда "since = updatedAt"
        // потерял бы запись с тем же временем, что не попала в эту страницу. serverTime —
        // это sync_seq (строго монотонный счётчик БД, см. Entries.syncSeq в Tables.kt),
        // а не updated_at, поэтому такой проблемы нет в принципе.
        val rest = changes(client, t, first.serverTime)
        assertEquals(1, rest.entries.size)
        assertFalse(rest.hasMore)
    }

    /**
     * Регрессия на баг, из-за которого флакал предыдущий тест: раньше пагинация была
     * завязана на updated_at (сначала — голый `greater since`, затем — составной курсор
     * updated_at+id для тай-брейка). Оба варианта ломались, если несколько записей получали
     * ОДИНАКОВЫЙ updated_at (легко происходит при быстром создании подряд): без тай-брейка
     * такая запись навсегда терялась между страницами, а тай-брейк по случайному id мог
     * ИСКЛЮЧИТЬ более новую запись, если её UUID оказался "меньше" id записи-курсора — id
     * не связан с реальным порядком вставки никак. Курсор синхронизации теперь — sync_seq
     * (строго монотонный счётчик БД, см. Tables.kt), у него по определению не бывает
     * совпадений, поэтому тест форсирует одинаковый updated_at (то, что раньше требовало
     * удачи с таймингом) и проверяет, что обе записи всё равно находятся — и в общем
     * ответе, и при постраничном обходе с лимитом 1 (самое чувствительное место старого бага).
     */
    @Test
    fun `несколько записей с одинаковым updatedAt не теряются между страницами`() = testApplication {
        application { module() }
        val t = token(client, "s10@b.co")
        val e1 = addEntry(client, t, "первая из пары с одинаковым временем")
        val e2 = addEntry(client, t, "вторая из пары с одинаковым временем")
        addEntry(client, t, "третья, время другое")

        // Форсируем совпадение updatedAt у первых двух — то, что раньше требовало удачи
        // с таймингом на быстрой машине, здесь гарантировано.
        val tiedInstant = java.time.Instant.parse(
            json.decodeFromString<EntryDto>(
                client.get("/api/entries/${e1.id}") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText(),
            ).updatedAt,
        )
        transaction {
            Entries.update({ Entries.id eq UUID.fromString(e2.id) }) { it[updatedAt] = tiedInstant }
        }

        val page1 = changes(client, t, since = null)
        // limit по умолчанию большой — все три должны прийти одной страницей, но проверяем
        // главное: обе "одновременные" записи присутствуют, ни одна не потерялась.
        val ids = page1.entries.map { it.id }.toSet()
        assertTrue(e1.id in ids && e2.id in ids, "обе записи с одинаковым updatedAt должны быть в ответе: $ids")

        // Тот же сценарий, но со страницами по ОДНОЙ записи — именно та граница, где раньше
        // терялась запись: лимит=1 разрежет ровно между двумя записями с одинаковым updatedAt.
        var since: String? = null
        val collected = mutableListOf<EntryDto>()
        for (i in 1..10) {
            val page = changes(client, t, since, limit = 1)
            collected += page.entries
            since = page.serverTime
            if (!page.hasMore) break
        }
        assertEquals(3, collected.distinctBy { it.id }.size, "все три записи должны быть собраны постранично без потерь и дублей")
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
