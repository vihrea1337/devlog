package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
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
 * Шаблон «Отчёт работодателю» (EmployerReport.kt), сложение «Цифр» и сборка месяца из
 * недельных отчётов (ReportGenerator). Внешний ИИ подменён [ReportGenerator.aiWriter] —
 * тест видит, что именно ушло бы в Groq, и проверяет, что секретное туда не уходит.
 */
class EmployerReportTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val originalWriter = ReportGenerator.aiWriter

    private fun dayFile(date: String, done: String, tests: String, commits: Int, notDone: String = "Уведомления — ждут ключа") = """
        # $date — Погодное приложение
        ## Сделано
        - $done
        ## Проверено
        - Блочные тесты зелёные.
        ## Нехватки API и макета
        - Почасовой прогноз — передано Ане (№12).
        ## Не делалось и почему
        - $notDone
        ## Цифры
        - Тестов к концу дня: $tests
        - Коммитов за день: $commits.
    """.trimIndent()

    private fun entry(date: String, text: String, source: String = "day-file", projectId: String? = null) = EntryDto(
        id = "$source-$date",
        projectId = projectId,
        occurredOn = date,
        rawText = text,
        source = source,
        status = "structured",
        createdAt = "",
        updatedAt = "",
        structured = if (source == "day-file") DayFileImporter.toStructured(DayFileImporter.parse(text)) else null,
    )

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_employer;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, GithubIntegrations, GitlabIntegrations, Projects, Entries, EntryStructured, Reports) }
    }

    @AfterTest
    fun teardown() {
        ReportGenerator.aiWriter = originalWriter
        transaction { SchemaUtils.drop(Reports, EntryStructured, Entries, Projects, GitlabIntegrations, GithubIntegrations, Users) }
    }

    // --- Цифры ---

    @Test
    fun `строки «Цифр» понимаются, номера задач и даты сборок за количество не считаются`() {
        val tests = EmployerReport.parseMetric("Тестов к концу дня: 262 — утром было 187; +6 на переписку")!!
        assertEquals("тесты", tests.key)
        assertEquals(262, tests.value)
        assertTrue(tests.level)

        val chain = EmployerReport.parseMetric("Блочные тесты: 144 → 149 → 153 → 157.")!!
        assertEquals("тесты", chain.key, "«Блочные тесты» и «Тестов к концу дня» — один показатель")
        assertEquals(144, chain.start)
        assertEquals(157, chain.value)

        assertEquals(1, EmployerReport.parseMetric("Сборок для тестеров: 1 (26.10.19).")!!.value)
        assertEquals(15, EmployerReport.parseMetric("Замечаний закрыто: 15 (п.1–5, 7, 9); п.8 снят")!!.value)
        assertNull(EmployerReport.parseMetric("Передано Ане: нехватка №65 (флаги стран)."), "№65 — номер, не количество")
        assertNull(EmployerReport.parseMetric("Данные стенда: 1 вопрос помощнику, 1 напоминание"), "два числа — непонятно, что складывать")
        assertNull(EmployerReport.parseMetric("**Ошибка исправлена**: лента писала «Найдено: 0»"), "жирный пункт — рассказ")
        assertNull(EmployerReport.parseMetric("Итог: без сети лента писала 0"), "число далеко от двоеточия — не показатель")
        assertFalse(EmployerReport.parseMetric("Сборок для тестеров: 2")!!.key == "тесты", "«тестеров» — не тесты")
    }

    @Test
    fun `за день — сумма, к концу дня — было → стало, части дня сливаются`() {
        val rows = EmployerReport.summarize(
            listOf(
                LocalDate.parse("2026-10-14") to listOf("Блочные тесты: 84 → 144", "Коммитов за день: 3."),
                LocalDate.parse("2026-10-18") to listOf("Коммитов забрано: 6", "Коммитов забрано вечером: 1"),
                LocalDate.parse("2026-10-19") to listOf("Тестов к концу дня: 262", "Коммитов за день: 7."),
            ),
        ).associate { it.label to it.value }

        assertEquals("84 → 262", rows["Тестов в проекте"])
        assertEquals("10", rows["Коммитов"])
        assertEquals("7", rows["Коммитов забрано"], "«вечером» — та же строка, что и днём")
    }

    // --- Сборка без ИИ ---

    @Test
    fun `без ИИ — все разделы кодом, коммиты дня с файлом не дублируются`() {
        val entries = listOf(
            entry("2026-10-14", dayFile("2026-10-14", "Экран прогноза", "84 → 100", 3)),
            entry("2026-10-14", "Коммиты за 2026-10-14 в a/b (импорт из GitHub, 2 шт.):\n- feat (aaaaaaa)\n  экран\n- fix (bbbbbbb)", source = "github"),
            entry("2026-10-15", "Коммиты за 2026-10-15 в a/b (импорт из GitHub, 1 шт.):\n- Карта осадков (ccccccc)", source = "github"),
            entry("2026-10-19", dayFile("2026-10-19", "Виджет", "120", 5, notDone = "Перевод на тайский — после согласования")),
        )

        val md = EmployerReport.buildMarkdown("Отчёт", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), entries)

        assertTrue(md.contains("## Сделано"))
        assertTrue(md.contains("- Экран прогноза") && md.contains("- Виджет"))
        assertTrue(md.contains("- Карта осадков"), "день без файла дня — из коммитов")
        assertFalse(md.contains("- feat"), "коммиты дня с файлом в «Сделано» не повторяются")
        assertTrue(md.contains("## Передано другим и нехватки") && md.contains("№12"))
        assertTrue(md.contains("Перевод на тайский") && !md.contains("Уведомления — ждут ключа"),
            "«Не сделано» — по последней записи: раннее уже неактуально")
        assertTrue(md.contains("| Коммитов (по GitHub) | 3 |"), md)
        assertTrue(md.contains("| Тестов в проекте | 84 → 120 |"), md)
        assertTrue(md.contains("| Коммитов | 8 |"), md)
        assertTrue(md.contains("**14 октября 2026** — Экран прогноза · 2 коммита"), md)
        assertTrue(md.contains("**15 октября 2026** — Карта осадков · 1 коммит"), md)
    }

    @Test
    fun `в дайджест для ИИ идут разделы, а коммиты дня с файлом — нет`() {
        val entries = listOf(
            entry("2026-10-14", dayFile("2026-10-14", "Экран прогноза", "100", 3)),
            entry("2026-10-14", "Коммиты за 2026-10-14:\n- секретный-коммит (aaaaaaa)", source = "github"),
        )
        val d = EmployerReport.digest(entries)
        assertTrue(d.contains("Сделано:\n- Экран прогноза"))
        assertTrue(d.contains("Не делалось и почему:"))
        assertFalse(d.contains("секретный-коммит"))
        assertFalse(d.contains("Коммитов за день"), "цифры считает код — модели они не нужны")
    }

    @Test
    fun `большой период ужимается до лимита дайджеста`() {
        val long = "Очень длинный пункт про работу. ".repeat(40)
        val entries = (1..28).map { d ->
            val date = LocalDate.parse("2026-10-01").plusDays(d.toLong() - 1).toString()
            entry(date, dayFile(date, long, "$d", 1))
        }
        assertTrue(EmployerReport.digest(entries).length <= ReportGenerator.DIGEST_MAX_CHARS)
    }

    @Test
    fun `склонение коммитов`() {
        assertEquals(listOf("коммит", "коммита", "коммитов", "коммитов", "коммит"), listOf(1, 3, 5, 11, 21).map(EmployerReport::commitsWord))
    }

    // --- Недельные отчёты ---

    private fun report(id: String, from: String, to: String, created: String, body: String, projectId: String? = null) = ReportDto(
        id = id, projectId = projectId, title = "t", periodStart = from, periodEnd = to, format = "markdown",
        contentMd = "# Отчёт\n\n_Период: x_\n\n$body\n\n## Цифры периода\n\n| a | b |\n\n## Хронология\n\n- день",
        createdAt = created,
    )

    @Test
    fun `выбираются недели внутри месяца, из пересобранной — самая свежая`() {
        val reports = listOf(
            report("old", "2026-10-06", "2026-10-12", "2026-10-12T10:00:00Z", "## Сделано\n- старая версия"),
            report("new", "2026-10-06", "2026-10-12", "2026-10-13T10:00:00Z", "## Сделано\n- вычитанная версия"),
            report("w3", "2026-10-13", "2026-10-19", "2026-10-19T10:00:00Z", "## Сделано\n- третья неделя"),
            report("month", "2026-10-01", "2026-10-31", "2026-10-31T10:00:00Z", "## Сделано\n- прошлый месяц"),
            report("other", "2026-10-20", "2026-10-26", "2026-10-26T10:00:00Z", "## Сделано\n- другой проект", projectId = "p2"),
        )
        val picked = ReportGenerator.pickWeekly(reports, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), null)

        assertEquals(listOf("2026-10-06", "2026-10-13"), picked.map { it.from.toString() })
        assertEquals("## Сделано\n- вычитанная версия", picked.first().body, "шапка, цифры и хронология сняты")
    }

    @Test
    fun `дайджест из недель влезает в лимит`() {
        val weeks = (0..4).map { i ->
            ReportGenerator.WeeklySource(LocalDate.parse("2026-10-01").plusDays(i * 7L), LocalDate.parse("2026-10-07").plusDays(i * 7L), "x".repeat(5000))
        }
        val d = ReportGenerator.weeklyDigest(weeks, "дни без отчёта")
        assertTrue(d.length <= ReportGenerator.DIGEST_MAX_CHARS)
        assertTrue(d.contains("### Неделя 1–7 октября 2026") && d.contains("### Дни без недельного отчёта"))
    }

    // --- Через HTTP: что уходит в ИИ ---

    private suspend fun token(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun project(client: HttpClient, t: String, ai: Boolean): String = json.decodeFromString<ProjectDto>(
        client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Работа","aiEnabled":$ai}""")
        }.bodyAsText(),
    ).id

    private suspend fun importDay(client: HttpClient, t: String, date: String, text: String, projectId: String?) {
        val r = client.post("/api/import/day-files") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DayFilesImportRequest.serializer(), DayFilesImportRequest(listOf(DayFileDto("$date.md", text)), projectId)))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    private suspend fun makeReport(client: HttpClient, t: String, body: NewReport): ReportDto {
        val r = client.post("/api/reports") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(NewReport.serializer(), body))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json.decodeFromString(r.bodyAsText())
    }

    @Test
    fun `шаблон работодателя — ИИ пишет разделы, код — цифры, секретный проект в ИИ не уходит`() = testApplication {
        application { module() }
        val sent = mutableListOf<Pair<String, Boolean>>()
        ReportGenerator.aiWriter = { _, digest, employer ->
            sent += digest to employer
            "## Кратко\nРаботал над приложением.\n\n## Сделано\n- **Экран** — сделан."
        }
        val t = token(client, "emp1@b.co")
        val open = project(client, t, ai = true)
        val secret = project(client, t, ai = false)
        importDay(client, t, "2026-10-14", dayFile("2026-10-14", "Открытая работа", "10", 2), open)
        importDay(client, t, "2026-10-15", dayFile("2026-10-15", "Секретная работа", "12", 3), secret)

        val r = makeReport(client, t, NewReport("2026-10-01", "2026-10-31", template = "employer"))

        assertEquals(1, sent.size)
        assertTrue(sent.single().second, "ИИ получил шаблон работодателя")
        assertTrue(sent.single().first.contains("Открытая работа"))
        assertFalse(sent.single().first.contains("Секретная работа"), "проект с выключенным ИИ — не в Groq")
        assertTrue(r.contentMd.contains("## Кратко\nРаботал над приложением."))
        assertTrue(r.contentMd.contains("| Коммитов | 5 |"), "цифры — по всем записям, их считает код: ${r.contentMd}")
        assertTrue(r.contentMd.contains("## Хронология"))
    }

    @Test
    fun `месяц из недельных отчётов — в ИИ уходят вычитанные недели и непокрытые дни`() = testApplication {
        application { module() }
        val sent = mutableListOf<String>()
        ReportGenerator.aiWriter = { _, digest, _ -> sent += digest; null } // ИИ «недоступен» — проверим и запасной путь
        val t = token(client, "emp2@b.co")
        val p = project(client, t, ai = true)
        importDay(client, t, "2026-10-14", dayFile("2026-10-14", "Работа второй недели", "10", 2), p)
        importDay(client, t, "2026-10-22", dayFile("2026-10-22", "Работа без недельного отчёта", "12", 1), p)

        val week = makeReport(client, t, NewReport("2026-10-13", "2026-10-19", projectId = p, template = "employer"))
        val edited = week.contentMd.replace(Regex("## Сделано[\\s\\S]*?(?=\\n## )"), "## Сделано\n- **Вычитанный пункт** — поправлен руками.\n")
        client.put("/api/reports/${week.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UpdateReport.serializer(), UpdateReport(edited)))
        }
        sent.clear()

        val month = makeReport(client, t, NewReport("2026-10-01", "2026-10-31", projectId = p, template = "employer", fromWeeklyReports = true))

        val digest = sent.single()
        assertTrue(digest.contains("### Неделя 13–19 октября 2026") && digest.contains("Вычитанный пункт"), digest)
        assertTrue(digest.contains("Работа без недельного отчёта"), "непокрытый день — из записей")
        assertFalse(digest.contains("Работа второй недели"), "покрытый неделей день не дублируется")
        // ИИ не ответил — недели подряд, затем непокрытые дни; цифры — за весь месяц.
        assertTrue(month.contentMd.contains("## Неделя 13–19 октября 2026") && month.contentMd.contains("### Сделано"))
        assertTrue(month.contentMd.contains("## Дни без недельного отчёта"))
        assertTrue(month.contentMd.contains("| Коммитов | 3 |"), month.contentMd)
    }

    @Test
    fun `недельные отчёты проекта без ИИ во внешний ИИ не отправляются`() = testApplication {
        application { module() }
        var calls = 0
        ReportGenerator.aiWriter = { _, _, _ -> calls++; "## Сделано\n- x" }
        val t = token(client, "emp3@b.co")
        val secret = project(client, t, ai = false)
        importDay(client, t, "2026-10-14", dayFile("2026-10-14", "Секретная работа", "10", 2), secret)
        makeReport(client, t, NewReport("2026-10-13", "2026-10-19", projectId = secret, template = "employer"))

        val month = makeReport(client, t, NewReport("2026-10-01", "2026-10-31", template = "employer", fromWeeklyReports = true, projectId = secret))

        assertEquals(0, calls, "ни недельный, ни месячный отчёт секретного проекта в Groq не ходили")
        assertTrue(month.contentMd.contains("Секретная работа"), "отчёт собран кодом из недели")
    }
}
