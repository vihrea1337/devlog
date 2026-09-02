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
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Импорт произвольного текстового лога (в т.ч. Claude Code). В сеть не ходим —
 * TextImporter.import() работает только с переданной строкой и базой.
 */
class TextImportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_textimport;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
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

    private suspend fun importText(client: HttpClient, token: String, text: String): io.ktor.client.statement.HttpResponse =
        client.post("/api/import/text") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(TextImportRequest.serializer(), TextImportRequest(text)))
        }

    // --- Чистая логика разбиения на дни (splitByDay) ---

    @Test
    fun `строки без даты в начале текста отбрасываются`() {
        val text = """
            это ещё не датировано
            2026-09-01 первая настоящая строка
            вторая строка того же дня (без даты в строке)
        """.trimIndent()

        val byDay = TextImporter.splitByDay(text)

        assertEquals(setOf(LocalDate.parse("2026-09-01")), byDay.keys)
        assertEquals(2, byDay.getValue(LocalDate.parse("2026-09-01")).size)
    }

    @Test
    fun `несколько дней подряд режутся по датам внутри строк`() {
        val text = """
            2026-09-01T10:15:00Z пользователь: разобрались с гонкой в EntryRepository
            2026-09-01T10:20:00Z claude: нашли и починили
            2026-09-02T09:00:00Z пользователь: импорт текста
        """.trimIndent()

        val byDay = TextImporter.splitByDay(text)

        assertEquals(2, byDay.size)
        assertEquals(2, byDay.getValue(LocalDate.parse("2026-09-01")).size)
        assertEquals(1, byDay.getValue(LocalDate.parse("2026-09-02")).size)
    }

    @Test
    fun `пустые строки и текст без единой даты`() {
        assertTrue(TextImporter.splitByDay("просто текст\nбез единой даты нигде").isEmpty())
        assertTrue(TextImporter.splitByDay("\n\n   \n").isEmpty())
    }

    @Test
    fun `невалидная на вид дата не режет день`() {
        // 2026-13-40 матчит регекс \d{4}-\d{2}-\d{2}, но не разбирается в LocalDate —
        // строка должна остаться при предыдущем дне (или быть отброшена, если дня ещё не было).
        val text = """
            2026-09-01 начало
            2026-13-40 это не настоящая дата
        """.trimIndent()

        val byDay = TextImporter.splitByDay(text)

        assertEquals(setOf(LocalDate.parse("2026-09-01")), byDay.keys)
        assertEquals(2, byDay.getValue(LocalDate.parse("2026-09-01")).size, "вторая строка приклеилась к предыдущему дню")
    }

    // --- Дедупликация по содержимому (entryIdFor) ---

    @Test
    fun `id записи одинаковый для того же дня и текста, разный при разном тексте`() {
        val day = LocalDate.parse("2026-09-01")
        val userId = java.util.UUID.randomUUID()
        val linesA = listOf("2026-09-01 привет")
        val linesB = listOf("2026-09-01 другой текст")

        assertEquals(TextImporter.entryIdFor(userId, day, linesA), TextImporter.entryIdFor(userId, day, linesA))
        assertTrue(TextImporter.entryIdFor(userId, day, linesA) != TextImporter.entryIdFor(userId, day, linesB))
    }

    // --- Интеграционные тесты через HTTP ---

    @Test
    fun `импорт создаёт по одной записи на день`() = testApplication {
        application { module() }
        val t = token(client, "textimport1@b.co")
        val text = """
            2026-09-01 обсудили офлайн-кэш
            2026-09-01 дописали SQLDelight
            2026-09-02 занялись импортом текста
        """.trimIndent()

        val r = importText(client, t, text)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val result = json.decodeFromString<TextImportResultDto>(r.bodyAsText())

        assertEquals(2, result.days)
        assertEquals(3, result.linesMatched)
        assertEquals(2, result.created)
        assertEquals(0, result.skipped)

        val feed = json.decodeFromString<List<EntryDto>>(
            client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText(),
        )
        assertEquals(2, feed.size)
        assertTrue(feed.all { it.source == "text-import" })
    }

    @Test
    fun `повторный импорт того же текста не дублирует записи`() = testApplication {
        application { module() }
        val t = token(client, "textimport2@b.co")
        val text = "2026-09-01 одна и та же строка"

        val first = json.decodeFromString<TextImportResultDto>(importText(client, t, text).bodyAsText())
        val second = json.decodeFromString<TextImportResultDto>(importText(client, t, text).bodyAsText())

        assertEquals(1, first.created)
        assertEquals(0, second.created)
        assertEquals(1, second.skipped)
    }

    @Test
    fun `другой лог за тот же день не затирает предыдущий, а ложится отдельной записью`() = testApplication {
        application { module() }
        val t = token(client, "textimport3@b.co")

        importText(client, t, "2026-09-01 первый лог за этот день")
        val second = json.decodeFromString<TextImportResultDto>(
            importText(client, t, "2026-09-01 совсем другой лог, тот же день").bodyAsText(),
        )

        assertEquals(1, second.created, "разный текст на тот же день — новая запись, а не пропуск")
        val feed = json.decodeFromString<List<EntryDto>>(
            client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText(),
        )
        assertEquals(2, feed.size)
    }

    @Test
    fun `текст без дат возвращает понятную ошибку 400`() = testApplication {
        application { module() }
        val t = token(client, "textimport4@b.co")

        val r = importText(client, t, "тут вообще нет никаких дат")

        assertEquals(HttpStatusCode.BadRequest, r.status)
    }
}
