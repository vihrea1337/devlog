package io.github.vihrea1337.devlog

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сборка changelog-файла для экспорта в GitHub (GithubExporter.buildUpdatedContent) —
 * чистая функция, без сети, по образцу GithubImportTest (parsePushesByDay/parseCommitsFromCompare).
 */
class GithubExporterTest {

    private fun section(
        reportId: String = "r1",
        title: String = "Отчёт за август",
        from: LocalDate = LocalDate.of(2026, 8, 1),
        to: LocalDate = LocalDate.of(2026, 8, 31),
        markdown: String = "Сделал важную работу.",
    ) = GithubExporter.ExportSection(reportId, title, from, to, markdown)

    @Test
    fun `новый файл получает заголовок и один раздел`() {
        val result = GithubExporter.buildUpdatedContent(null, section())

        assertTrue(result.startsWith("# Dev Log"))
        assertTrue(result.contains("<!-- devlog:report:r1 -->"))
        assertTrue(result.contains("## 2026-08-01 – 2026-08-31: Отчёт за август"))
        assertTrue(result.contains("Сделал важную работу."))
    }

    @Test
    fun `повторный экспорт того же отчёта обновляет раздел на месте, а не дублирует`() {
        val first = GithubExporter.buildUpdatedContent(null, section(markdown = "первая версия текста"))
        val second = GithubExporter.buildUpdatedContent(first, section(markdown = "исправленная версия текста"))

        assertEquals(1, Regex("<!-- devlog:report:r1 -->").findAll(second).count(), "раздел не должен задваиваться")
        assertTrue(second.contains("исправленная версия текста"))
        assertTrue(!second.contains("первая версия текста"), "старый текст раздела должен быть заменён")
    }

    @Test
    fun `новый отчёт вставляется сверху, старые разделы не трогаются`() {
        val afterFirst = GithubExporter.buildUpdatedContent(
            null,
            section(reportId = "old", title = "Июль", markdown = "работа за июль"),
        )
        val afterSecond = GithubExporter.buildUpdatedContent(
            afterFirst,
            section(reportId = "new", title = "Август", markdown = "работа за август"),
        )

        val newIdx = afterSecond.indexOf("<!-- devlog:report:new -->")
        val oldIdx = afterSecond.indexOf("<!-- devlog:report:old -->")
        assertTrue(newIdx in 0 until oldIdx, "новый раздел должен стоять выше старого")
        assertTrue(afterSecond.contains("работа за июль"), "старый раздел не должен пропасть")
        assertTrue(afterSecond.contains("работа за август"))
    }

    @Test
    fun `обновление одного раздела не трогает соседний`() {
        val afterFirst = GithubExporter.buildUpdatedContent(null, section(reportId = "a", markdown = "текст A"))
        val afterSecond = GithubExporter.buildUpdatedContent(afterFirst, section(reportId = "b", markdown = "текст B"))
        val afterUpdateA = GithubExporter.buildUpdatedContent(afterSecond, section(reportId = "a", markdown = "текст A, версия 2"))

        assertTrue(afterUpdateA.contains("текст A, версия 2"))
        assertTrue(!afterUpdateA.contains("текст A,\n") && !afterUpdateA.endsWith("текст A"), "старый текст A не должен остаться")
        assertTrue(afterUpdateA.contains("текст B"), "раздел B должен остаться нетронутым")
        assertEquals(1, Regex("<!-- devlog:report:a -->").findAll(afterUpdateA).count())
        assertEquals(1, Regex("<!-- devlog:report:b -->").findAll(afterUpdateA).count())
    }

    @Test
    fun `файл существовал, но был пустым — ведёт себя как новый файл, без лишних пустых строк`() {
        val result = GithubExporter.buildUpdatedContent("", section())
        assertTrue(result.startsWith("# Dev Log\n\n<!-- devlog:report:r1 -->"))
    }

    @Test
    fun `ручной текст в файле до первого маркера сохраняется как преамбула`() {
        val manual = "# Мой личный дневник\n\nЭто я написал сам, руками.\n"
        val result = GithubExporter.buildUpdatedContent(manual, section())

        assertTrue(result.startsWith("# Мой личный дневник"))
        assertTrue(result.contains("Это я написал сам, руками."))
        assertTrue(result.contains("<!-- devlog:report:r1 -->"), "новый раздел всё равно должен появиться")
    }
}
