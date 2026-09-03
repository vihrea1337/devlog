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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Импорт коммитов с GitLab. В сеть не ходим: подменяем fetchEvents/fetchCompare/fetchProjectPath —
 * так тесты стабильны и не зависят от реального API (которого для ручной проверки не было,
 * см. предупреждение в докстринге GitlabImport.kt — форма фикстур ниже взята из официальной
 * документации GitLab, а не проверена вживую).
 *
 * Форма событий: `action_name` (не `type`, как у GitHub), `push_data.commit_count/commit_from/
 * commit_to/commit_title`, числовой `project_id` вместо готового "owner/repo".
 */
class GitlabImportTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val today: LocalDate = LocalDate.now()
    private val yesterday: LocalDate = today.minusDays(1)

    /**
     * Три пуша: сегодня — один-коммитный (project 1, sha уже в событии, compare не нужен)
     * и двух-коммитный (тот же project 1 — проверка кэша имени проекта); вчера — ещё один
     * один-коммитный, но другой project 2.
     */
    private fun eventsJson(): String = """
        [
          {
            "action_name": "pushed",
            "created_at": "${today}T10:15:00Z",
            "project_id": 1,
            "push_data": {
              "commit_count": 1, "commit_from": "aaa1111111111111111111111111111111111111",
              "commit_to": "aaa2222222222222222222222222222222222222", "commit_title": "Один коммит сегодня"
            }
          },
          {
            "action_name": "closed",
            "created_at": "${today}T10:30:00Z",
            "project_id": 1,
            "target_type": "MergeRequest"
          },
          {
            "action_name": "pushed",
            "created_at": "${today}T12:00:00Z",
            "project_id": 1,
            "push_data": {
              "commit_count": 2, "commit_from": "aaa2222222222222222222222222222222222222",
              "commit_to": "aaa3333333333333333333333333333333333333", "commit_title": "Второй из двух (заголовок последнего)"
            }
          },
          {
            "action_name": "pushed",
            "created_at": "${yesterday}T09:00:00Z",
            "project_id": 2,
            "push_data": {
              "commit_count": 1, "commit_from": "bbb1111111111111111111111111111111111111",
              "commit_to": "bbb2222222222222222222222222222222222222", "commit_title": "Один коммит вчера"
            }
          }
        ]
    """.trimIndent()

    /** Compare для двух-коммитного пуша: один с message (полный), другой только с title (проверка фолбэка). */
    private fun compareTwoCommits(): String = """
        {"commits": [
          {"id": "c1", "message": "Первый коммит\n\nтело коммита"},
          {"id": "c2", "title": "Второй коммит (только title)"}
        ]}
    """.trimIndent()

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_gitlab;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, GithubIntegrations, GitlabIntegrations, Projects, Entries, EntryStructured) }
        GitlabImporter.fetchEvents = { _, _, _ -> eventsJson() }
        GitlabImporter.fetchCompare = { _, from, _, _ ->
            if (from.startsWith("aaa2")) compareTwoCommits() else error("неожиданный compare: $from")
        }
        GitlabImporter.fetchProjectPath = { projectId, _ ->
            when (projectId) {
                1L -> "user/repo-one"
                2L -> "user/repo-two"
                else -> error("неожиданный projectId: $projectId")
            }
        }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(EntryStructured, Entries, Projects, GitlabIntegrations, GithubIntegrations, Users) }
    }

    private suspend fun token(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun bindToken(client: HttpClient, token: String) {
        val r = client.post("/api/me/gitlab-token") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"glpat-test"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    private suspend fun import(client: HttpClient, token: String): ImportResultDto {
        val r = client.post("/api/import/gitlab") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"${today.minusDays(7)}","periodEnd":"$today"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json.decodeFromString(r.bodyAsText())
    }

    // --- Разбор ленты событий в пуши ---

    @Test
    fun `разбор ленты - берём только action_name pushed, по дням`() {
        val byDay = GitlabImporter.parsePushesByDay(eventsJson(), today.minusDays(7), today)

        assertEquals(2, byDay.size, "два дня с пушами")
        assertEquals(2, byDay[today]!!.size, "два пуша сегодня (merge request закрытия не считаются)")
        assertEquals(1, byDay[yesterday]!!.size)
    }

    @Test
    fun `пуши вне периода не берутся`() {
        val byDay = GitlabImporter.parsePushesByDay(eventsJson(), today, today)
        assertEquals(setOf(today), byDay.keys)
    }

    @Test
    fun `одно-коммитный пуш несёт заголовок сразу, compare ему не нужен`() {
        val byDay = GitlabImporter.parsePushesByDay(eventsJson(), today, today)
        val single = byDay[today]!!.first { it.commitCount == 1 }
        assertEquals("Один коммит сегодня", single.singleCommitTitle)

        val multi = byDay[today]!!.first { it.commitCount == 2 }
        assertEquals(null, multi.singleCommitTitle, "у многокоммитного title не используем — он только для последнего")
    }

    @Test
    fun `пуш без commit_from-to (массовый, за лимитом событий) пропускается`() {
        val body = """
            [{"action_name":"pushed","created_at":"${today}T10:00:00Z","project_id":9,
              "push_data":{"commit_count":0,"commit_from":null,"commit_to":null,"commit_title":null}}]
        """.trimIndent()
        assertTrue(GitlabImporter.parsePushesByDay(body, today, today).isEmpty())
    }

    // --- Разбор ответа Compare ---

    @Test
    fun `разбор compare читает id и message, а при отсутствии message - title`() {
        val commits = GitlabImporter.parseCommitsFromCompare(compareTwoCommits(), "user/repo-one")
        assertEquals(2, commits.size)
        assertEquals("c1", commits[0].sha)
        assertTrue(commits[0].message.startsWith("Первый коммит"))
        assertEquals("c2", commits[1].sha)
        assertEquals("Второй коммит (только title)", commits[1].message, "message нет — берём title")
    }

    @Test
    fun `message JSON-null (не отсутствует, а именно null) тоже переходит на title`() {
        // Регрессия: content() на JsonNull даёт строку "null", а не Kotlin null — без
        // contentOrNull это "message" считалось бы заполненным и title бы не подхватился.
        val body = """{"commits": [{"id": "c3", "message": null, "title": "Заголовок вместо null-message"}]}"""
        val commits = GitlabImporter.parseCommitsFromCompare(body, "user/repo-one")
        assertEquals(1, commits.size)
        assertEquals("Заголовок вместо null-message", commits.single().message)
    }

    @Test
    fun `текст записи содержит репозиторий, суть коммита и короткий sha`() {
        val commits = listOf(GitlabCommit("user/repo-one", "Суть\n\nдетали", "abcdef1234567890"))
        val text = GitlabImporter.buildEntryText(today, commits)

        assertTrue(text.contains("user/repo-one"))
        assertTrue(text.contains("Суть"))
        assertTrue(text.contains("abcdef1"))
        assertTrue(!text.contains("детали"))
    }

    // --- Полный импорт через HTTP ---

    @Test
    fun `без привязанного токена импорт отклоняется с объяснением`() = testApplication {
        application { module() }
        val t = token(client, "gl1@b.co")

        val r = client.post("/api/import/gitlab") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"${today.minusDays(3)}","periodEnd":"$today"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("токен"))
    }

    @Test
    fun `импорт создаёт по одной записи на день, имя проекта кэшируется`() = testApplication {
        application { module() }
        val t = token(client, "gl2@b.co")
        bindToken(client, t)

        var pathCalls = 0
        GitlabImporter.fetchProjectPath = { projectId, _ ->
            pathCalls++
            if (projectId == 1L) "user/repo-one" else "user/repo-two"
        }

        val result = import(client, t)

        assertEquals(2, result.days)
        // сегодня: 1 (одиночный) + 2 (compare) = 3; вчера: 1 → итого 4
        assertEquals(4, result.commits)
        assertEquals(2, result.created)
        assertEquals(0, result.skipped)
        assertFalse(result.truncated)
        assertEquals(2, pathCalls, "имя project 1 запрошено один раз, хотя пушей к нему два")

        val feed = json.decodeFromString<List<EntryDto>>(
            client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText(),
        )
        assertEquals(2, feed.size)
        assertTrue(feed.all { it.source == "gitlab" })
        assertTrue(feed.any { it.rawText.contains("Второй коммит") })
    }

    @Test
    fun `повторный импорт не создаёт дублей`() = testApplication {
        application { module() }
        val t = token(client, "gl3@b.co")
        bindToken(client, t)

        import(client, t)
        val second = import(client, t)

        assertEquals(0, second.created)
        assertEquals(2, second.skipped)
    }

    @Test
    fun `сбой при загрузке ленты не роняет сервер, а объясняется пользователю`() = testApplication {
        application { module() }
        val t = token(client, "gl4@b.co")
        bindToken(client, t)
        GitlabImporter.fetchEvents = { _, _, _ -> error("Токен GitLab недействителен или истёк — привяжите новый в настройках") }

        val r = client.post("/api/import/gitlab") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"${today.minusDays(3)}","periodEnd":"$today"}""")
        }

        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertTrue(r.bodyAsText().contains("истёк"))
    }

    @Test
    fun `сбой compare на одном пуше не роняет импорт остальных`() = testApplication {
        application { module() }
        val t = token(client, "gl5@b.co")
        bindToken(client, t)
        GitlabImporter.fetchCompare = { _, from, _, _ ->
            if (from.startsWith("aaa2")) error("временный сбой сети") else error("неожиданный compare")
        }

        val result = import(client, t)

        // Многокоммитный пуш сегодня не сравнился (0 коммитов), но одиночный сегодня
        // и одиночный вчера всё равно дают записи.
        assertEquals(2, result.days)
        assertFalse(result.truncated, "единичный сбой конкретного пуша — не то же самое, что лимит запросов")
    }

    @Test
    fun `rate-limit от GitLab на compare останавливает догрузку, но не роняет то, что уже собрано`() = testApplication {
        application { module() }
        val t = token(client, "gl6@b.co")
        bindToken(client, t)
        // Пуши обрабатываются по дням по возрастанию (вчера раньше сегодня) — одиночный
        // пуш вчера успевает собраться (title уже в событии, никакого compare не нужно),
        // а на compare многокоммитного пуша сегодня GitLab "отказывает по лимиту".
        GitlabImporter.fetchCompare = { _, _, _, _ -> throw GitlabRateLimitException() }

        val result = import(client, t)

        assertTrue(result.truncated)
        assertEquals(2, result.commits, "одиночные коммиты (сегодня и вчера) не потеряны, хотя compare остановлен")
    }

    @Test
    fun `rate-limit на поиске имени проекта тоже останавливает догрузку`() = testApplication {
        application { module() }
        val t = token(client, "gl7@b.co")
        bindToken(client, t)
        GitlabImporter.fetchProjectPath = { _, _ -> throw GitlabRateLimitException() }

        val result = import(client, t)

        assertTrue(result.truncated, "без имени проекта нельзя подписать даже одиночный коммит")
        assertEquals(0, result.commits)
    }

    @Test
    fun `лимит запросов ограничивает число догрузок`() = testApplication {
        application { module() }
        val t = token(client, "gl8@b.co")
        bindToken(client, t)

        // MAX_GITLAB_LOOKUP_CALLS + 5 многокоммитных пушей за один день к разным
        // проектам — каждый требует и project-path, и compare, заведомо больше лимита.
        val pushCount = MAX_GITLAB_LOOKUP_CALLS + 5
        val events = (1..pushCount).joinToString(",", prefix = "[", postfix = "]") { i ->
            """{"action_name":"pushed","created_at":"${today}T00:${(i % 59).toString().padStart(2, '0')}:00Z",
                "project_id":$i,"push_data":{"commit_count":2,"commit_from":"from$i","commit_to":"to$i","commit_title":null}}"""
        }
        GitlabImporter.fetchEvents = { _, _, _ -> events }
        GitlabImporter.fetchProjectPath = { projectId, _ -> "user/repo$projectId" }
        GitlabImporter.fetchCompare = { projectId, _, _, _ -> """{"commits":[{"id":"s$projectId","message":"m"}]}""" }

        val result = import(client, t)

        assertTrue(result.truncated, "пушей больше лимита — должно быть отмечено")
    }
}
