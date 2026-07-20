package io.github.vihrea1337.devlog

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Доступ к таблице entry_structured (результат ИИ). Массивы шагов/решений/тегов лежат
 * JSON-строкой в text-колонках — здесь мы их кодируем при записи и декодируем при чтении.
 */
object EntryStructuredRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /** Сохранить (перезаписать) структуру для записи. Связь один-к-одному, поэтому сначала чистим. */
    fun save(entryId: UUID, dto: StructuredDto, model: String) = transaction {
        EntryStructured.deleteWhere { EntryStructured.entryId eq entryId }
        EntryStructured.insert {
            it[EntryStructured.entryId] = entryId
            it[summary] = dto.summary
            it[steps] = json.encodeToString(dto.steps)
            it[decisions] = json.encodeToString(dto.decisions)
            it[problems] = json.encodeToString(dto.problems)
            it[outcome] = dto.outcome
            it[tags] = json.encodeToString(dto.tags)
            it[aiModel] = model
            it[processedAt] = Instant.now()
        }
        Unit
    }

    fun forEntry(entryId: UUID): StructuredDto? = transaction {
        EntryStructured.selectAll().where { EntryStructured.entryId eq entryId }
            .map { it.toDto() }
            .singleOrNull()
    }

    /** Структуры для набора записей одним запросом: entryId → StructuredDto. */
    fun forEntries(ids: List<UUID>): Map<UUID, StructuredDto> = transaction {
        if (ids.isEmpty()) return@transaction emptyMap()
        EntryStructured.selectAll().where { EntryStructured.entryId inList ids }
            .associate { it[EntryStructured.entryId] to it.toDto() }
    }

    private fun decodeList(raw: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw) }.getOrElse { emptyList() }

    private fun ResultRow.toDto() = StructuredDto(
        summary = this[EntryStructured.summary],
        steps = decodeList(this[EntryStructured.steps]),
        decisions = decodeList(this[EntryStructured.decisions]),
        problems = decodeList(this[EntryStructured.problems]),
        outcome = this[EntryStructured.outcome],
        tags = decodeList(this[EntryStructured.tags]),
    )
}
