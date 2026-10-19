package io.github.vihrea1337.devlog

import io.github.vihrea1337.devlog.DayFileImporter.Kind
import java.time.LocalDate

/**
 * Шаблон «Отчёт работодателю» — отчёт о проделанной работе за период (обычно месяц),
 * собранный из файлов дня (см. DayFileImport.kt) и коммитов.
 *
 * Чем отличается от обычного отчёта (ReportGenerator):
 * - **разделы — как в записях дня**: Сделано / Проверено / Передано другим и нехватки /
 *   Не сделано и почему. Руководителю важно, что сделано и в каком состоянии работа,
 *   а не «ключевые архитектурные решения»;
 * - **«Цифры периода» складывает код** из разделов «Цифры» записей дня: коммиты и сборки за
 *   день суммируются, число тестов («к концу дня») показывается как «было → стало».
 *   Модели в числах путаются, а сумма по дням — ровно то, что дольше всего считать руками;
 * - **файлы дня разбирает код, а не ИИ** — и в дайджест для ИИ уходят уже разобранные
 *   разделы, а коммиты дня, у которого есть файл, в него не дублируются (они — в цифрах
 *   и хронологии);
 * - **без ИИ отчёт тоже собирается целиком** (проект с выключенным ИИ, лимит Groq):
 *   те же разделы, только пункты — как в записях, по дням.
 *
 * Общие правила те же: шапку, цифры и хронологию всегда строит код; записи проектов с
 * выключенным ИИ в дайджест не попадают (это решает вызывающий ReportGenerator).
 */
object EmployerReport {

    /** Разделы записи, если это файл дня. Разбор дешёвый (текст до 20 000 символов), не кэшируем. */
    private fun EntryDto.dayFile(): DayFileImporter.ParsedDay? =
        if (source == DayFileImporter.SOURCE_TYPE) DayFileImporter.parse(rawText) else null

    private fun day(e: EntryDto): LocalDate = LocalDate.parse(e.occurredOn)

    /** Дни, за которые есть файл дня: коммиты таких дней не дублируем в тексте. */
    private fun daysWithFiles(entries: List<EntryDto>): Set<String> =
        entries.filter { it.source == DayFileImporter.SOURCE_TYPE }.map { it.occurredOn }.toSet()

    /** Сколько коммитов в записи импорта GitHub: строки-пункты «- …» верхнего уровня. */
    fun commitCount(e: EntryDto): Int =
        if (e.source != "github") 0 else e.rawText.lineSequence().count { it.startsWith("- ") }

