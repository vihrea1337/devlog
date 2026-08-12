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
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Импорт коммитов с GitHub. В сеть не ходим: подменяем загрузку событий на кусок
 * настоящего ответа GitHub — так тесты стабильны и не зависят от лимитов API.
 */
class GithubImportTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val today: LocalDate = LocalDate.now()
    private val yesterday: LocalDate = today.minusDays(1)

    /** Кусок ответа /users/{login}/events/public: два пуша за сегодня и один за вчера. */
    private fun eventsJson(): String = """
        [
          {
            "type": "PushEvent",
            "created_at": "${today}T10:15:00Z",
            "repo": { "name": "vihrea1337/devlog" },
            "payload": { "commits": [
              { "sha": "aaaaaaaaaaaaaaaa", "message": "Мягкое удаление записей\n\nтело коммита" },
              { "sha": "bbbbbbbbbbbbbbbb", "message": "Тесты синхронизации" }
            ] }
          },
          {
            "type": "WatchEvent",
            "created_at": "${today}T11:00:00Z",
            "repo": { "name": "someone/other" },
            "payload": {}
          },
          {
            "type": "PushEvent",
            "created_at": "${today}T12:00:00Z",
            "repo": { "name": "vihrea1337/devlog" },
            "payload": { "commits": [
              { "sha": "aaaaaaaaaaaaaaaa", "message": "Мягкое удаление записей" }
            ] }
          },
          {
            "type": "PushEvent",
            "created_at": "${yesterday}T09:00:00Z",
            "repo": { "name": "vihrea1337/skycast" },
            "payload": { "commits": [
              { "sha": "cccccccccccccccc", "message": "Починил виджет" }
            ] }
          }
        ]
    """.trimIndent()

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_github;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, Projects, Entries, EntryStructured) }
        GithubImporter.fetchEvents = { eventsJson() }
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

    private suspend fun setLogin(client: HttpClient, token: String, login: String) {
        val r = client.put("/api/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"githubLogin":"$login"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    private suspend fun import(client: HttpClient, token: String): ImportResultDto {
        val r = client.post("/api/import/github") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"${today.minusDays(7)}","periodEnd":"$today"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json.decodeFromString(r.bodyAsText())
    }

    @Test
    fun `разбор ответа GitHub - берём только пуши и уникальные коммиты`() {
        val byDay = GithubImporter.parseCommitsByDay(eventsJson(), today.minusDays(7), today)

        assertEquals(2, byDay.size, "два дня с коммитами")
        assertEquals(2, byDay[today]!!.size, "повтор того же коммита не должен удваиваться")
        assertEquals(1, byDay[yesterday]!!.size)
        assertTrue(byDay[yesterday]!!.single().repo.endsWith("skycast"))
    }

    @Test
    fun `коммиты вне периода не берутся`() {
        val byDay = GithubImporter.parseCommitsByDay(eventsJson(), today, today)
        assertEquals(setOf(today), byDay.keys)
    }

    @Test
    fun `текст записи содержит репозиторий, суть коммита и короткий sha`() {
        val commits = listOf(GithubCommit("vihrea1337/devlog", "Мягкое удаление\n\nдетали", "abcdef1234567890"))
        val text = GithubImporter.buildEntryText(today, commits)

        assertTrue(text.contains("vihrea1337/devlog"))
        assertTrue(text.contains("Мягкое удаление"))
        assertTrue(text.contains("abcdef1"), "нужен короткий sha: $text")
        assertTrue(!text.contains("детали"), "тело коммита в заголовок не тащим")
    }

    @Test
    fun `импорт создаёт по одной записи на день`() = testApplication {
        application { module() }
        val t = token(client, "gh1@b.co")
        setLogin(client, t, "vihrea1337")

        val result = import(client, t)

        assertEquals(2, result.days)
        assertEquals(3, result.commits)
        assertEquals(2, result.created)
        assertEquals(0, result.skipped)

        val feed = json.decodeFromString<List<EntryDto>>(
            client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText(),
        )
        assertEquals(2, feed.size)
        assertTrue(feed.all { it.source == "github" }, "источник записи — github")
        assertTrue(feed.any { it.rawText.contains("Тесты синхронизации") })
    }

    @Test
    fun `повторный импорт не создаёт дублей`() = testApplication {
        application { module() }
        val t = token(client, "gh2@b.co")
        setLogin(client, t, "vihrea1337")

        import(client, t)
        val second = import(client, t)

        assertEquals(0, second.created, "всё уже импортировано")
        assertEquals(2, second.skipped)
        val feed = json.decodeFromString<List<EntryDto>>(
            client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText(),
        )
        assertEquals(2, feed.size, "дней всё так же два")
    }

    @Test
    fun `без логина GitHub импорт просит его указать`() = testApplication {
        application { module() }
        val t = token(client, "gh3@b.co")

        val r = client.post("/api/import/github") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"${today.minusDays(3)}","periodEnd":"$today"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("логин"))
    }

    @Test
    fun `кривой логин GitHub не принимается`() = testApplication {
        application { module() }
        val t = token(client, "gh4@b.co")

        val r = client.put("/api/me") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"githubLogin":"не логин!"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `сбой GitHub не роняет сервер, а объясняется пользователю`() = testApplication {
        application { module() }
        val t = token(client, "gh5@b.co")
        setLogin(client, t, "vihrea1337")
        GithubImporter.fetchEvents = { error("Пользователь GitHub не найден") }

        val r = client.post("/api/import/github") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"${today.minusDays(3)}","periodEnd":"$today"}""")
        }

        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertTrue(r.bodyAsText().contains("не найден"))
    }

    @Test
    fun `слишком старый период отклоняется с объяснением`() = testApplication {
        application { module() }
        val t = token(client, "gh6@b.co")
        setLogin(client, t, "vihrea1337")

        val r = client.post("/api/import/github") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"${today.minusDays(300)}","periodEnd":"$today"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
    }
}
