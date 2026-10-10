package io.github.vihrea1337.devlog

/**
 * Минимальный конвертер Markdown → HTML для отчётов: публичная страница `/r/{token}`
 * и предпросмотр в интерфейсе рендерятся этим же кодом.
 *
 * Поддерживает заголовки (#, ##, ###), маркированные (- ) и нумерованные (1. ) списки,
 * жирный (**...**), курсив (_..._), `код`, таблицы с палками и абзацы. Не полноценный движок — ровно столько,
 * сколько встречается в наших отчётах (в том числе в том, что возвращает ИИ:
 * он охотно строит таблицу «дата — что сделано», и без её поддержки отчёт для
 * работодателя выглядел бы строками с палками).
 */
object Markdown {
    private val boldRegex = Regex("""\*\*(.+?)\*\*""")
    // Курсив — только если подчёркивания стоят на границе слова: иначе имена вроде
    // sync_seq или updated_at (их полно в технических отчётах) разваливались в курсив.
    private val italicRegex = Regex("""(?<![\p{L}\d])_([^_]+)_(?![\p{L}\d])""")
    private val orderedItem = Regex("""^\d+[.)] """)

    /** Строка-разделитель шапки таблицы: |---|:---:| и подобное. */
    private val tableSeparator = Regex("""^\|?[\s:|-]+\|[\s:|-]*$""")

    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun toHtml(md: String): String {
        val out = StringBuilder()
        val lines = md.lines()
        var inList: String? = null // "ul" или "ol" — какой список сейчас открыт
        var inTable = false

        fun closeList() { inList?.let { out.append("</").append(it).append(">\n") }; inList = null }
        fun openList(tag: String) {
            if (inList != tag) { closeList(); out.append("<").append(tag).append(">\n"); inList = tag }
        }
        fun closeTable() { if (inTable) { out.append("</tbody></table>\n"); inTable = false } }
        fun closeBlocks() { closeList(); closeTable() }

        var i = 0
        while (i < lines.size) {
            val raw = lines[i].trim()
            val line = escapeHtml(raw)
            when {
                line.startsWith("### ") -> { closeBlocks(); out.append("<h3>").append(inline(line.substring(4))).append("</h3>\n") }
                line.startsWith("## ") -> { closeBlocks(); out.append("<h2>").append(inline(line.substring(3))).append("</h2>\n") }
                line.startsWith("# ") -> { closeBlocks(); out.append("<h1>").append(inline(line.substring(2))).append("</h1>\n") }

                // Строка таблицы. Если следующая — разделитель шапки, значит это заголовок.
                line.startsWith("|") -> {
                    closeList()
                    val cells = splitRow(line)
                    val nextIsSeparator = i + 1 < lines.size && tableSeparator.matches(lines[i + 1].trim())
                    if (!inTable && nextIsSeparator) {
                        out.append("<table><thead><tr>")
                        cells.forEach { out.append("<th>").append(inline(it)).append("</th>") }
                        out.append("</tr></thead><tbody>\n")
                        inTable = true
                        i++ // разделитель показывать не нужно
                    } else {
                        if (!inTable) { out.append("<table><tbody>\n"); inTable = true }
                        out.append("<tr>")
                        cells.forEach { out.append("<td>").append(inline(it)).append("</td>") }
                        out.append("</tr>\n")
                    }
                }

                line.startsWith("- ") || line.startsWith("* ") -> {
                    closeTable()
                    openList("ul")
                    out.append("<li>").append(inline(line.substring(2))).append("</li>\n")
                }

                orderedItem.containsMatchIn(line) -> {
                    closeTable()
                    openList("ol")
                    out.append("<li>").append(inline(line.replaceFirst(orderedItem, ""))).append("</li>\n")
                }

                line.isBlank() -> closeBlocks()
                else -> { closeBlocks(); out.append("<p>").append(inline(line)).append("</p>\n") }
            }
            i++
        }
        closeBlocks()
        return out.toString()
    }

    /** «| a | b |» → ["a", "b"]. Крайние палки отбрасываем. */
    private fun splitRow(line: String): List<String> =
        line.trim().trim('|').split("|").map { it.trim() }

    /**
     * Строчная разметка. Сначала режем строку по `обратным кавычкам`: внутри кода никакой
     * разметки нет (там как раз живут подчёркивания и звёздочки), снаружи — жирный и курсив.
     * Текст уже экранирован escapeHtml, поэтому код вставляем как есть.
     */
    private fun inline(s: String): String {
        val parts = s.split('`')
        // Нечётное число кавычек → последняя без пары: её частью кода не считаем.
        val lastClosed = if (parts.size % 2 == 0) parts.size - 2 else parts.size - 1
        return parts.mapIndexed { idx, part ->
            when {
                idx % 2 == 1 && idx <= lastClosed -> "<code>$part</code>"
                idx % 2 == 1 -> "`" + emphasis(part)
                else -> emphasis(part)
            }
        }.joinToString("")
    }

    private fun emphasis(s: String): String =
        italicRegex.replace(boldRegex.replace(s) { "<strong>${it.groupValues[1]}</strong>" }) {
            "<em>${it.groupValues[1]}</em>"
        }
}
