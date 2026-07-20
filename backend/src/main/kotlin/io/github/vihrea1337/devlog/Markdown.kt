package io.github.vihrea1337.devlog

/**
 * Минимальный конвертер Markdown → HTML для публичной страницы отчёта (`/r/{token}`).
 * Поддерживает заголовки (#, ##, ###), маркированные списки (- ), жирный (**...**) и абзацы.
 * Не полноценный движок — ровно столько, сколько нужно нашему формату отчёта.
 */
object Markdown {
    private val boldRegex = Regex("""\*\*(.+?)\*\*""")

    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun toHtml(md: String): String {
        val out = StringBuilder()
        var inList = false
        fun closeList() { if (inList) { out.append("</ul>\n"); inList = false } }
        for (raw in md.lines()) {
            val line = escapeHtml(raw.trim())
            when {
                line.startsWith("### ") -> { closeList(); out.append("<h3>").append(inline(line.substring(4))).append("</h3>\n") }
                line.startsWith("## ") -> { closeList(); out.append("<h2>").append(inline(line.substring(3))).append("</h2>\n") }
                line.startsWith("# ") -> { closeList(); out.append("<h1>").append(inline(line.substring(2))).append("</h1>\n") }
                line.startsWith("- ") -> {
                    if (!inList) { out.append("<ul>\n"); inList = true }
                    out.append("<li>").append(inline(line.substring(2))).append("</li>\n")
                }
                line.isBlank() -> closeList()
                else -> { closeList(); out.append("<p>").append(inline(line)).append("</p>\n") }
            }
        }
        closeList()
        return out.toString()
    }

    private fun inline(s: String): String =
        boldRegex.replace(s) { "<strong>${it.groupValues[1]}</strong>" }
}
