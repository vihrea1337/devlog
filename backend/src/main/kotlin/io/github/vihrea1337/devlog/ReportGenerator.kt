package io.github.vihrea1337.devlog

import java.time.LocalDate
import java.util.UUID

/**
 * Сборка отчёта за период.
 *
 * Отчёт состоит из двух частей разного происхождения:
 *
 * 1. **Аналитическая часть** (Кратко / Главные результаты / Ключевые решения / Сложные
 *    проблемы / Что дальше) — её пишет ИИ по ТЕМАМ, а не по дням. На вход он получает
 *    [digest] — полную структуру записей (суть, шаги, решения, проблемы, итог), а не урезанный
 *    список шагов. Если ИИ недоступен (нет ключа, лимит, ошибка) — эту часть собирает код
 *    из той же структуры ([fallbackBody]): без связного текста, но с теми же разделами.
 *
 * 2. **Факты** — шапка, таблица «Цифры периода» и «Хронология» по дням. Их ВСЕГДА строит код:
 *    числа и даты должны быть точными, а модели в них путаются и иногда придумывают.
 *
 * Конфиденциальность: записи проектов с выключенным ИИ в [digest] НЕ попадают — их текст
 * не уходит в Groq. В отчёте они видны только в хронологии и в цифрах (её строит код).
 */
object ReportGenerator {

    /** Сколько символов сырого текста брать для записи, которую ИИ ещё не разобрал. */
    private const val RAW_FALLBACK_CHARS = 700

    /**
     * Верхняя граница размера digest. Бесплатный Groq принимает 8000 токенов в минуту на
     * запрос+ответ; русский текст — примерно 3 символа на токен, так что 10 000 символов ≈
     * 3300 токенов, плюс промпт и 3500 на ответ — укладываемся.
     */
    private const val DIGEST_MAX_CHARS = 10_000

    suspend fun generate(userId: UUID, title: String, from: LocalDate, to: LocalDate, projectId: UUID?): String {
        val entries = EntryRepository.list(userId, from, to, projectId).sortedBy { it.occurredOn }
        if (entries.isEmpty()) return buildMarkdown(title, from, to, entries, emptyMap(), null)

        val projects = ProjectRepository.list(userId).associateBy { it.id }
        val forAi = entries.filter { e -> e.projectId?.let { projects[it]?.aiEnabled } ?: true }
        val aiBody = if (forAi.isNotEmpty()) AiProcessor.writeReport(userId, digest(forAi, projects)) else null
        return buildMarkdown(title, from, to, entries, projects, aiBody)
    }

    /**
     * Собрать итоговый Markdown. [aiBody] — аналитическая часть от ИИ (null → собираем сами).
     * Функция чистая (без сети и базы) — на ней держатся юнит-тесты.
     */
    fun buildMarkdown(
        title: String,
        from: LocalDate,
        to: LocalDate,
        entries: List<EntryDto>,
        projects: Map<String, ProjectDto> = emptyMap(),
        aiBody: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        sb.append("_Период: ").append(humanDate(from)).append(" — ").append(humanDate(to)).append("_\n")
        if (entries.isEmpty()) {
            sb.append("\nЗа этот период записей нет.\n")
            return sb.toString()
        }
        sb.append("\n").append((aiBody ?: fallbackBody(entries)).trim()).append("\n")
        sb.append("\n").append(statsSection(entries, projects))
        sb.append("\n").append(chronologySection(entries))
        return sb.toString()
    }

    /**
     * Данные для ИИ: по записи на абзац — дата, проект, время и вся структура.
     * Компактный текст, а не JSON: так меньше токенов, а модели читают его не хуже.
     * Если всё не влезает в [DIGEST_MAX_CHARS] — урезаем шаги (они самые многословные),
     * решения и проблемы оставляем: из них и получается ценность отчёта.
     */
    fun digest(entries: List<EntryDto>, projects: Map<String, ProjectDto> = emptyMap()): String {
        fun render(maxSteps: Int): String = buildString {
            append("Записи разработчика за период (").append(entries.size).append(" шт.):\n")
            for (e in entries) {
                append("\n### ").append(e.occurredOn)
                e.projectId?.let { projects[it]?.name }?.let { append(" · проект «").append(it).append("»") }
                e.timeSpentMin?.let { append(" · ").append(humanDuration(it)) }
                append("\n")
                val s = e.structured
                if (s == null || s.summary.isBlank()) {
                    // ИИ эту запись ещё не разобрал (или не смог) — даём сырой текст.
                    append("Заметка: ").append(e.rawText.trim().take(RAW_FALLBACK_CHARS)).append("\n")
                    continue
                }
                append("Суть: ").append(s.summary).append("\n")
                s.steps.take(maxSteps).forEach { append("- сделано: ").append(it).append("\n") }
                s.decisions.forEach { append("- решение: ").append(it).append("\n") }
                s.problems.forEach { append("- проблема: ").append(it).append("\n") }
                if (s.outcome.isNotBlank()) append("Итог: ").append(s.outcome).append("\n")
            }
        }
        for (maxSteps in listOf(Int.MAX_VALUE, 4, 2, 0)) {
            val text = render(maxSteps)
            if (text.length <= DIGEST_MAX_CHARS) return text
        }
        return render(0).take(DIGEST_MAX_CHARS)
    }

