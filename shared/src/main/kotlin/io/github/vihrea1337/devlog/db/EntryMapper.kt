package io.github.vihrea1337.devlog.db

import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.StructuredDto
import kotlinx.serialization.json.Json

/**
 * Превращения между EntryDto (то, что летает по сети и знает про поле "deleted")
 * и EntryEntity (строка локальной таблицы — удалённые записи в ней просто не хранятся,
 * поэтому у сущности такого поля нет).
 */

private val json = Json { ignoreUnknownKeys = true }

/** Строка БД → DTO для показа в UI. structuredJson разбираем обратно в StructuredDto. */
fun EntryEntity.toDto(): EntryDto = EntryDto(
    id = id,
    projectId = projectId,
    occurredOn = occurredOn,
    rawText = rawText,
    source = source,
    status = status,
    timeSpentMin = timeSpentMin?.toInt(),
    createdAt = createdAt,
    updatedAt = updatedAt,
    structured = structuredJson?.let { raw -> runCatching { json.decodeFromString<StructuredDto>(raw) }.getOrNull() },
    aiError = aiError,
    deleted = false,
)

/** DTO с сервера → аргументы для upsert-запроса (structured сериализуем в JSON-строку). */
fun EntryDto.toEntityJson(): String? = structured?.let { json.encodeToString(StructuredDto.serializer(), it) }
