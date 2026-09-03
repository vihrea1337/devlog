package io.github.vihrea1337.devlog

import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * Импорт произвольного текстового лога (переписки, заметок, выгрузки чата) как дневника.
 *
 * Почему общий текст, а не разбор конкретного формата Claude Code — см. `docs/architecture.md`,
 * раздел «Решение: импорт из Claude Code». Коротко: у сессий Claude Code нет стабильного
 * задокументированного формата экспорта, парсер под него был бы догадкой, которая молча
 * сломается при следующем обновлении CLI. Вместо этого текст режется на дни по датам вида
 * ГГГГ-ММ-ДД, встреченным в строках, — работает для транскрипта Claude Code (у реплик обычно
 * есть ISO-таймстемпы), но и для любого другого текста с датами.
 *
 * Один день — одна запись дневника, дальше её разбирает ИИ ровно так же, как заметку руками
 * (тот же паттерн, что и у импорта коммитов GitHub, см. GithubImport.kt).
 */

private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

/** Результат импорта для ответа клиенту. */
data class TextImportResult(val days: Int, val linesMatched: Int, val created: Int, val skipped: Int)

object TextImporter {

    /**
     * Разбить текст на куски по дням. Чистая функция (без сети и БД) — легко тестировать.
     *
     * Правило: в каждой непустой строке ищем первую подстроку ГГГГ-ММ-ДД; если она разбирается
     * в настоящую дату — эта дата становится "текущим днём". Строка добавляется к текущему дню
     * (даже если сама даты не содержит — так продолжение многострочной реплики после строки
     * с таймстемпом попадает к тому же дню). Строки до первой найденной в тексте даты
     * отбрасываются — без даты непонятно, к какому дню их отнести, а гадать не будем.
     */
    fun splitByDay(text: String): Map<LocalDate, List<String>> {
        val byDay = sortedMapOf<LocalDate, MutableList<String>>()
        var currentDay: LocalDate? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = DATE_PATTERN.find(line)?.value
            val parsedDay = match?.let { parseDateOrNull(it) }
            if (parsedDay != null) currentDay = parsedDay
            val day = currentDay ?: continue
            byDay.getOrPut(day) { mutableListOf() }.add(line)
        }
        return byDay
    }

    /** Текст записи из строк одного дня — сырьё, которое дальше разберёт ИИ. */
    fun buildEntryText(day: LocalDate, lines: List<String>): String {
        val header = "Импорт текстового лога за $day (${lines.size} строк):"
        return (header + "\n" + lines.joinToString("\n")).take(MAX_RAW_TEXT_LENGTH)
    }

    /** Короткий отпечаток содержимого дня — часть id записи, а не секрет, коллизии не критичны. */
    private fun fingerprint(lines: List<String>): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Один и тот же день ОДНОГО И ТОГО ЖЕ лога должен давать одну и ту же запись при повторном
     * импорте — поэтому id зависит не только от дня, но и от содержимого (в отличие от
     * GitHub-импорта, где ключ — только пользователь+день: там источник один и тот же по смыслу
     * при любом перезапросе). Здесь источник — что угодно, что вставит пользователь, поэтому:
     * тот же текст повторно → тот же id → пропускаем; ДРУГОЙ лог, случайно попавший на тот же
     * календарный день, → другой id → ложится отдельной записью, а не затирает предыдущую.
     */
    fun entryIdFor(userId: UUID, day: LocalDate, lines: List<String>): UUID =
        UUID.nameUUIDFromBytes("devlog-text-import:$userId:$day:${fingerprint(lines)}".toByteArray())

    /** Импортировать текст в записи дневника. Уже существующие (тот же день+контент) не трогаем. */
    fun import(userId: UUID, text: String, projectId: UUID?): TextImportResult {
        val byDay = splitByDay(text)
        var created = 0
        var skipped = 0
        var linesMatched = 0

        for ((day, lines) in byDay) {
            linesMatched += lines.size
            val entryId = entryIdFor(userId, day, lines)
            if (EntryRepository.getById(userId, entryId) != null) {
                skipped++
                continue
            }
            EntryRepository.create(
                userId,
                ValidEntryInput(
                    occurredOn = day,
                    rawText = buildEntryText(day, lines),
                    projectId = projectId,
                    sourceType = "text-import",
                    timeSpentMin = null,
                    id = entryId,
                ),
            )
            created++
        }
        return TextImportResult(days = byDay.size, linesMatched = linesMatched, created = created, skipped = skipped)
    }
}
