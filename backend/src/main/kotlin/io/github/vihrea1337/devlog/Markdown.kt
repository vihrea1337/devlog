package io.github.vihrea1337.devlog

/**
 * Минимальный конвертер Markdown → HTML для отчётов: публичная страница `/r/{token}`
 * и предпросмотр в интерфейсе рендерятся этим же кодом.
 *
 * Поддерживает заголовки (#, ##, ###), маркированные списки (- ), жирный (**...**),
 * курсив (_..._) , таблицы с палками и абзацы. Не полноценный движок — ровно столько,
 * сколько встречается в наших отчётах (в том числе в том, что возвращает ИИ:
 * он охотно строит таблицу «дата — что сделано», и без её поддержки отчёт для
 * работодателя выглядел бы строками с палками).
 */
object Markdown {
    private val boldRegex = Regex("""\*\*(.+?)\*\*""")
    private val italicRegex = Regex("""_([^_]+)_""")

    /** Строка-разделитель шапки таблицы: |---|:---:| и подобное. */
    private val tableSeparator = Regex("""^\|?[\s:|-]+\|[\s:|-]*$""")

    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun toHtml(md: String): String {
        val out = StringBuilder()
        val lines = md.lines()
        var inList = false
        var inTable = false

        fun closeList() { if (inList) { out.append("</ul>\n"); inList = false } }
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

                line.startsWith("- ") -> {
                    closeTable()
                    if (!inList) { out.append("<ul>\n"); inList = true }
                    out.append("<li>").append(inline(line.substring(2))).append("</li>\n")
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

    private fun inline(s: String): String =
        italicRegex.replace(boldRegex.replace(s) { "<strong>${it.groupValues[1]}</strong>" }) {
            "<em>${it.groupValues[1]}</em>"
        }
}
