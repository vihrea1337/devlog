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
 *    [digest] — сами заметки автора (первоисточник) с краткой сутью от ИИ; на длинных
 *    периодах, где заметки не влезают, — полную структуру записей. Если ИИ недоступен (нет ключа, лимит, ошибка) — эту часть собирает код
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
    internal const val DIGEST_MAX_CHARS = 10_000

    /** Шаблон «Отчёт работодателю» (см. EmployerReport.kt); всё прочее — обычный отчёт по темам. */
    const val TEMPLATE_EMPLOYER = "employer"

    /**
     * Кто пишет текст отчёта: по умолчанию Groq (AiProcessor). Вынесено в переменную, чтобы
     * тесты проверяли, ЧТО уходит во внешний ИИ (и что не уходит), без настоящей сети.
     */
    var aiWriter: suspend (userId: UUID, digest: String, employer: Boolean) -> String? =
        { userId, digest, employer -> AiProcessor.writeReport(userId, digest, employer) }

    suspend fun generate(
        userId: UUID,
        title: String,
        from: LocalDate,
        to: LocalDate,
        projectId: UUID?,
        template: String = "default",
        fromWeeklyReports: Boolean = false,
    ): String {
        val employer = template == TEMPLATE_EMPLOYER
        val entries = EntryRepository.list(userId, from, to, projectId).sortedBy { it.occurredOn }
        val projects = ProjectRepository.list(userId).associateBy { it.id }
        val weekly = if (fromWeeklyReports) pickWeekly(ReportRepository.list(userId), from, to, projectId) else emptyList()

        fun build(body: String?) =
            if (employer) EmployerReport.buildMarkdown(title, from, to, entries, projects, body)
            else buildMarkdown(title, from, to, entries, projects, body)

        if (entries.isEmpty() && weekly.isEmpty()) return build(null)

        val forAi = entries.filter { e -> e.projectId?.let { projects[it]?.aiEnabled } ?: true }
        fun entriesDigest(list: List<EntryDto>) = if (employer) EmployerReport.digest(list, projects) else digest(list, projects)

        if (weekly.isEmpty()) {
            val aiBody = if (forAi.isNotEmpty()) aiWriter(userId, entriesDigest(forAi), employer) else null
            return build(aiBody)
        }

        // Сборка из недельных отчётов. Дни, которые они не покрыли, добавляем из записей.
        val covered = weekly.flatMap { w -> generateSequence(w.from) { it.plusDays(1) }.takeWhile { it <= w.to }.toList() }.toSet()
        val uncovered = entries.filter { LocalDate.parse(it.occurredOn) !in covered }
        // Текст недельного отчёта мог собраться кодом и содержать записи проектов с выключенным
        // ИИ — во внешний ИИ отдаём его, только если таких проектов в отчёте быть не может.
        val weeklySafeForAi = if (projectId != null) projects[projectId.toString()]?.aiEnabled == true
        else projects.values.none { !it.aiEnabled }
        val aiBody = if (weeklySafeForAi) {
            val uncoveredForAi = uncovered.filter { e -> e.projectId?.let { projects[it]?.aiEnabled } ?: true }
            aiWriter(userId, weeklyDigest(weekly, uncoveredForAi.takeIf { it.isNotEmpty() }?.let(::entriesDigest)), employer)
        } else {
            null
        }
        val uncoveredBody = uncovered.takeIf { it.isNotEmpty() }
            ?.let { if (employer) EmployerReport.fallbackBody(it) else fallbackBody(it) }
        return build(aiBody ?: weeklyConcatBody(weekly, uncoveredBody))
    }

    /** Недельный отчёт как источник месячного: период и текст без шапки, цифр и хронологии. */
    data class WeeklySource(val from: LocalDate, val to: LocalDate, val body: String)

    /**
     * Выбрать недельные отчёты (период до 8 дней) того же проекта, целиком лежащие внутри
     * [from]..[to]. Если несколько отчётов пересекаются (неделю пересобирали) — берём самый
     * свежий: его, скорее всего, и вычитывали. Чистая функция — на ней тест.
     */
    fun pickWeekly(reports: List<ReportDto>, from: LocalDate, to: LocalDate, projectId: UUID?): List<WeeklySource> {
        val picked = mutableListOf<Pair<ReportDto, ClosedRange<LocalDate>>>()
        reports.filter { it.projectId == projectId?.toString() }
            .sortedByDescending { it.createdAt }
            .forEach { r ->
                val start = LocalDate.parse(r.periodStart)
                val end = LocalDate.parse(r.periodEnd)
                val shortEnough = java.time.temporal.ChronoUnit.DAYS.between(start, end) <= 7
                val inside = start >= from && end <= to && !(start == from && end == to)
                val overlaps = picked.any { (_, range) -> start <= range.endInclusive && end >= range.start }
                if (shortEnough && inside && !overlaps) picked.add(r to start..end)
            }
        return picked.sortedBy { it.second.start }
            .map { (r, range) -> WeeklySource(range.start, range.endInclusive, stripFacts(r.contentMd)) }
            .filter { it.body.isNotBlank() }
    }

    /**
     * Текст отчёта без того, что строит код: заголовка `# …`, строки периода, разделов
     * «Цифры периода» и «Хронология» — в месячном отчёте код соберёт их заново за весь месяц.
     */
    fun stripFacts(md: String): String {
        val out = StringBuilder()
        var skipping = false
        for (line in md.lines()) {
            val t = line.trim()
            if (t.startsWith("## ")) {
                val h = t.removePrefix("## ").trim().lowercase()
                skipping = h.startsWith("цифры периода") || h.startsWith("хронология")
            }
            if (skipping || t.startsWith("# ") || (t.startsWith("_Период:") && t.endsWith("_"))) continue
            out.append(line).append("\n")
        }
        return out.toString().trim()
    }

    /** Подпись периода недели: «13–19 октября 2026» / «29 сентября — 5 октября 2026». */
    fun weekLabel(from: LocalDate, to: LocalDate): String =
        if (from.month == to.month && from.year == to.year) "${from.dayOfMonth}–${humanDate(to)}"
        else "${humanDate(from).substringBeforeLast(' ')} — ${humanDate(to)}"

    /**
     * Данные для ИИ из недельных отчётов: их уже вычитал автор, это главный источник.
     * Не влезает в лимит — каждый недельный текст урезается поровну.
     */
    fun weeklyDigest(weekly: List<WeeklySource>, uncoveredDigest: String?): String {
        fun build(perPart: Int) = buildString {
            append("Недельные отчёты за этот период — автор их уже вычитал и поправил, это главный источник. ")
            append("Собери из них один отчёт за весь период, объединяя одинаковые темы разных недель.\n")
            for (w in weekly) {
                append("\n### Неделя ").append(weekLabel(w.from, w.to)).append("\n")
                append(if (w.body.length > perPart) w.body.take(perPart) + "…" else w.body).append("\n")
            }
            if (uncoveredDigest != null) {
                append("\n### Дни без недельного отчёта\n").append(uncoveredDigest.take(perPart)).append("\n")
            }
        }
        val parts = weekly.size + if (uncoveredDigest != null) 1 else 0
        var perPart = Int.MAX_VALUE
        var text = build(perPart)
        while (text.length > DIGEST_MAX_CHARS && perPart > 300) {
            perPart = if (perPart == Int.MAX_VALUE) DIGEST_MAX_CHARS / parts else perPart * 3 / 4
            text = build(perPart)
        }
        return text.take(DIGEST_MAX_CHARS)
    }

    /**
     * Без ИИ: недельные отчёты подряд, каждый под своим заголовком (их разделы — на уровень
     * ниже), затем дни без недельного отчёта. Вычитанный текст важнее механической сборки.
     */
    fun weeklyConcatBody(weekly: List<WeeklySource>, uncoveredBody: String?): String = buildString {
        fun demote(md: String) = md.lines().joinToString("\n") { if (it.startsWith("## ")) "#$it" else it }.trim()
        for (w in weekly) {
            append("## Неделя ").append(weekLabel(w.from, w.to)).append("\n\n").append(demote(w.body)).append("\n\n")
        }
        if (uncoveredBody != null) append("## Дни без недельного отчёта\n\n").append(demote(uncoveredBody)).append("\n")
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
     * Данные для ИИ: по записи на абзац — дата, проект, время и содержание.
     * Компактный текст, а не JSON: так меньше токенов, а модели читают его не хуже.
     *
     * Главное — САМА ЗАМЕТКА автора, а не только её разбор ИИ. Раньше отчёт строился только
     * по разбору, и каждая ошибка разбора («вывод: настроить бэкап» → «бэкап настроен»)
     * переезжала в отчёт и обрастала подробностями. Теперь модель видит первоисточник,
     * а от разбора — лишь «суть» как подсказку.
     *
     * Если всё не влезает в [DIGEST_MAX_CHARS] (длинный период), урезаем заметки, а если и это
     * не помогает — переходим на разбор ИИ без заметок, как раньше: шаги урезаем (они самые
     * многословные), решения и проблемы оставляем — из них и получается ценность отчёта.
     */
    fun digest(entries: List<EntryDto>, projects: Map<String, ProjectDto> = emptyMap()): String {
        fun StringBuilder.header(e: EntryDto) {
            append("\n### ").append(e.occurredOn)
            e.projectId?.let { projects[it]?.name }?.let { append(" · проект «").append(it).append("»") }
            e.timeSpentMin?.let { append(" · ").append(humanDuration(it)) }
            append("\n")
        }

        /** Вариант «заметка + суть»: заметка обрезается до [rawLimit] символов. */
        fun withNotes(rawLimit: Int): String = buildString {
            append("Записи разработчика за период (").append(entries.size).append(" шт., от ранних к поздним).\n")
            append("«Заметка» — текст автора, первоисточник; «Суть» — краткий разбор ИИ, подсказка.\n")
            for (e in entries) {
                header(e)
                val raw = e.rawText.trim()
                append("Заметка: ").append(if (raw.length > rawLimit) raw.take(rawLimit) + "…" else raw).append("\n")
                e.structured?.summary?.takeIf { it.isNotBlank() }?.let { append("Суть: ").append(it).append("\n") }
            }
        }

        /** Вариант «только разбор ИИ» — для длинных периодов, где заметки не влезают. */
        fun structuredOnly(maxSteps: Int): String = buildString {
            append("Записи разработчика за период (").append(entries.size).append(" шт., от ранних к поздним):\n")
            for (e in entries) {
                header(e)
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

        // Sequence ленивая: следующий вариант строится, только если предыдущий не влез.
        val variants = sequenceOf(3000, 1200, 600).map { limit -> withNotes(limit) } +
            sequenceOf(Int.MAX_VALUE, 4, 2, 0).map { maxSteps -> structuredOnly(maxSteps) }
        return variants.firstOrNull { it.length <= DIGEST_MAX_CHARS }
            ?: structuredOnly(0).take(DIGEST_MAX_CHARS)
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

    private val monthsNominative = listOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
    )

    /**
     * Заголовок отчёта, если пользователь не задал свой. Раньше было «Отчёт за 2026-10-04 —
     * 2026-10-10» — машинный формат в документе для человека. Теперь:
     * весь месяц (или его начало по сегодня) — «Отчёт за октябрь 2026»; внутри месяца —
     * «Отчёт за 4–10 октября 2026»; внутри года — «Отчёт за 20 июля — 10 октября 2026».
     * Отчёт по одному проекту получает его имя впереди: «DevLog: отчёт за октябрь 2026».
     */
    fun defaultTitle(
        from: LocalDate,
        to: LocalDate,
        today: LocalDate = LocalDate.now(),
        projectName: String? = null,
    ): String {
        val sameMonth = from.year == to.year && from.month == to.month
        val wholeMonth = sameMonth && from.dayOfMonth == 1 && (to.dayOfMonth == to.lengthOfMonth() || to == today)
        val period = when {
            wholeMonth -> "Отчёт за ${monthsNominative[from.monthValue - 1]} ${from.year}"
            sameMonth -> "Отчёт за ${from.dayOfMonth}–${humanDate(to)}"
            from.year == to.year -> "Отчёт за ${humanDate(from).substringBeforeLast(' ')} — ${humanDate(to)}"
            else -> "Отчёт за ${humanDate(from)} — ${humanDate(to)}"
        }
        // «Отчёт за …» → «DevLog: отчёт за …» — с маленькой буквы после двоеточия.
        return if (projectName.isNullOrBlank()) period else "$projectName: ${period.replaceFirstChar { it.lowercase() }}"
    }

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
