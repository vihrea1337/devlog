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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Импорт коммитов с GitHub. В сеть не ходим: подменяем и загрузку событий (fetchEvents),
 * и догрузку коммитов через Compare API (fetchCompare) — так тесты стабильны и не зависят
 * от лимитов настоящего GitHub API.
 *
 * Форма фикстур ниже соответствует РЕАЛЬНОМУ ответу GitHub (проверено вручную через
 * gh auth token, см. docs/roadmap.md, Фаза 4): у PushEvent нет `payload.commits`,
 * только `before`/`head` — коммиты достаются отдельным запросом на Compare API.
 */
class GithubImportTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val today: LocalDate = LocalDate.now()
    private val yesterday: LocalDate = today.minusDays(1)

    /** Три пуша: два сегодня (один репозиторий) и один вчера (другой репозиторий). */
    private fun eventsJson(): String = """
        [
          {
            "type": "PushEvent",
            "created_at": "${today}T10:15:00Z",
            "repo": { "name": "vihrea1337/devlog" },
            "payload": { "before": "aaa1111111111111111111111111111111111111", "head": "aaa2222222222222222222222222222222222222" }
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
            "payload": { "before": "aaa2222222222222222222222222222222222222", "head": "aaa3333333333333333333333333333333333333" }
          },
          {
            "type": "PushEvent",
            "created_at": "${yesterday}T09:00:00Z",
            "repo": { "name": "vihrea1337/skycast" },
            "payload": { "before": "bbb1111111111111111111111111111111111111", "head": "bbb2222222222222222222222222222222222222" }
          }
        ]
    """.trimIndent()

    /** Ответ Compare API для первого пуша дня (aaa1..aaa2): два коммита. */
    private fun compareTodayFirstPush(): String = """
        {"commits": [
          {"sha": "c1", "commit": {"message": "Мягкое удаление записей\n\nтело коммита"}},
          {"sha": "c2", "commit": {"message": "Тесты синхронизации"}}
        ]}
    """.trimIndent()

    /** Ответ на второй пуш дня (aaa2..aaa3): один коммит, sha повторяет c1 (проверка дедупа). */
    private fun compareTodaySecondPush(): String = """{"commits": [{"sha": "c1", "commit": {"message": "Мягкое удаление записей"}}]}"""

    private fun compareYesterdayPush(): String = """{"commits": [{"sha": "c4", "commit": {"message": "Починил виджет"}}]}"""

    /** Стандартный fetchCompare-мок: раздаёт фикстуры по (before, head), остальное — ошибка. */
    private fun defaultFetchCompare(): suspend (String, String, String, String?) -> String = { _, before, head, _ ->
        when {
            before.startsWith("aaa1") -> compareTodayFirstPush()
            before.startsWith("aaa2") -> compareTodaySecondPush()
            before.startsWith("bbb1") -> compareYesterdayPush()
            else -> error("неожиданный compare: $before...$head")
        }
    }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_github;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, GithubIntegrations, Projects, Entries, EntryStructured) }
        GithubImporter.fetchEvents = { _, _ -> eventsJson() }
        GithubImporter.fetchCompare = defaultFetchCompare()
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

    // --- Разбор ленты событий в пуши (без коммитов — их достаёт compare) ---

    @Test
    fun `разбор ленты - берём только пуши, по дням`() {
        val byDay = GithubImporter.parsePushesByDay(eventsJson(), today.minusDays(7), today)

        assertEquals(2, byDay.size, "два дня с пушами")
        assertEquals(2, byDay[today]!!.size, "два пуша сегодня")
        assertEquals(1, byDay[yesterday]!!.size)
        assertTrue(byDay[yesterday]!!.single().repo.endsWith("skycast"))
    }

    @Test
    fun `пуши вне периода не берутся`() {
        val byDay = GithubImporter.parsePushesByDay(eventsJson(), today, today)
        assertEquals(setOf(today), byDay.keys)
    }

    @Test
    fun `пуш с нулевым before (новая ветка) пропускается — сравнивать не с чем`() {
        val body = """
            [{"type":"PushEvent","created_at":"${today}T10:00:00Z","repo":{"name":"a/b"},
              "payload":{"before":"0000000000000000000000000000000000000000","head":"aaa"}}]
        """.trimIndent()
        assertTrue(GithubImporter.parsePushesByDay(body, today, today).isEmpty())
    }

    // --- Разбор ответа Compare API ---

    @Test
    fun `разбор compare достаёт sha и сообщение, пустые пропускает`() {
        val commits = GithubImporter.parseCommitsFromCompare(compareTodayFirstPush(), "vihrea1337/devlog")
        assertEquals(2, commits.size)
        assertEquals("c1", commits[0].sha)
        assertTrue(commits[0].message.startsWith("Мягкое удаление"))
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

    // --- Полный импорт через HTTP ---

    @Test
    fun `импорт создаёт по одной записи на день, коммиты достаются через compare`() = testApplication {
        application { module() }
        val t = token(client, "gh1@b.co")
        setLogin(client, t, "vihrea1337")

        val result = import(client, t)

        assertEquals(2, result.days)
        assertEquals(3, result.commits, "c1, c2 сегодня (дедуп) + c4 вчера")
        assertEquals(2, result.created)
        assertEquals(0, result.skipped)
        assertFalse(result.truncated)

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
    fun `сбой при загрузке ленты не роняет сервер, а объясняется пользователю`() = testApplication {
        application { module() }
        val t = token(client, "gh5@b.co")
        setLogin(client, t, "vihrea1337")
        GithubImporter.fetchEvents = { _, _ -> error("Пользователь GitHub не найден") }

        val r = client.post("/api/import/github") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"${today.minusDays(3)}","periodEnd":"$today"}""")
        }

        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertTrue(r.bodyAsText().contains("не найден"))
    }

    @Test
    fun `сбой compare на одном пуше не роняет импорт остальных`() = testApplication {
        application { module() }
        val t = token(client, "gh5b@b.co")
        setLogin(client, t, "vihrea1337")
        GithubImporter.fetchCompare = { _, before, head, _ ->
            if (before.startsWith("aaa1")) error("временный сбой сети")
            defaultFetchCompare()("", before, head, null)
        }

        val result = import(client, t)

        // Первый пуш сегодня не сравнился (0 коммитов), второй и вчерашний — как обычно.
        assertEquals(2, result.days, "оба дня всё равно дают записи (у второго пуша сегодня коммит есть)")
        assertFalse(result.truncated, "единичный сбой конкретного пуша — не то же самое, что лимит запросов")
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

    // --- Приватные репозитории: привязанный токен доезжает до fetchEvents и fetchCompare ---

    @Test
    fun `с привязанным токеном токен доезжает и до ленты, и до compare`() = testApplication {
        application { module() }
        val t = token(client, "gh7@b.co")
        setLogin(client, t, "vihrea1337")
        client.post("/api/me/github-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"ghp_privaterepo"}""")
        }

        var seenEventsToken: String? = null
        var seenCompareToken: String? = null
        GithubImporter.fetchEvents = { _, tok -> seenEventsToken = tok; eventsJson() }
        GithubImporter.fetchCompare = { repo, before, head, tok -> seenCompareToken = tok; defaultFetchCompare()(repo, before, head, tok) }

        val result = import(client, t)

        assertEquals("ghp_privaterepo", seenEventsToken)
        assertEquals("ghp_privaterepo", seenCompareToken, "токен нужен и для compare — иначе приватный репозиторий не сравнится")
        assertEquals(2, result.created)
    }

    @Test
    fun `без привязанного токена в fetchEvents и fetchCompare уходит null`() = testApplication {
        application { module() }
        val t = token(client, "gh8@b.co")
        setLogin(client, t, "vihrea1337")

        var seenEventsToken: String? = "не тронуто"
        var seenCompareToken: String? = "не тронуто"
        GithubImporter.fetchEvents = { _, tok -> seenEventsToken = tok; eventsJson() }
        GithubImporter.fetchCompare = { repo, before, head, tok -> seenCompareToken = tok; defaultFetchCompare()(repo, before, head, tok) }

        import(client, t)

        assertNull(seenEventsToken)
        assertNull(seenCompareToken)
    }

    // --- Лимит запросов к GitHub: не долбим API бесконечно ---

    @Test
    fun `без токена число догрузок ограничено — если пушей больше, импорт помечается truncated`() = testApplication {
        application { module() }
        val t = token(client, "gh9@b.co")
        setLogin(client, t, "vihrea1337")

        // MAX_COMPARE_CALLS_ANONYMOUS + 5 пушей за один день — заведомо больше лимита.
        val pushCount = MAX_COMPARE_CALLS_ANONYMOUS + 5
        val events = (1..pushCount).joinToString(",", prefix = "[", postfix = "]") { i ->
            val before = "b" + i.toString().padStart(39, '0')
            val head = "h" + i.toString().padStart(39, '0')
            """{"type":"PushEvent","created_at":"${today}T00:${(i % 59).toString().padStart(2, '0')}:00Z",
                "repo":{"name":"vihrea1337/repo$i"},"payload":{"before":"$before","head":"$head"}}"""
        }
        GithubImporter.fetchEvents = { _, _ -> events }
        var compareCalls = 0
        GithubImporter.fetchCompare = { repo, _, _, _ -> compareCalls++; """{"commits":[{"sha":"s$repo","commit":{"message":"m"}}]}""" }

        val result = import(client, t)

        assertTrue(result.truncated, "пушей больше лимита — должно быть отмечено")
        assertEquals(MAX_COMPARE_CALLS_ANONYMOUS, compareCalls, "не больше лимита запросов на compare")
    }

    @Test
    fun `rate-limit от GitHub на compare останавливает догрузку, но не роняет то, что уже собрано`() = testApplication {
        application { module() }
        val t = token(client, "gh10@b.co")
        setLogin(client, t, "vihrea1337")
        // Пуши обрабатываются по дням по возрастанию (вчера раньше сегодня) — первый
        // ПО ПОРЯДКУ пуш (вчерашний, bbb1) успевает собраться, дальше GitHub "отказывает по лимиту".
        var calls = 0
        GithubImporter.fetchCompare = { _, before, head, tok ->
            calls++
            if (calls == 1) defaultFetchCompare()("", before, head, tok) else throw GithubRateLimitException()
        }

        val result = import(client, t)

        assertTrue(result.truncated)
        assertEquals(1, result.commits, "коммит с первого (по порядку дней) пуша не потерян, хотя догрузку остановили")
    }
}