    /**
     * Аналитическая часть без ИИ: те же разделы, что просим у модели, но собранные из структуры
     * записей механически. Связного текста нет, зато решения и проблемы не теряются.
     */
    fun fallbackBody(entries: List<EntryDto>): String {
        val structured = entries.mapNotNull { it.structured }
        val sb = StringBuilder()
        val results = structured.mapNotNull { s -> s.outcome.ifBlank { s.summary }.ifBlank { null } }.distinct()
        if (results.isNotEmpty()) {
            sb.append("## Главные результаты\n")
            results.forEach { sb.append("- ").append(it).append("\n") }
        }
        val decisions = structured.flatMap { it.decisions }.distinct()
        if (decisions.isNotEmpty()) {
            sb.append("\n## Ключевые решения\n")
            decisions.forEach { sb.append("- ").append(it).append("\n") }
        }
        val problems = structured.flatMap { it.problems }.distinct()
        if (problems.isNotEmpty()) {
            sb.append("\n## Сложные проблемы\n")
            problems.forEach { sb.append("- ").append(it).append("\n") }
        }
        // Совсем ничего не разобрано ИИ (например, ключа нет) — хронология ниже всё покажет.
        return sb.toString().ifBlank { "_Записи ещё не разобраны ИИ — ниже их хронология._\n" }
    }

    /** «Цифры периода»: считает код, не модель. */
    fun statsSection(entries: List<EntryDto>, projects: Map<String, ProjectDto> = emptyMap()): String {
        val sb = StringBuilder("## Цифры периода\n\n| Показатель | Значение |\n|---|---|\n")
        sb.append("| Рабочих дней с записями | ").append(entries.map { it.occurredOn }.distinct().size).append(" |\n")
        sb.append("| Записей | ").append(entries.size).append(" |\n")
        val minutes = entries.mapNotNull { it.timeSpentMin }.sum()
        if (minutes > 0) sb.append("| Учтённое время | ").append(humanDuration(minutes)).append(" |\n")
        val projectNames = entries.mapNotNull { e -> e.projectId?.let { projects[it]?.name } }.distinct()
        if (projectNames.isNotEmpty()) sb.append("| Проекты | ").append(projectNames.joinToString(", ")).append(" |\n")
        val topTags = entries.flatMap { it.structured?.tags.orEmpty() }
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(8).map { it.key }
        if (topTags.isNotEmpty()) sb.append("| Основные темы | ").append(topTags.joinToString(", ")).append(" |\n")
        return sb.toString()
    }

    /**
     * «Хронология»: одна строка на ДЕНЬ — дата и суть записей этого дня через «;».
     * Для тех, кому нужны даты; основная часть отчёта сгруппирована по темам.
     */
    fun chronologySection(entries: List<EntryDto>): String {
        val sb = StringBuilder("## Хронология\n\n")
        for ((day, dayEntries) in entries.groupBy { it.occurredOn }.toSortedMap()) {
            val heads = dayEntries.map { e ->
                // Сводку от ИИ выравниваем (заглавная буква, без точки в конце) — модель пишет
                // её то так, то эдак. Сырой текст пользователя оставляем как есть.
                e.structured?.summary?.takeIf { it.isNotBlank() }
                    ?.trim()?.trimEnd('.')?.replaceFirstChar { it.uppercase() }
                    ?: e.rawText.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(200)
            }
            sb.append("- **").append(humanDate(LocalDate.parse(day))).append("** — ")
                .append(heads.joinToString("; ")).append("\n")
        }
        return sb.toString()
    }

    private val months = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** 2026-08-12 → «12 августа 2026». */
    fun humanDate(d: LocalDate): String = "${d.dayOfMonth} ${months[d.monthValue - 1]} ${d.year}"

    /** 90 → «1 ч 30 мин», 480 → «8 ч», 45 → «45 мин». */
    fun humanDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m мин"
            m == 0 -> "$h ч"
            else -> "$h ч $m мин"
        }
    }
}
