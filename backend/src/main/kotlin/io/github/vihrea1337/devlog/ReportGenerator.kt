package io.github.vihrea1337.devlog

import java.time.LocalDate
import java.util.UUID

/**
 * Сборка отчёта за период. Работает В ЛЮБОМ случае: сначала детерминированно строим
 * черновик Markdown из записей (это всегда, без ключа и без сети). Если ИИ включён —
 * просим его причесать черновик в связный отчёт; не вышло — отдаём черновик как есть.
 */
object ReportGenerator {

    suspend fun generate(userId: UUID, title: String, from: LocalDate, to: LocalDate, projectId: UUID?): String {
        val entries = EntryRepository.list(userId, from, to, projectId).sortedBy { it.occurredOn }
        val baseline = buildMarkdown(title, from, to, entries)
        if (entries.isEmpty()) return baseline
        return AiProcessor.polishReport(userId, baseline) ?: baseline
    }

    /** Детерминированный черновик: заголовок, период, по дням — записи со шагами. */
    fun buildMarkdown(title: String, from: LocalDate, to: LocalDate, entries: List<EntryDto>): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        sb.append("_Период: ").append(from).append(" — ").append(to)
            .append(". Записей: ").append(entries.size).append("._\n")
        if (entries.isEmpty()) {
            sb.append("\nЗа этот период записей нет.\n")
            return sb.toString()
        }
        var lastDay: String? = null
        for (e in entries) {
            if (e.occurredOn != lastDay) {
                sb.append("\n## ").append(e.occurredOn).append("\n")
                lastDay = e.occurredOn
            }
            val s = e.structured
            val head = if (s != null && s.summary.isNotBlank()) s.summary else e.rawText.lineSequence().firstOrNull().orEmpty()
            sb.append("- **").append(head).append("**\n")
            if (s != null) {
                for (step in s.steps) sb.append("  - ").append(step).append("\n")
                if (s.outcome.isNotBlank()) sb.append("  - _Итог: ").append(s.outcome).append("_\n")
            }
        }
        return sb.toString()
    }
}
