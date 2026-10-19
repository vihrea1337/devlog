package io.github.vihrea1337.devlog

import java.time.LocalDate
import java.util.UUID

/**
 * Импорт «файлов дня» — записей, которые ведутся после каждого пункта работы в отдельной
 * папке: один файл на день, имя `ГГГГ-ММ-ДД.md`, внутри — одни и те же разделы:
 *
 * ```
 * # 2026-10-19 — Android-клиент, проект X
 * ## Сделано
 * - пункт…
 * ## Проверено
 * ## Нехватки API и макета
 * ## Не делалось и почему
 * ## Цифры
 * - Коммитов за день: 7.
 * ```
 *
 * Чем отличается от общего импорта текста (TextImport.kt):
 * - **дата берётся из имени файла**, а не из строк внутри (в заголовках бывает «14.10.2026»,
 *   а внутри текста встречаются чужие даты — номер сборки, срок задачи);
 * - **один файл — одна запись**, и повторный импорт того же дня ОБНОВЛЯЕТ запись (файл
 *   дописывается в течение дня), а не создаёт вторую — см. ImportedEntries;
 * - **разделы разбирает код, а не ИИ**: раз структура уже есть, отправлять её в Groq незачем —
 *   меньше запросов к бесплатному лимиту, ничего не выдумывается, и это работает даже у
 *   проекта с выключенным ИИ (конфиденциальные записи никуда не уходят).
 *
 * Файл без раздела «Сделано» разобрать нечем — такая запись идёт обычным путём
 * (очередь ИИ или черновик, если у проекта ИИ выключен).
 */
object DayFileImporter {

    /** Максимум файлов за один запрос — два месяца с запасом. */
    const val MAX_FILES = 100

    /** Источник записи (колонка source, до 20 символов). */
    const val SOURCE_TYPE = "day-file"

    /** Разделы записи дня, которые понимает код. Порядок — как в отчёте работодателю. */
    enum class Kind { DONE, CHECKED, GAPS, NOT_DONE, NUMBERS, OTHER }

    /** Раздел: его заголовок как в файле и пункты списка. */
    data class Section(val kind: Kind, val title: String, val items: List<String>)

    /** Разобранный файл дня. [preamble] — абзацы между заголовком и первым разделом. */
    data class ParsedDay(val title: String?, val preamble: List<String>, val sections: List<Section>) {
        fun items(kind: Kind): List<String> = sections.filter { it.kind == kind }.flatMap { it.items }
    }

    private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val RU_DATE = Regex("""(?<!\d)(\d{1,2})\.(\d{1,2})\.(\d{4})(?!\d)""")
    private val BULLET = Regex("""^(?:[-*•]|\d+[.)])\s+""")
    private val DASH_BULLET = Regex("""^[-*•]\s+""")

