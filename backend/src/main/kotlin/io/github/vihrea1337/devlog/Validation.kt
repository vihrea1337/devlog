package io.github.vihrea1337.devlog

import java.time.LocalDate
import java.util.UUID

/**
 * Разбор и проверка того, что пришло от клиента.
 *
 * Зачем отдельно: `LocalDate.parse("вчера")` и `UUID.fromString("abc")` кидают исключение,
 * и клиент получал 500 «внутренняя ошибка сервера» — как будто виноват сервер, хотя данные
 * прислал неверные он сам. Правильный ответ на кривой запрос — 400 с понятным текстом.
 */

/** Потолок длины заметки: защищает базу и лимит обращений к ИИ от вставки на мегабайт. */
const val MAX_RAW_TEXT_LENGTH = 20_000

/** Потолок для текста отчёта: он собирается из многих записей, поэтому больше. */
const val MAX_REPORT_LENGTH = 200_000

fun parseDateOrNull(raw: String): LocalDate? = runCatching { LocalDate.parse(raw) }.getOrNull()

fun parseUuidOrNull(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()

/** Проверенные поля новой записи — репозиторий получает готовые типы, а не строки. */
data class ValidEntryInput(
    val occurredOn: LocalDate,
    val rawText: String,
    val projectId: UUID?,
    val sourceType: String,
    val timeSpentMin: Int?,
    /** id от клиента (защита от дублей при повторной отправке); null — придумает сервер. */
    val id: UUID? = null,
)

/** Что менять в записи. null = поле не трогаем; projectId = "" означает «убрать из проекта». */
data class ValidEntryPatch(
    val rawText: String? = null,
    val occurredOn: LocalDate? = null,
    val timeSpentMin: Int? = null,
    val projectId: UUID? = null,
    val clearProject: Boolean = false,
)

/** Результат проверки: либо готовые данные, либо текст ошибки для ответа 400. */
sealed interface Validated<out T> {
    data class Ok<T>(val value: T) : Validated<T>
    data class Invalid(val message: String) : Validated<Nothing>
}

/**
 * Проверить тело POST /api/entries. Владение проектом проверяется здесь же: без этого
 * можно было привязать свою запись к ЧУЖОМУ проекту и обойти его выключатель ИИ
 * (для чужого проекта проверка «ИИ разрешён?» отвечала «да»).
 */
fun validateNewEntry(userId: UUID, body: NewEntry): Validated<ValidEntryInput> {
    val text = body.rawText.trim()
    if (text.isEmpty()) return Validated.Invalid("Текст записи пуст")
    if (text.length > MAX_RAW_TEXT_LENGTH) {
        return Validated.Invalid("Заметка длиннее $MAX_RAW_TEXT_LENGTH символов — разбейте её на несколько")
    }
    val date = parseDateOrNull(body.occurredOn)
        ?: return Validated.Invalid("Некорректная дата работы, нужен формат ГГГГ-ММ-ДД")

    // Значения берём в локальные переменные: DTO лежит в общем модуле, а через границу
    // модуля Kotlin не делает умное приведение типов (проверка на null «не запоминается»).
    val rawProjectId = body.projectId
    val projectId = when {
        rawProjectId.isNullOrBlank() -> null
        else -> parseUuidOrNull(rawProjectId) ?: return Validated.Invalid("Некорректный id проекта")
    }
    if (projectId != null && ProjectRepository.getById(userId, projectId) == null) {
        return Validated.Invalid("Проект не найден")
    }
    val minutes = body.timeSpentMin
    if (minutes != null && minutes < 0) {
        return Validated.Invalid("Потраченное время не может быть отрицательным")
    }

    val rawId = body.id
    val clientId = when {
        rawId.isNullOrBlank() -> null
        else -> parseUuidOrNull(rawId) ?: return Validated.Invalid("Некорректный id записи")
    }
    if (clientId != null && EntryRepository.existsOwnedByOther(userId, clientId)) {
        return Validated.Invalid("Запись с таким id уже занята")
    }

    return Validated.Ok(
        ValidEntryInput(
            occurredOn = date,
            rawText = text,
            projectId = projectId,
            sourceType = body.sourceType.trim().ifBlank { "manual" }.take(20),
            timeSpentMin = minutes,
            id = clientId,
        ),
    )
}

/** То же для PUT /api/entries/{id}: меняем только присланные поля. */
fun validateEntryPatch(userId: UUID, body: UpdateEntry): Validated<ValidEntryPatch> {
    val text = body.rawText?.trim()
    if (text != null) {
        if (text.isEmpty()) return Validated.Invalid("Текст записи пуст")
        if (text.length > MAX_RAW_TEXT_LENGTH) {
            return Validated.Invalid("Заметка длиннее $MAX_RAW_TEXT_LENGTH символов — разбейте её на несколько")
        }
    }
    val date = body.occurredOn?.let {
        parseDateOrNull(it) ?: return Validated.Invalid("Некорректная дата работы, нужен формат ГГГГ-ММ-ДД")
    }
    val minutes = body.timeSpentMin
    if (minutes != null && minutes < 0) {
        return Validated.Invalid("Потраченное время не может быть отрицательным")
    }

    // Пустая строка — способ убрать запись из проекта (null означает «не трогать поле»).
    val rawProjectId = body.projectId
    val clearProject = rawProjectId != null && rawProjectId.isBlank()
    val projectId = when {
        rawProjectId.isNullOrBlank() -> null
        else -> parseUuidOrNull(rawProjectId) ?: return Validated.Invalid("Некорректный id проекта")
    }
    if (projectId != null && ProjectRepository.getById(userId, projectId) == null) {
        return Validated.Invalid("Проект не найден")
    }

    return Validated.Ok(ValidEntryPatch(text, date, minutes, projectId, clearProject))
}
