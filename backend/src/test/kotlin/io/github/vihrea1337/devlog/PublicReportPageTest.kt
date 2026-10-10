package io.github.vihrea1337.devlog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Публичная страница отчёта: разбор на разделы и то, что видит работодатель. */
class PublicReportPageTest {

    private val md = """
        # Отчёт за 4–10 октября 2026

        _Период: 4 октября 2026 — 10 октября 2026_

        ## Кратко
        Фокус недели — **Android-клиент**.

        ## Главные результаты
        - **Офлайн-синхронизация** — записи уезжают сами.

        ## Цифры периода

        | Показатель | Значение |
        |---|---|
        | Записей | 3 |

        ## Хронология

        - **4 октября 2026** — Сделал экран входа
    """.trimIndent()

    private fun entry(day: String, min: Int?) = EntryDto(
        id = day, occurredOn = day, rawText = "x", status = "structured", timeSpentMin = min,
        createdAt = "${day}T10:00:00Z", updatedAt = "${day}T10:00:00Z", projectId = "p1",
    )

    @Test
    fun `разделы режутся по заголовкам, шапка отчёта отбрасывается`() {
        val sections = PublicReportPage.splitSections(md)
        assertEquals(listOf("Кратко", "Главные результаты", "Цифры периода", "Хронология"), sections.map { it.title })
        assertTrue(sections.none { it.body.contains("_Период:") })
    }

    @Test
    fun `страница — цифры плитками, без дубля таблицы, с превью ссылки`() {
        val report = ReportDto(
            id = "r1", title = "Отчёт за 4–10 октября 2026", periodStart = "2026-10-04", periodEnd = "2026-10-10",
            format = "markdown", contentMd = md, createdAt = "2026-10-10T12:00:00Z",
        )
        val ctx = PublicReportPage.Context(
            authorName = "Демо-разработчик", projectName = null,
            entries = listOf(entry("2026-10-04", 120), entry("2026-10-05", 60), entry("2026-10-05", null)),
            projectCount = 1,
        )
        val html = PublicReportPage.render(report, ctx, "https://example.org", autoPrint = false)

        assertTrue(html.contains("""<meta property="og:title" content="Отчёт за 4–10 октября 2026">"""))
        assertTrue(html.contains("https://example.org/og-devlog.png"))
        assertTrue(html.contains("Фокус недели"), "описание ссылки — из раздела «Кратко»")
        assertTrue(html.contains("<b>2</b><span>рабочих дня</span>"))
        assertTrue(html.contains("<b>3,0 ч</b>"))
        assertFalse(html.contains("<h2>Цифры периода</h2>"), "таблицу заменяют плитки")
        assertTrue(html.contains("sec-timeline"))
        assertFalse(html.contains("</strong> — Сделал"), "в хронологии дата над текстом, без тире")
        assertTrue(html.contains("автор: Демо-разработчик"))
    }
}
