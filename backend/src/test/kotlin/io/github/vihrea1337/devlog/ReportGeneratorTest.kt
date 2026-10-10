package io.github.vihrea1337.devlog

import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Юнит-тесты сборки отчёта (без сети и базы): что код сам считает цифры и хронологию,
 * что аналитическая часть есть и без ИИ, что digest для ИИ влезает в лимит, и разбор
 * ответов Groq (обёртка ```, лишний заголовок, время ожидания после 429).
 */
class ReportGeneratorTest {

    private fun entry(
        day: String,
        raw: String,
        s: StructuredDto? = null,
        min: Int? = null,
        projectId: String? = null,
    ) = EntryDto(
        id = "id-$day-${raw.hashCode()}",
        projectId = projectId,
        occurredOn = day,
        rawText = raw,
        status = if (s != null) "structured" else "queued",
        timeSpentMin = min,
        createdAt = "2026-08-12T10:00:00Z",
        updatedAt = "2026-08-12T10:00:00Z",
        structured = s,
    )

    private val from = LocalDate.parse("2026-08-01")
    private val to = LocalDate.parse("2026-08-31")

    private val flyway = StructuredDto(
        summary = "Перевёл схему БД на Flyway",
        steps = listOf("добавил Flyway", "написал V1"),
        decisions = listOf("Flyway вместо SchemaUtils — нельзя молча пересоздавать схему на проде"),
        problems = listOf("fat-jar терял META-INF/services → Flyway пропускал миграции → перешёл на distTar"),
        outcome = "Миграции применяются на проде",
        tags = listOf("Flyway", "postgres"),
    )
    private val sse = StructuredDto(
        summary = "Живые обновления ленты через SSE",
        outcome = "Лента обновляется без опроса",
        tags = listOf("sse", "flyway"),
    )

    @Test
    fun `с текстом от ИИ — он идёт в середину, цифры и хронологию добавляет код`() {
        val entries = listOf(entry("2026-08-12", "flyway", flyway, 480), entry("2026-08-13", "sse", sse, 90))
        val md = ReportGenerator.buildMarkdown("Отчёт", from, to, entries, aiBody = "## Кратко\nТекст от ИИ.")

        assertTrue(md.startsWith("# Отчёт\n"))
        assertTrue(md.contains("_Период: 1 августа 2026 — 31 августа 2026_"))
        assertTrue(md.contains("## Кратко\nТекст от ИИ."))
        assertTrue(md.contains("| Рабочих дней с записями | 2 |"))
        assertTrue(md.contains("| Учтённое время | 9 ч 30 мин |"))
        // Теги считаются без учёта регистра: Flyway и flyway — одна тема, она первая.
        assertTrue(md.contains("| Основные темы | flyway, postgres, sse |"), md)
        assertTrue(md.contains("- **12 августа 2026** — Перевёл схему БД на Flyway"))
        assertTrue(md.indexOf("## Кратко") < md.indexOf("## Цифры периода"))
        assertTrue(md.indexOf("## Цифры периода") < md.indexOf("## Хронология"))
    }

    @Test
    fun `без ИИ решения и проблемы всё равно попадают в отчёт`() {
        val md = ReportGenerator.buildMarkdown("Отчёт", from, to, listOf(entry("2026-08-12", "flyway", flyway)))

        assertTrue(md.contains("## Главные результаты\n- Миграции применяются на проде"))
        assertTrue(md.contains("## Ключевые решения\n- Flyway вместо SchemaUtils"))
        assertTrue(md.contains("## Сложные проблемы\n- fat-jar терял"))
    }

    @Test
    fun `неразобранная запись видна в хронологии по первой строке сырого текста`() {
        val md = ReportGenerator.buildMarkdown("Отчёт", from, to, listOf(entry("2026-08-12", "починил сборку CI\nподробности")))

        assertTrue(md.contains("- **12 августа 2026** — починил сборку CI"))
        assertFalse(md.contains("подробности"))
    }

    @Test
    fun `в хронологии записи одного дня склеены в одну строку`() {
        val md = ReportGenerator.chronologySection(
            listOf(entry("2026-08-12", "a", flyway), entry("2026-08-12", "живые обновления."), entry("2026-08-13", "b", sse)),
        )
        assertTrue(md.contains("- **12 августа 2026** — Перевёл схему БД на Flyway; живые обновления.\n"), md)
        assertEquals(2, md.lines().count { it.startsWith("- **") })
    }

    @Test
    fun `пустой период`() {
        val md = ReportGenerator.buildMarkdown("Отчёт", from, to, emptyList())
        assertTrue(md.contains("За этот период записей нет."))
        assertFalse(md.contains("## Цифры периода"))
    }

    @Test
    fun `digest даёт ИИ саму заметку автора, проект и время`() {
        // Заметка — первоисточник: ошибки разбора ИИ не должны переезжать в отчёт.
        val project = ProjectDto(id = "p1", name = "DevLog")
        val d = ReportGenerator.digest(listOf(entry("2026-08-12", "flyway вместо SchemaUtils", flyway, 480, "p1")), mapOf("p1" to project))

        assertTrue(d.contains("### 2026-08-12 · проект «DevLog» · 8 ч"))
        assertTrue(d.contains("Заметка: flyway вместо SchemaUtils"))
        assertTrue(d.contains("Суть: "))
    }

    @Test
    fun `digest урезает длинные заметки, чтобы влезть в лимит`() {
        val d = ReportGenerator.digest(List(10) { entry("2026-08-%02d".format(it + 1), "слово ".repeat(400), flyway) })

        assertTrue(d.length <= 10_000, "digest должен влезать в лимит, а он ${d.length}")
        assertEquals(10, Regex("Заметка: ").findAll(d).count(), "заметки урезаны, но не выброшены")
    }

    @Test
    fun `на длинном периоде digest переходит на разбор ИИ, решения и проблемы сохраняет`() {
        val many = StructuredDto(
            summary = "день",
            steps = List(40) { "очень длинный шаг номер $it ".repeat(6) },
            decisions = listOf("ключевое решение"),
            problems = listOf("fat-jar терял миграции"),
        )
        // 40 записей по ~600 символов не влезают даже урезанными — нужен разбор без заметок.
        val d = ReportGenerator.digest(List(40) { entry("2026-08-%02d".format(it % 28 + 1), "заметка ".repeat(80), many) })

        assertTrue(d.length <= 10_000, "digest должен влезать в лимит, а он ${d.length}")
        assertFalse(d.contains("Заметка: "))
        assertTrue(d.contains("- решение: ключевое решение"))
        assertTrue(d.contains("- проблема: fat-jar терял миграции"))
    }

    @Test
    fun `cleanReportBody снимает обёртку и лишний заголовок`() {
        val raw = "```markdown\n# Мой отчёт\n\n## Кратко\nТекст\n```"
        assertEquals("## Кратко\nТекст", AiProcessor.cleanReportBody(raw))
        assertNull(AiProcessor.cleanReportBody("Просто текст без разделов"))
        assertNull(AiProcessor.cleanReportBody(null))
    }

    @Test
    fun `parseRetryAfter понимает заголовок и текст ошибки Groq`() {
        assertEquals(Duration.ofSeconds(7), AiProcessor.parseRetryAfter("7", ""))
        assertEquals(Duration.ofMillis(1_234), AiProcessor.parseRetryAfter(null, "Please try again in 1.234s. Need more tokens?"))
        assertEquals(Duration.ofMillis(123_500), AiProcessor.parseRetryAfter(null, "try again in 2m3.5s"))
        assertEquals(Duration.ofSeconds(10), AiProcessor.parseRetryAfter(null, "непонятно"))
    }

    @Test
    fun `humanDuration`() {
        assertEquals("45 мин", ReportGenerator.humanDuration(45))
        assertEquals("8 ч", ReportGenerator.humanDuration(480))
        assertEquals("1 ч 30 мин", ReportGenerator.humanDuration(90))
    }
}
