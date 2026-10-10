package io.github.vihrea1337.devlog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Мини-конвертер Markdown → HTML: им рендерится и публичная страница отчёта, и предпросмотр.
 * Проверяем то, что реально встречается в отчётах — в том числе таблицы, которые охотно
 * строит ИИ («дата — что сделано»).
 */
class MarkdownTest {

    @Test
    fun `заголовки, списки и выделение`() {
        val html = Markdown.toHtml(
            """
            # Отчёт
            ## 12 августа
            - **Починил** виджет
            - _Итог: выпущена v1.0.1_
            Обычный абзац.
            """.trimIndent(),
        )
        assertTrue(html.contains("<h1>Отчёт</h1>"))
        assertTrue(html.contains("<h2>12 августа</h2>"))
        assertTrue(html.contains("<li><strong>Починил</strong> виджет</li>"))
        assertTrue(html.contains("<em>Итог: выпущена v1.0.1</em>"))
        assertTrue(html.contains("<p>Обычный абзац.</p>"))
    }

    @Test
    fun `таблица превращается в table, а не в строки с палками`() {
        val html = Markdown.toHtml(
            """
            | Дата | Действия |
            |------|----------|
            | 12.08 | Перевёл схему на Flyway |
            | 13.08 | Написал тесты |
            """.trimIndent(),
        )
        assertTrue(html.contains("<th>Дата</th>"), html)
        assertTrue(html.contains("<td>Перевёл схему на Flyway</td>"), html)
        assertEquals(2, Regex("<tr><td>").findAll(html).count(), "две строки данных, шапка отдельно: $html")
        assertTrue(html.contains("</table>"))
        assertFalse(html.contains("<p>|"), "палки не должны утечь в текст: $html")
    }

    @Test
    fun `таблица закрывается, когда текст пошёл дальше`() {
        val html = Markdown.toHtml(
            """
            | a | b |
            |---|---|
            | 1 | 2 |

            ## Итог
            """.trimIndent(),
        )
        assertTrue(html.indexOf("</table>") < html.indexOf("<h2>Итог</h2>"), html)
    }

    @Test
    fun `код в обратных кавычках не ломается на подчёркиваниях`() {
        val html = Markdown.toHtml("- заменил `updated_at` на `sync_seq` из _PostgreSQL_")
        assertTrue(html.contains("<code>updated_at</code>"), html)
        assertTrue(html.contains("<code>sync_seq</code>"), html)
        assertTrue(html.contains("<em>PostgreSQL</em>"), html)
        assertFalse(html.contains("`"), html)
    }

    @Test
    fun `подчёркивания внутри слов — не курсив`() {
        val html = Markdown.toHtml("поле sync_seq и updated_at")
        assertFalse(html.contains("<em>"), html)
    }

    @Test
    fun `код не трогает звёздочки и непарная кавычка остаётся текстом`() {
        assertTrue(Markdown.toHtml("`**не жирный**`").contains("<code>**не жирный**</code>"))
        assertTrue(Markdown.toHtml("цена `5").contains("цена `5"))
    }

    @Test
    fun `нумерованный список`() {
        val html = Markdown.toHtml("1. первый\n2. второй\n\n- маркер")
        assertTrue(html.contains("<ol>\n<li>первый</li>\n<li>второй</li>\n</ol>"), html)
        assertTrue(html.contains("<ul>\n<li>маркер</li>\n</ul>"), html)
    }

    @Test
    fun `html из текста экранируется`() {
        val html = Markdown.toHtml("Правил <script>alert(1)</script> в шаблоне")
        assertFalse(html.contains("<script>"), html)
        assertTrue(html.contains("&lt;script&gt;"))
    }
}
