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
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Автоматика отчётов (Automation.kt): расписание «пора / не пора», коммиты с ПК без токена
 * GitHub, отчёты за день/неделю/месяц по расписанию и уведомления для ПК. Внешний ИИ подменён
 * [ReportGenerator.aiWriter], пауза между запросами — [ReportGenerator.aiPause].
 */
class AutomationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val moscow = ZoneId.of("Europe/Moscow")
    private val originalWriter = ReportGenerator.aiWriter
    private val originalPause = ReportGenerator.aiPause
    private val originalFetch = GithubImporter.fetchRepoCommits

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_automation;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(Users, GithubIntegrations, GitlabIntegrations, ImportTokens, Projects, Entries, EntryStructured, Reports, ReportAutomation)
        }
        ReportGenerator.aiPause = {}
    }

    @AfterTest
    fun teardown() {
        ReportGenerator.aiWriter = originalWriter
        ReportGenerator.aiPause = originalPause
        GithubImporter.fetchRepoCommits = originalFetch
        transaction {
            SchemaUtils.drop(ReportAutomation, Reports, EntryStructured, Entries, Projects, ImportTokens, GitlabIntegrations, GithubIntegrations, Users)
        }
    }

    private fun at(text: String): ZonedDateTime = ZonedDateTime.of(java.time.LocalDateTime.parse(text), moscow)

    private val all = Automation.Settings(UUID.randomUUID(), daily = true, weekly = true, monthly = true, githubRepo = "o/r")

    // --- Расписание: чистые функции ---

    @Test
    fun `утро понедельника — коммиты, отчёт за вчера и за прошлую неделю`() {
        val now = at("2026-10-19T05:00") // понедельник
        assertTrue(Automation.commitsDue(all, now))
        val due = Automation.reportsDue(all, now)
        assertEquals(
            listOf(
                Automation.Period(Automation.DAY, LocalDate.parse("2026-10-18"), LocalDate.parse("2026-10-18")),
                Automation.Period(Automation.WEEK, LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18")),
            ),
            due,
        )
    }

    @Test
    fun `до утра ничего, сделанное не повторяется`() {
        assertFalse(Automation.commitsDue(all, at("2026-10-19T03:59")))
        assertTrue(Automation.reportsDue(all, at("2026-10-19T03:59")).isEmpty())

        val done = all.copy(
            lastCommitImportOn = LocalDate.parse("2026-10-19"),
            lastDailyOn = LocalDate.parse("2026-10-18"),
            lastWeeklyOn = LocalDate.parse("2026-10-12"),
        )
        assertFalse(Automation.commitsDue(done, at("2026-10-19T09:00")))
        assertTrue(Automation.reportsDue(done, at("2026-10-19T09:00")).isEmpty())
        // Неделю пропустили в понедельник (сервер лежал) — догоняем во вторник.
        val missed = done.copy(lastWeeklyOn = LocalDate.parse("2026-10-05"))
        assertEquals(Automation.WEEK, Automation.reportsDue(missed, at("2026-10-20T09:00")).last().kind)
    }

    @Test
    fun `месяц — в последний день после полудня, пропущенный догоняется в начале следующего`() {
        assertNull(Automation.monthDue(all, at("2026-10-31T11:59")))
        assertEquals(YearMonth.of(2026, 10), Automation.monthDue(all, at("2026-10-31T12:00")))
        assertNull(Automation.monthDue(all.copy(lastMonthly = "2026-10"), at("2026-10-31T18:00")))
        assertEquals(YearMonth.of(2026, 10), Automation.monthDue(all, at("2026-11-02T09:00")), "догоняем")
        assertNull(Automation.monthDue(all, at("2026-11-04T09:00")), "дальше — уже поздно")
        assertNull(Automation.monthDue(all.copy(monthly = false), at("2026-10-31T12:00")))
        assertNull(Automation.monthDue(all, at("2026-10-30T23:00")))
    }

    @Test
    fun `недели месяца — пн–вс, крайние обрезаны`() {
        val weeks = ReportGenerator.weekRanges(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))
        assertEquals("2026-10-01..2026-10-04", weeks.first().let { "${it.start}..${it.endInclusive}" })
        assertEquals("2026-10-26..2026-10-31", weeks.last().let { "${it.start}..${it.endInclusive}" })
        assertEquals(5, weeks.size)
    }

    @Test
    fun `отчёт за один день — заголовок с датой, без хронологии`() {
        assertEquals("Отчёт за 18 октября 2026", ReportGenerator.defaultTitle(LocalDate.parse("2026-10-18"), LocalDate.parse("2026-10-18")))
        val d = LocalDate.parse("2026-10-18")
        val e = EntryDto(
            id = "x", occurredOn = d.toString(), rawText = dayFile("2026-10-18", "Экран"), source = "day-file",
            status = "structured", createdAt = "", updatedAt = "",
        )
        val md = EmployerReport.buildMarkdown("Отчёт за 18 октября 2026", d, d, listOf(e), author = "Анна")
        assertTrue(md.contains("**Исполнитель:** Анна"))
        assertFalse(md.contains("## Хронология"), md)
        assertTrue(md.contains("## Цифры периода"))
    }

    @Test
    fun `«Кратко» недели находится под заголовком любого уровня`() {
        assertEquals("Сделал экран. Проверил.", EmployerReport.briefOf("## Кратко\nСделал экран.\nПроверил.\n\n## Сделано\n- x"))
        assertEquals("Коротко", EmployerReport.briefOf("### Кратко\nКоротко"))
        assertNull(EmployerReport.briefOf("## Сделано\n- x"))
    }

    @Test
    fun `пункты ИИ без маркера списка становятся пунктами, «Кратко» не трогается`() {
        val md = "## Кратко\n**Жирное** вступление.\n\n## Сделано\n**Уведомления** – готовы.\n- **Виджет** — готов."
        assertEquals(
            "## Кратко\n**Жирное** вступление.\n\n## Сделано\n- **Уведомления** – готовы.\n- **Виджет** — готов.",
            EmployerReport.polishAi(md),
        )
    }

    @Test
    fun `раздел «Передано другим», которого нет в записях, ИИ не может выдумать`() {
        val ai = "## Сделано\n- Экран\n\n## Передано другим и нехватки\n- Перевод — ждёт контент-команды\n\n## Не сделано и почему\n- Перевод — ждёт текстов"
        val digest = "### 2026-10-16\nСделано:\n- Экран\nНе делалось и почему:\n- Перевод — ждёт текстов\n"
        val md = EmployerReport.polishAi(ai, digest)
        assertFalse(md.contains("Передано другим") || md.contains("контент-команды"), md)
        assertTrue(md.contains("## Не сделано и почему\n- Перевод — ждёт текстов"), "этот раздел в записях есть")
    }

    // --- Через HTTP ---

    private fun dayFile(date: String, done: String) = """
        # $date — Приложение
        ## Сделано
        - $done
        ## Проверено
        - Блочные тесты зелёные.
        ## Цифры
        - Коммитов за день: 2
    """.trimIndent()

    private suspend fun register(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"Анна"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun project(client: HttpClient, t: String, name: String): String = json.decodeFromString<ProjectDto>(
        client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name"}""")
        }.bodyAsText(),
    ).id

    private suspend fun importToken(client: HttpClient, jwt: String): String = json.decodeFromString<CreatedImportTokenDto>(
        client.post("/api/me/import-tokens") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ПК"}""")
        }.bodyAsText(),
    ).token

    private suspend fun importDays(client: HttpClient, bearer: String, vararg days: Pair<String, String>) {
        val r = client.post("/api/import/day-files") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
            contentType(ContentType.Application.Json)
            val files = days.map { (date, done) -> DayFileDto("$date.md", dayFile(date, done)) }
            setBody(json.encodeToString(DayFilesImportRequest.serializer(), DayFilesImportRequest(files, projectName = "weather")))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    private suspend fun putAutomation(client: HttpClient, jwt: String, body: UpdateAutomation): io.ktor.client.statement.HttpResponse =
        client.put("/api/automation") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UpdateAutomation.serializer(), body))
        }

    private suspend fun reports(client: HttpClient, jwt: String): List<ReportDto> =
        json.decodeFromString(client.get("/api/reports") { header(HttpHeaders.Authorization, "Bearer $jwt") }.bodyAsText())

    @Test
    fun `коммиты с ПК без токена GitHub — те же записи, что у импорта GitHub, без дублей`() = testApplication {
        application { module() }
        val jwt = register(client, "auto1@b.co")
        val p = project(client, jwt, "Weather")
        val script = importToken(client, jwt)
        assertEquals(HttpStatusCode.OK, putAutomation(client, jwt, UpdateAutomation(timeZone = "Europe/Moscow")).status)

        val body = CommitsImportRequest(
            repo = "acme/weather",
            projectName = "WEATHER", // имя без учёта регистра
            commits = listOf(
                CommitDto("aaaaaaa1", "2026-10-19", "feat(android)\n\nПуш-уведомления\n\nCo-Authored-By: X <x@y>"),
                CommitDto("bbbbbbb2", "2026-10-19", "fix(android)\n\nКапча"),
            ),
        )
        suspend fun push() = client.post("/api/import/commits") {
            header(HttpHeaders.Authorization, "Bearer $script")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CommitsImportRequest.serializer(), body))
        }
        val first = push()
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(1, json.decodeFromString<ImportResultDto>(first.bodyAsText()).created)
        assertEquals(1, json.decodeFromString<ImportResultDto>(push().bodyAsText()).skipped, "повтор ничего не меняет")

        val entry = json.decodeFromString<List<EntryDto>>(client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $jwt") }.bodyAsText()).single()
        assertTrue(entry.rawText.contains("Пуш-уведомления") && !entry.rawText.contains("Co-Authored-By"), entry.rawText)
        assertEquals(2, EmployerReport.commitCount(entry))

        // Тот же день через GitHub (classic-токен): 22:30 UTC 18-го — это 01:30 19-го по Москве.
        client.put("/api/me") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody("""{"githubLogin":"vihrea1337"}""")
        }
        GithubImporter.fetchRepoCommits = { _, _, _, _, _, _ ->
            """[
              {"sha": "bbbbbbb2", "parents": [{"sha": "aaaaaaa1"}], "commit": {"message": "fix(android)\n\nКапча", "author": {"date": "2026-10-18T22:40:00Z"}}},
              {"sha": "aaaaaaa1", "parents": [{"sha": "0"}], "commit": {"message": "feat(android)\n\nПуш-уведомления\n\nCo-Authored-By: X <x@y>", "author": {"date": "2026-10-18T22:30:00Z"}}}
            ]"""
        }
        val viaGithub = client.post("/api/import/github") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"2026-10-15","periodEnd":"2026-10-19","repo":"acme/weather","projectId":"$p"}""")
        }
        val r = json.decodeFromString<ImportResultDto>(viaGithub.bodyAsText())
        assertEquals(0, r.created, "день — по Москве, запись та же: ${viaGithub.bodyAsText()}")
        assertEquals(1, r.skipped, viaGithub.bodyAsText())
    }

    @Test
    fun `кривые коммиты и чужой проект отклоняются`() = testApplication {
        application { module() }
        val jwt = register(client, "auto2@b.co")
        suspend fun push(body: CommitsImportRequest) = client.post("/api/import/commits") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CommitsImportRequest.serializer(), body))
        }
        assertEquals(HttpStatusCode.BadRequest, push(CommitsImportRequest("o/r", listOf(CommitDto("zzz", "2026-10-19", "x")))).status)
        assertEquals(HttpStatusCode.BadRequest, push(CommitsImportRequest("o/r", listOf(CommitDto("abcdef1", "19.10.2026", "x")))).status)
        assertEquals(HttpStatusCode.BadRequest, push(CommitsImportRequest("o/r", emptyList(), projectName = "Нет такого")).status)
        assertEquals(HttpStatusCode.BadRequest, push(CommitsImportRequest("не репо", emptyList())).status)
    }

    @Test
    fun `расписание собирает отчёты за день, неделю и месяц, ПК узнаёт о них`() = testApplication {
        application { module() }
        val jwt = register(client, "auto3@b.co")
        val p = project(client, jwt, "Weather")
        val script = importToken(client, jwt)
        importDays(client, script, "2026-10-14" to "Экран прогноза", "2026-10-18" to "Пуши", "2026-10-22" to "Капча")
        val saved = putAutomation(client, jwt, UpdateAutomation(projectId = p, dailyReport = true, weeklyReport = true, monthlyReport = true))
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())

        val digests = mutableListOf<String>()
        ReportGenerator.aiWriter = { _, digest, employer ->
            assertTrue(employer)
            digests += digest
            "## Кратко\nНеделя ${digests.size}: работа над приложением.\n\n## Сделано\n- **Экран** — сделан."
        }

        // Понедельник, 05:00 по Москве: отчёт за воскресенье и за прошлую неделю.
        Automation.runOnce(Instant.parse("2026-10-19T02:00:00Z"))
        val afterMonday = reports(client, jwt)
        assertEquals(setOf("day", "week"), afterMonday.map { it.autoKind }.toSet())
        val day = afterMonday.single { it.autoKind == "day" }
        assertEquals("Weather: отчёт за 18 октября 2026", day.title)
        assertTrue(day.contentMd.contains("**Исполнитель:** Анна · **Проект:** Weather"), day.contentMd)
        assertEquals("2026-10-12", afterMonday.single { it.autoKind == "week" }.periodStart)

        // Повтор того же утра ничего не добавляет.
        Automation.runOnce(Instant.parse("2026-10-19T03:00:00Z"))
        assertEquals(2, reports(client, jwt).size)

        // Скрипт на ПК видит оба отчёта — только названия и ссылки.
        val notices = json.decodeFromString<List<ImportNoticeDto>>(
            client.get("/api/import/notices") { header(HttpHeaders.Authorization, "Bearer $script") }.bodyAsText(),
        )
        assertEquals(setOf("day", "week"), notices.map { it.kind }.toSet())
        assertTrue(notices.all { it.url == "/?report=${it.reportId}" })
        // А читать сами отчёты токену скрипта по-прежнему нельзя.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/reports") { header(HttpHeaders.Authorization, "Bearer $script") }.status)

        // Последний день месяца, 12:30: месяц по неделям. Неделя 12–18 уже готова — её не пишем
        // заново; 1–4 и 5–11 без записей; пишутся 19–25 и итог месяца.
        digests.clear()
        Automation.runOnce(Instant.parse("2026-10-31T09:30:00Z"))
        val month = reports(client, jwt).single { it.autoKind == "month" }
        assertEquals("Weather: отчёт за октябрь 2026", month.title)
        assertEquals(2, digests.size, "неделя 19–25 + месяц: ${digests.map { it.take(80) }}")
        assertTrue(digests[0].contains("Капча") && !digests[0].contains("Экран прогноза"), "неделя — только её дни")
        assertTrue(digests[1].contains("### Неделя 12–18 октября 2026") && digests[1].contains("### Неделя 19–25 октября 2026"), digests[1])
        assertTrue(month.contentMd.contains("## По неделям"), month.contentMd)
        assertTrue(month.contentMd.contains("## Хронология") && month.contentMd.contains(EmployerReport.FOOTER))
        val status = json.decodeFromString<AutomationDto>(client.get("/api/automation") { header(HttpHeaders.Authorization, "Bearer $jwt") }.bodyAsText())
        assertEquals("2026-10", status.lastMonthly)
        assertTrue(status.lastReportResult!!.contains("за месяц"), status.lastReportResult)
    }

    @Test
    fun `без токена GitHub серверный импорт честно говорит, что делать`() = testApplication {
        application { module() }
        val jwt = register(client, "auto4@b.co")
        client.put("/api/me") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody("""{"githubLogin":"vihrea1337"}""")
        }
        putAutomation(client, jwt, UpdateAutomation(githubRepo = "https://github.com/acme/weather"))
        val r = client.post("/api/automation/run") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            contentType(ContentType.Application.Json)
            setBody("""{"action":"commits"}""")
        }
        val msg = json.decodeFromString<AutomationRunResult>(r.bodyAsText()).message
        assertTrue(msg.contains("classic") && msg.contains("--git"), msg)
        val status = json.decodeFromString<AutomationDto>(client.get("/api/automation") { header(HttpHeaders.Authorization, "Bearer $jwt") }.bodyAsText())
        assertEquals("acme/weather", status.githubRepo)
        assertFalse(status.githubTokenLinked)
        assertEquals(HttpStatusCode.BadRequest, putAutomation(client, jwt, UpdateAutomation(timeZone = "Луна/Море")).status)
    }
}