    /**
     * Короткие строки о записи, которая НЕ файл дня: суть от ИИ, а без неё — заголовки
     * коммитов (для импорта GitHub) или первая строка заметки.
     */
    private fun headlines(e: EntryDto): List<String> {
        e.structured?.summary?.takeIf { it.isNotBlank() }?.let { return listOf(it.trim().trimEnd('.')) }
        if (e.source == "github") {
            return e.rawText.lineSequence().filter { it.startsWith("- ") }
                .map { it.removePrefix("- ").replace(Regex("""\s*\([0-9a-f]{7}\)$"""), "").trim() }
                .toList()
        }
        return listOfNotNull(e.rawText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200))
    }

    // --- Данные для ИИ ---

    /**
     * Дайджест для ИИ: по дню — разделы файла дня пунктами; прочие записи — заметкой.
     * Не влезает в лимит — пункты укорачиваются, затем остаются только их начала
     * (см. [DayFileImporter.shortHead]), а «Проверено» сжимается первым: для руководителя
     * это наименее важная часть.
     */
    fun digest(entries: List<EntryDto>, projects: Map<String, ProjectDto> = emptyMap()): String {
        val withFiles = daysWithFiles(entries)
        val relevant = entries.filterNot { it.source == "github" && it.occurredOn in withFiles }
        val parsed = relevant.associate { it.id to it.dayFile() }

        fun variant(itemLimit: Int, checkedHeadsOnly: Boolean, doneHeadsOnly: Boolean): String = buildString {
            append("Записи разработчика за период (").append(relevant.size).append(" шт., от ранних к поздним).\n")
            append("Разделы записей дня — как их записал автор.\n")
            for (e in relevant) {
                append("\n### ").append(e.occurredOn)
                e.projectId?.let { projects[it]?.name }?.let { append(" · проект «").append(it).append("»") }
                append("\n")
                val p = parsed[e.id]
                if (p == null) {
                    val raw = e.rawText.trim()
                    append("Заметка: ").append(if (raw.length > itemLimit * 3) raw.take(itemLimit * 3) + "…" else raw).append("\n")
                    continue
                }
                fun section(name: String, kind: Kind, headsOnly: Boolean) {
                    val items = p.items(kind)
                    if (items.isEmpty()) return
                    append(name).append(":\n")
                    items.forEach { item ->
                        val text = if (headsOnly) DayFileImporter.shortHead(item)
                        else if (item.length > itemLimit) item.take(itemLimit) + "…" else item
                        append("- ").append(text).append("\n")
                    }
                }
                section("Сделано", Kind.DONE, doneHeadsOnly)
                section("Проверено", Kind.CHECKED, checkedHeadsOnly)
                section("Передано другим / нехватки", Kind.GAPS, doneHeadsOnly)
                section("Не делалось и почему", Kind.NOT_DONE, doneHeadsOnly)
            }
        }

        val variants = sequenceOf(
            Triple(600, false, false),
            Triple(600, true, false),
            Triple(300, true, false),
            Triple(160, true, false),
            Triple(160, true, true),
        ).map { (limit, checked, done) -> variant(limit, checked, done) }
        return variants.firstOrNull { it.length <= ReportGenerator.DIGEST_MAX_CHARS }
            ?: variant(120, true, true).take(ReportGenerator.DIGEST_MAX_CHARS)
    }

    // --- Без ИИ ---

    /**
     * Разделы без ИИ: пункты как в записях. «Сделано» — по дням (группировать по темам
     * умеет только ИИ), «Проверено» — строка на день, «Не сделано» — по ПОСЛЕДНЕЙ записи дня:
     * она отражает состояние на конец периода, а ранние «не сделано» часто уже сделаны позже.
     */
    fun fallbackBody(entries: List<EntryDto>): String {
        val withFiles = daysWithFiles(entries)
        val byDay = entries.groupBy { it.occurredOn }.toSortedMap()
        val sb = StringBuilder()

        sb.append("## Сделано\n")
        for ((dayStr, dayEntries) in byDay) {
            val items = mutableListOf<String>()
            for (e in dayEntries) {
                val p = e.dayFile()
                when {
                    p != null -> items += p.items(Kind.DONE).ifEmpty { p.preamble }
                    e.source == "github" && dayStr in withFiles -> Unit // коммиты дня — в цифрах и хронологии
                    else -> items += headlines(e)
                }
            }
            if (items.isEmpty()) continue
            sb.append("\n**").append(ReportGenerator.humanDate(LocalDate.parse(dayStr))).append("**\n")
            items.forEach { sb.append("- ").append(it).append("\n") }
        }

        val checked = byDay.mapNotNull { (dayStr, list) ->
            // Не shortHead: «Блочные тесты — зелёные» без второй половины теряет смысл.
            val heads = list.mapNotNull { it.dayFile() }.flatMap { it.items(Kind.CHECKED) }.map { brief(it, 140) }
            if (heads.isEmpty()) null else "- **${ReportGenerator.humanDate(LocalDate.parse(dayStr))}** — ${heads.joinToString("; ")}"
        }
        if (checked.isNotEmpty()) sb.append("\n## Проверено\n").append(checked.joinToString("\n")).append("\n")

        val gaps = entries.mapNotNull { it.dayFile() }.flatMap { it.items(Kind.GAPS) }.distinct()
        if (gaps.isNotEmpty()) {
            sb.append("\n## Передано другим и нехватки\n")
            gaps.forEach { sb.append("- ").append(it).append("\n") }
        }

        val lastWithNotDone = entries.filter { it.source == DayFileImporter.SOURCE_TYPE }
            .sortedBy { it.occurredOn }
            .lastOrNull { it.dayFile()?.items(Kind.NOT_DONE)?.isNotEmpty() == true }
        if (lastWithNotDone != null) {
            sb.append("\n## Не сделано и почему\n")
            sb.append("_По записи за ").append(ReportGenerator.humanDate(day(lastWithNotDone))).append("._\n\n")
            lastWithNotDone.dayFile()!!.items(Kind.NOT_DONE).forEach { sb.append("- ").append(it).append("\n") }
        }
        return sb.toString()
    }

    /** Пункт целиком, но не длиннее [max] символов (по границе слова), без звёздочек и точки в конце. */
    fun brief(item: String, max: Int): String {
        val plain = item.replace("**", "").trim().trimEnd('.', ';')
        return if (plain.length <= max) plain else plain.take(max).substringBeforeLast(' ').trimEnd(',', ';') + "…"
    }

    // --- Цифры ---

    /** Одна строка раздела «Цифры», понятая кодом. [start] — «было» у записи вида «84 → 144». */
    data class Metric(val key: String, val label: String, val start: Int?, val value: Int, val level: Boolean)

    /** Строка таблицы: показатель и значение за период. */
    data class MetricRow(val label: String, val value: String)

    // «84 → 144» и цепочка «144 → 149 → 153 → 157»: было — первое, стало — последнее.
    private val LEADING_CHAIN = Regex("""^\s*\d+(?:\s*→\s*\d+)+""")
    // Число, но не часть даты/версии («26.10.19», «1.3.1») и не дробь.
    private val NUMBER = Regex("""(?<![\d.,])\d+(?![\d]|[.,]\d)""")
    // Номера задач и нехваток (№65, № 13–31) — это не количество.
    private val ISSUE_NO = Regex("""№\s*\d+(?:\s*[–-]\s*\d+)?""")
    private val TESTS_KEY = Regex("""(^|\s)(блочн\S*\s+|авто)?тест(ов|ы)?(\s|$)""")
    private val DAY_WORDS = listOf("за день", "к концу дня", "на конец дня", "за сегодня", "всего", "утром", "днём", "вечером", "ночью")

    /** Количество должно стоять сразу после двоеточия — «Коммитов: 7», а не в глубине фразы. */
    private const val NUMBER_WITHIN = 12

    /**
     * Разобрать строку «Показатель: число …». null — в строке нет понятного количества.
     * Пункт, начатый жирным («**Сборка для тестеров**: …»), — рассказ, а не показатель.
     */
    fun parseMetric(item: String): Metric? {
        if (item.trimStart().startsWith("**")) return null
        val plain = item.replace("`", "").trim()
        val colon = plain.indexOf(':')
        if (colon <= 0) return null
        val rawLabel = plain.substring(0, colon).trim()
        if (rawLabel.length > 60) return null
        val valueText = plain.substring(colon + 1).replace(ISSUE_NO, "").trim()
        val key = metricKey(rawLabel)
        if (key.isEmpty()) return null
        val label = metricLabel(rawLabel, key)
        val chain = LEADING_CHAIN.find(valueText)
        if (chain != null) {
            val numbers = chain.value.split('→').map { it.trim().toInt() }
            return Metric(key, label, numbers.first(), numbers.last(), level = true)
        }
        val found = NUMBER.find(valueText) ?: return null
        if (found.range.first > NUMBER_WITHIN) return null
        // Несколько чисел в одной фразе («1 вопрос помощнику, 1 напоминание», «52 страницы,
        // 37 экранов») — непонятно, что из них складывать; скобки и пояснения после «;»/«—»
        // не считаем: «15 (п.1–5, 7, 9)» и «262 — утром было 187» — это одно число.
        val clause = valueText.replace(Regex("""\([^)]*\)"""), " ").split("; ", " — ").first()
        if (NUMBER.findAll(clause).count() > 1) return null
        val number = found.value.toIntOrNull() ?: return null
        val level = key == "тесты" || listOf("к концу", "на конец", "всего").any { it in rawLabel.lowercase() }
        return Metric(key, label, null, number, level)
    }

    /**
     * Ключ показателя, чтобы складывать одно и то же из разных дней: без «за день»,
     * «к концу дня», знаков препинания. «Тестов к концу дня» и «Блочные тесты» — оба «тесты».
     */
    fun metricKey(label: String): String {
        var k = label.lowercase()
        DAY_WORDS.forEach { k = k.replace(it, " ") }
        k = k.replace(Regex("""[^\p{L}\p{N}\s-]"""), " ").replace(Regex("""\s+"""), " ").trim()
        return if (TESTS_KEY.containsMatchIn(k)) "тесты" else k
    }

    private fun metricLabel(label: String, key: String): String {
        if (key == "тесты") return "Тестов в проекте"
        var l = label
        DAY_WORDS.forEach { l = l.replace(it, "", ignoreCase = true) }
        return l.replace(Regex("""\s+"""), " ").trim().trimEnd(',', '.').replaceFirstChar { it.uppercase() }
    }

    /**
     * Сложить показатели по дням. Поток (коммиты, сборки, найденные ошибки) — сумма;
     * уровень (сколько тестов на конец дня) — «первое → последнее». Порядок строк — как
     * показатели впервые встретились в записях.
     */
    fun summarize(days: List<Pair<LocalDate, List<String>>>): List<MetricRow> {
        val metrics = days.sortedBy { it.first }.flatMap { (d, items) -> items.mapNotNull { parseMetric(it) }.map { d to it } }
        return metrics.groupBy { it.second.key }.map { (_, list) ->
            val first = list.first().second
            if (list.any { it.second.level }) {
                val start = first.start ?: first.value
                val end = list.last().second.value
                MetricRow(first.label, if (start != end) "$start → $end" else "$end")
            } else {
                MetricRow(first.label, list.sumOf { it.second.value }.toString())
            }
        }
    }

    /** «Цифры периода» шаблона: базовые цифры + коммиты GitHub + сложенные «Цифры» записей дня. */
    fun numbersSection(entries: List<EntryDto>, projects: Map<String, ProjectDto> = emptyMap()): String {
        val sb = StringBuilder("## Цифры периода\n\n| Показатель | Значение |\n|---|---|\n")
        sb.append("| Рабочих дней с записями | ").append(entries.map { it.occurredOn }.distinct().size).append(" |\n")
        val commits = entries.sumOf { commitCount(it) }
        if (commits > 0) sb.append("| Коммитов (по GitHub) | ").append(commits).append(" |\n")
        val minutes = entries.mapNotNull { it.timeSpentMin }.sum()
        if (minutes > 0) sb.append("| Учтённое время | ").append(ReportGenerator.humanDuration(minutes)).append(" |\n")
        val projectNames = entries.mapNotNull { e -> e.projectId?.let { projects[it]?.name } }.distinct()
        if (projectNames.size > 1) sb.append("| Проекты | ").append(projectNames.joinToString(", ")).append(" |\n")

        val rows = summarize(
            entries.mapNotNull { e -> e.dayFile()?.let { day(e) to it.items(Kind.NUMBERS) } }.filter { it.second.isNotEmpty() },
        )
            // Коммиты уже посчитаны по GitHub — вторая строка «Коммитов» из записей дня только путала бы.
            .filterNot { commits > 0 && metricKey(it.label).contains("коммит") }
        rows.forEach { sb.append("| ").append(it.label.replace("|", "/")).append(" | ").append(it.value).append(" |\n") }
        if (rows.isNotEmpty()) sb.append("\n_Сложено кодом из разделов «Цифры» записей дня: за день — сумма, «к концу дня» — было → стало._\n")
        return sb.toString()
    }

    /** Хронология: строка на день — суть записи дня и число коммитов. */
    fun chronologySection(entries: List<EntryDto>): String {
        val sb = StringBuilder("## Хронология\n\n")
        val withFiles = daysWithFiles(entries)
        for ((dayStr, list) in entries.groupBy { it.occurredOn }.toSortedMap()) {
            val heads = list.flatMap { e ->
                when {
                    e.source == DayFileImporter.SOURCE_TYPE ->
                        listOfNotNull(e.structured?.summary?.takeIf { it.isNotBlank() } ?: e.dayFile()?.let { DayFileImporter.toStructured(it)?.summary })
                    e.source == "github" && dayStr in withFiles -> emptyList()
                    e.source == "github" -> listOfNotNull(headlines(e).firstOrNull())
                    else -> headlines(e).take(1)
                }
            }.map { it.trim().trimEnd('.').replaceFirstChar { c -> c.uppercase() } }
            val commits = list.sumOf { commitCount(it) }
            sb.append("- **").append(ReportGenerator.humanDate(LocalDate.parse(dayStr))).append("** — ")
                .append(heads.joinToString("; ").ifEmpty { "—" })
            if (commits > 0) sb.append(" · ").append(commits).append(" ").append(commitsWord(commits))
            sb.append("\n")
        }
        return sb.toString()
    }

    /** 1 коммит, 2 коммита, 5 коммитов. */
    fun commitsWord(n: Int): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> "коммитов"
            mod10 == 1 -> "коммит"
            mod10 in 2..4 -> "коммита"
            else -> "коммитов"
        }
    }

    /**
     * Строка «кто и по какому проекту» под периодом — руководитель видит, чей это отчёт,
     * даже если файл переслали без письма. Пустые части пропускаются.
     */
    fun passportLine(author: String?, projectNames: List<String>, today: LocalDate): String =
        listOfNotNull(
            author?.let { "**Исполнитель:** $it" },
            projectNames.takeIf { it.isNotEmpty() }?.let { "**Проект:** ${it.joinToString(", ")}" },
            "**Составлен:** ${ReportGenerator.humanDate(today)}",
        ).joinToString(" · ")

    /**
     * «По неделям»: раздел «Кратко» каждой недели одной строкой — руководителю видно, как
     * шла работа по ходу месяца, без чтения хронологии. Неделя без «Кратко» пропускается
     * (например, собранная кодом без ИИ); меньше двух недель — раздела нет.
     */
    fun weeksSection(weeks: List<ReportGenerator.WeeklySource>): String? {
        val lines = weeks.mapNotNull { w ->
            briefOf(w.body)?.let { "- **${ReportGenerator.weekLabel(w.from, w.to)}** — $it" }
        }
        if (lines.size < 2) return null
        return "## По неделям\n\n" + lines.joinToString("\n") + "\n"
    }

    /** Текст раздела «Кратко» (заголовок любого уровня) одной строкой; null — раздела нет или он пуст. */
    fun briefOf(md: String): String? {
        val lines = md.lines()
        val start = lines.indexOfFirst { line ->
            val t = line.trim()
            t.startsWith("#") && t.trimStart('#').trim().lowercase().startsWith("кратко")
        }
        if (start < 0) return null
        val text = lines.drop(start + 1).takeWhile { !it.trimStart().startsWith("#") }
            .joinToString(" ") { it.trim() }.replace(Regex("""\s+"""), " ").trim()
        return text.ifEmpty { null }
    }

    /** Подпись в конце: откуда взялись разделы, а откуда цифры. */
    const val FOOTER =
        "_Разделы составлены по записям дня и коммитам за период. Цифры и хронология посчитаны автоматически по тем же записям._"

    /**
     * Итоговый Markdown шаблона. [body] — текст от ИИ (или из недельных отчётов); null — собрать кодом.
     * [weeks] — недельные тексты, если отчёт собран из них (тогда добавляется «По неделям»).
     *
     * Вид (образец — docs/employer-report-sample.md): заголовок, период, строка «Исполнитель ·
     * Проект · Составлен», затем Кратко / Сделано / Проверено / Передано другим и нехватки /
     * Не сделано и почему, «По неделям», «Цифры периода», «Хронология» и подпись.
     */
    fun buildMarkdown(
        title: String,
        from: LocalDate,
        to: LocalDate,
        entries: List<EntryDto>,
        projects: Map<String, ProjectDto> = emptyMap(),
        body: String? = null,
        author: String? = null,
        weeks: List<ReportGenerator.WeeklySource> = emptyList(),
        today: LocalDate = LocalDate.now(),
    ): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        sb.append("_Период: ").append(ReportGenerator.humanDate(from)).append(" — ").append(ReportGenerator.humanDate(to)).append("_\n")
        if (entries.isEmpty() && body == null) {
            sb.append("\nЗа этот период записей нет.\n")
            return sb.toString()
        }
        val projectNames = entries.mapNotNull { e -> e.projectId?.let { projects[it]?.name } }.distinct()
        sb.append("\n").append(passportLine(author, projectNames, today)).append("\n")
        sb.append("\n").append((body ?: fallbackBody(entries)).trim()).append("\n")
        weeksSection(weeks)?.let { sb.append("\n").append(it) }
        if (entries.isNotEmpty()) {
            sb.append("\n").append(numbersSection(entries, projects))
            // За один день хронология повторила бы «Кратко» — её нет.
            if (from != to) sb.append("\n").append(chronologySection(entries))
        }
        sb.append("\n---\n\n").append(FOOTER).append("\n")
        return sb.toString()
    }
}