    /**
     * Дата дня. Сначала из имени файла (`2026-10-19.md`) — это главный источник; если в имени
     * даты нет — из заголовка `# …` (ГГГГ-ММ-ДД или ДД.ММ.ГГГГ). Чужие даты из глубины текста
     * не берём: «сборка 26.10.19» или «срок 01.11.2026» — не день записи.
     */
    fun dateFor(fileName: String, text: String): LocalDate? {
        ISO_DATE.find(fileName.substringAfterLast('/').substringAfterLast('\\'))
            ?.let { parseDateOrNull(it.value) }?.let { return it }
        val title = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("# ") } ?: return null
        ISO_DATE.find(title)?.let { parseDateOrNull(it.value) }?.let { return it }
        val ru = RU_DATE.find(title) ?: return null
        val (d, m, y) = ru.destructured
        return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
    }

    /** К какому разделу относится заголовок `## …` (по началу, без учёта регистра). */
    fun kindOf(heading: String): Kind {
        val h = heading.trim().lowercase()
        return when {
            h.startsWith("сделано") -> Kind.DONE
            h.startsWith("проверено") || h.startsWith("проверки") -> Kind.CHECKED
            h.startsWith("нехватк") || h.startsWith("передано") -> Kind.GAPS
            h.startsWith("не делалось") || h.startsWith("не сделано") -> Kind.NOT_DONE
            h.startsWith("цифры") -> Kind.NUMBERS
            else -> Kind.OTHER
        }
    }

    /**
     * Разобрать текст на заголовок, вступление и разделы. Чистая функция — на ней тесты.
     *
     * Пункт списка — строка с маркером (`- `, `* `, `1. `); строки после неё без маркера и
     * без пустой строки между — продолжение того же пункта (длинные пункты переносятся).
     * Абзац без маркера после пустой строки — отдельный пункт. Строка целиком жирным
     * (`**Вечер 14.10 — …**`) и `### …` — подзаголовки-группы, в пункты не идут.
     * Второй `# …` посреди файла (день дописан вторым заходом) — граница частей.
     */
    fun parse(text: String): ParsedDay {
        var title: String? = null
        val preamble = mutableListOf<String>()
        val sections = mutableListOf<Section>()
        var currentKind: Kind? = null
        var currentTitle = ""
        var items = mutableListOf<String>()
        var current: StringBuilder? = null
        var afterBlank = true

        fun closeItem() {
            current?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { items.add(it) }
            current = null
        }
        fun closeSection() {
            closeItem()
            val kind = currentKind
            when {
                kind == null -> preamble.addAll(items)
                // В «Цифры» иногда попадает рассказ о сделанном: «**Сборка для тестеров** …».
                // Пункт, начатый жирным, — это работа, а не показатель: переносим в «Сделано».
                kind == Kind.NUMBERS -> {
                    val (story, numbers) = items.partition { it.startsWith("**") }
                    sections.add(Section(kind, currentTitle, numbers))
                    if (story.isNotEmpty()) sections.add(Section(Kind.DONE, "$currentTitle (сделано)", story))
                }
                else -> sections.add(Section(kind, currentTitle, items.toList()))
            }
            items = mutableListOf()
        }

        for (raw in text.lineSequence()) {
            val trimmed = raw.trim()
            // Строка с отступом сразу под пунктом — его продолжение, даже если похожа на маркер:
            // перенос «(412 и\n  360) в светлой…» — не пункт «360)», а вложенный список
            // «  - снимок открывается…» — часть пункта-заголовка «Замечания тестеров, 2–5:».
            val indented = raw.startsWith(" ") || raw.startsWith("\t")
            when {
                trimmed.isEmpty() -> { closeItem(); afterBlank = true; continue }
                trimmed.startsWith("## ") -> {
                    closeSection()
                    currentTitle = trimmed.removePrefix("## ").trim()
                    currentKind = kindOf(currentTitle)
                }
                trimmed.startsWith("# ") && title == null && sections.isEmpty() && currentKind == null -> {
                    title = trimmed.removePrefix("# ").trim()
                }
                // Второй заголовок первого уровня посреди файла — «# 16.10 (вечер) — …»: день
                // дописан вторым заходом. Это граница частей, не пункт; разделы дальше копятся.
                trimmed.startsWith("# ") -> { closeSection(); currentKind = null; currentTitle = "" }
                // Подзаголовок внутри раздела (### …) — подпись группы, как и жирная строка.
                trimmed.startsWith("### ") -> closeItem()
                // «**Вечер 14.10 — A3, A5**» — подпись группы пунктов, не сам пункт.
                trimmed.startsWith("**") && trimmed.endsWith("**") && trimmed.length > 4 &&
                    trimmed.count { it == '*' } == 4 -> closeItem()
                indented && current != null && !afterBlank ->
                    current!!.append(' ').append(trimmed.replaceFirst(DASH_BULLET, ""))
                BULLET.containsMatchIn(trimmed) -> {
                    closeItem()
                    current = StringBuilder(trimmed.replaceFirst(BULLET, ""))
                }
                current != null && !afterBlank -> current!!.append(' ').append(trimmed)
                else -> { closeItem(); current = StringBuilder(trimmed) }
            }
            afterBlank = false
        }
        closeSection()
        return ParsedDay(title, preamble, sections)
    }

    /**
     * Структура записи из разделов — то же, что обычно возвращает ИИ, но без ИИ:
     * суть — из первых пунктов «Сделано», шаги — «Сделано», проблемы — «Нехватки»,
     * итог — «Цифры». null — раздела «Сделано» нет, разбирать нечего (пусть разберёт ИИ).
     */
    fun toStructured(parsed: ParsedDay): StructuredDto? {
        val done = parsed.items(Kind.DONE)
        if (done.isEmpty()) return null
        val heads = done.take(3).map { shortHead(it) }
        val summary = heads.joinToString("; ") + if (done.size > 3) "; и ещё ${done.size - 3}" else ""
        val numbers = parsed.items(Kind.NUMBERS)
        return StructuredDto(
            summary = summary,
            steps = done,
            decisions = emptyList(),
            problems = parsed.items(Kind.GAPS),
            outcome = numbers.joinToString("; ") { it.trimEnd('.') },
            tags = emptyList(),
        )
    }

    /**
     * Начало пункта до первого «:», «. », « — » или «;» — суть без подробностей,
     * не длиннее 90 символов (по границе слова). Звёздочки жирного убираем.
     */
    fun shortHead(item: String): String {
        val plain = item.replace("**", "").replace("`", "").trim()
        // Разделитель ищем только ВНЕ скобок и кавычек: «Забраны 6 коммитов (сайт, API; P0)»
        // не должно резаться посреди скобки.
        var depth = 0
        var cut: Int? = null
        for (i in plain.indices) {
            when (plain[i]) {
                '(', '«', '[' -> depth++
                ')', '»', ']' -> depth = (depth - 1).coerceAtLeast(0)
            }
            if (depth == 0 && i > 0 && listOf(": ", ". ", " — ", "; ").any { plain.startsWith(it, i) }) {
                cut = i
                break
            }
        }
        val head = (if (cut != null) plain.substring(0, cut) else plain).trim().trimEnd('.', ':', ';')
        if (head.length <= 90) return head
        var short = head.take(90).substringBeforeLast(' ')
        // Обрезали посреди скобки — режем до неё, чтобы не оставлять «(сайт, API…».
        val open = short.lastIndexOf('(')
        if (open > 20 && short.indexOf(')', open) < 0) short = short.substring(0, open)
        return short.trimEnd(',', ' ', ';') + "…"
    }

    /**
     * Разобрать запись-файл дня заново кодом (после правки текста или по кнопке
     * «Переобработать»). true — разобрано; false — разделов нет, решает вызывающий (ИИ/черновик).
     * ИИ здесь не участвует: правка записи конфиденциального проекта не уходит в Groq.
     */
    fun restructure(userId: UUID, entry: EntryDto): Boolean {
        if (entry.source != SOURCE_TYPE) return false
        val structured = toStructured(parse(entry.rawText)) ?: return false
        val id = UUID.fromString(entry.id)
        EntryStructuredRepository.save(id, structured, ImportedEntries.PARSER_MODEL)
        EntryRepository.markAiDone(id)
        return true
    }

    /**
     * id записи: пользователь + проект + день. Один день — одна запись, повторный импорт
     * того же дня попадает в неё же. Проект в ключе — чтобы файлы дня ДВУХ разных работ
     * за одну дату не затирали друг друга.
     */
    fun entryIdFor(userId: UUID, projectId: UUID?, day: LocalDate): UUID =
        UUID.nameUUIDFromBytes("devlog-day-file:$userId:${projectId ?: "-"}:$day".toByteArray())

    /** Импортировать набор файлов: каждому — свой итог (создан/обновлён/без изменений/ошибка). */
    fun import(userId: UUID, files: List<DayFileDto>, projectId: UUID?, overwrite: Boolean = false): DayFilesImportResultDto {
        val results = mutableListOf<DayFileResultDto>()
        val seenDays = mutableSetOf<LocalDate>()

        for (file in files) {
            val name = file.name.trim().substringAfterLast('/').substringAfterLast('\\').take(200)
            val text = file.text.replace("\r\n", "\n").trim()
            fun fail(message: String) = results.add(DayFileResultDto(name, status = "error", message = message))

            if (text.isEmpty()) { fail("Файл пуст"); continue }
            if (text.length > MAX_RAW_TEXT_LENGTH) {
                fail("Файл длиннее $MAX_RAW_TEXT_LENGTH символов — DevLog столько в одну запись не принимает")
                continue
            }
            val day = dateFor(name, text)
            if (day == null) { fail("Нет даты ни в имени файла (ГГГГ-ММ-ДД.md), ни в заголовке"); continue }
            if (!seenDays.add(day)) { fail("Второй файл за $day в одной загрузке — оставлен первый"); continue }

            val structured = toStructured(parse(text))
            val outcome = ImportedEntries.upsert(
                userId,
                ImportedEntries.Prepared(
                    id = entryIdFor(userId, projectId, day),
                    input = ValidEntryInput(
                        occurredOn = day,
                        rawText = text,
                        projectId = projectId,
                        sourceType = SOURCE_TYPE,
                        timeSpentMin = null,
                    ),
                    source = "$SOURCE_TYPE:$name",
                    structured = structured,
                ),
                overwrite = overwrite,
            )
            results.add(
                DayFileResultDto(
                    name = name,
                    date = day.toString(),
                    status = outcome.name.lowercase(),
                    parsedByCode = structured != null,
                    message = when (outcome) {
                        ImportedEntries.Outcome.EDITED -> "Запись правили в DevLog — не перезаписываю (заменить можно флажком «Перезаписать»)"
                        ImportedEntries.Outcome.DELETED -> "Запись за этот день удалена в DevLog — не восстанавливаю (можно флажком «Перезаписать»)"
                        else -> null
                    },
                ),
            )
        }
        return DayFilesImportResultDto(
            created = results.count { it.status == "created" },
            updated = results.count { it.status == "updated" },
            unchanged = results.count { it.status == "unchanged" },
            failed = results.count { it.status == "error" },
            files = results,
        )
    }
}
