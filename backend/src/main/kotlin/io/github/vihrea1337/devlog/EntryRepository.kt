package io.github.vihrea1337.devlog

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Доступ к таблице entries. КАЖДЫЙ метод принимает userId и фильтрует по нему —
 * так пользователь физически не может достать чужую запись (изоляция под многопользовательность).
 * Пока отдаём EntryDto без структурированной части (её заполнит ИИ на шаге 3б).
 */
object EntryRepository {

    /** Список записей пользователя с необязательными фильтрами: период (from/to) и проект. */
    fun list(userId: UUID, from: LocalDate?, to: LocalDate?, projectId: UUID?): List<EntryDto> {
        val base = transaction {
            val query = Entries.selectAll().where { Entries.userId eq userId }
            if (from != null) query.andWhere { Entries.occurredOn greaterEq from }
            if (to != null) query.andWhere { Entries.occurredOn lessEq to }
            if (projectId != null) query.andWhere { Entries.projectId eq projectId }
            query.orderBy(Entries.occurredOn to SortOrder.DESC, Entries.createdAt to SortOrder.DESC)
                .map { it.toEntryDto() }
        }
        if (base.isEmpty()) return base
        // Подтянуть структуру ИИ одним запросом и приклеить к записям.
        val structured = EntryStructuredRepository.forEntries(base.map { UUID.fromString(it.id) })
        return base.map { it.copy(structured = structured[UUID.fromString(it.id)]) }
    }

    fun getById(userId: UUID, id: UUID): EntryDto? {
        val base = transaction { findInTx(userId, id) } ?: return null
        return base.copy(structured = EntryStructuredRepository.forEntry(id))
    }

    /** Создать запись из уже проверенных данных (разбор и проверки — в Validation.kt). */
    fun create(userId: UUID, input: ValidEntryInput): EntryDto = transaction {
        val newId = UUID.randomUUID()
        val now = Instant.now()
        Entries.insert {
            it[id] = newId
            it[Entries.userId] = userId
            it[projectId] = input.projectId
            it[occurredOn] = input.occurredOn
            it[rawText] = input.rawText
            it[sourceType] = input.sourceType
            it[status] = "queued"
            it[timeSpentMin] = input.timeSpentMin
            it[createdAt] = now
            it[updatedAt] = now
        }
        findInTx(userId, newId)!!
    }

    /** Частичная правка: меняем только переданные поля. */
    fun update(userId: UUID, id: UUID, patch: ValidEntryPatch): EntryDto? {
        val base = transaction {
            val changed = Entries.update({ (Entries.id eq id) and (Entries.userId eq userId) }) {
                if (patch.rawText != null) it[rawText] = patch.rawText
                if (patch.occurredOn != null) it[occurredOn] = patch.occurredOn
                if (patch.timeSpentMin != null) it[timeSpentMin] = patch.timeSpentMin
                if (patch.clearProject) it[projectId] = null
                else if (patch.projectId != null) it[projectId] = patch.projectId
                it[updatedAt] = Instant.now()
            }
            if (changed == 0) null else findInTx(userId, id)
        } ?: return null
        return base.copy(structured = EntryStructuredRepository.forEntry(id))
    }

    fun delete(userId: UUID, id: UUID): Boolean = transaction {
        Entries.deleteWhere { (Entries.id eq id) and (Entries.userId eq userId) } > 0
    }

    /** Сменить статус записи (для постановки в очередь ИИ и перезапуска обработки). */
    fun setStatus(userId: UUID, id: UUID, newStatus: String): Boolean = transaction {
        Entries.update({ (Entries.id eq id) and (Entries.userId eq userId) }) {
            it[status] = newStatus
            it[updatedAt] = Instant.now()
        } > 0
    }

    // --- Очередь обработки ИИ (пользуется AiWorker) ---

    /** Запись, взятая воркером в обработку. */
    data class AiJob(val id: UUID, val userId: UUID, val rawText: String, val attempt: Int)

    /**
     * Взять следующую запись в обработку и сразу пометить её как processing.
     *
     * Берём: записи в очереди (queued) и «зависшие» — те, что помечены processing давно
     * (значит, сервер перезапустили посреди обработки, и продолжать её некому).
     * Записи, у которых уже кончились попытки, и пользователей из [skipUsers]
     * (у них исчерпан суточный лимит ИИ) пропускаем.
     *
     * Захват атомарный: обновляем строку с условием «статус и число попыток всё ещё те,
     * что мы видели». Если условие не сошлось — запись успел забрать кто-то другой,
     * пробуем следующую. Так двум воркерам (или двум серверам) не достанется одна запись.
     */
    fun claimNextAiJob(stuckBefore: Instant, maxAttempts: Int, skipUsers: Set<UUID> = emptySet()): AiJob? =
        transaction {
            val candidates = Entries
                .selectAll()
                .where {
                    ((Entries.status eq "queued") or
                        ((Entries.status eq "processing") and (Entries.aiStartedAt less stuckBefore))) and
                        (Entries.aiAttempts less maxAttempts)
                }
                .orderBy(Entries.createdAt to SortOrder.ASC)
                .limit(20)
                .map {
                    Candidate(
                        id = it[Entries.id],
                        userId = it[Entries.userId],
                        rawText = it[Entries.rawText],
                        status = it[Entries.status],
                        attempts = it[Entries.aiAttempts],
                    )
                }
                .filterNot { it.userId in skipUsers }

            for (c in candidates) {
                val now = Instant.now()
                val claimed = Entries.update({
                    (Entries.id eq c.id) and (Entries.status eq c.status) and (Entries.aiAttempts eq c.attempts)
                }) {
                    it[status] = "processing"
                    it[aiAttempts] = c.attempts + 1
                    it[aiStartedAt] = now
                    it[updatedAt] = now
                }
                if (claimed > 0) return@transaction AiJob(c.id, c.userId, c.rawText, c.attempts + 1)
            }
            null
        }

    private data class Candidate(
        val id: UUID,
        val userId: UUID,
        val rawText: String,
        val status: String,
        val attempts: Int,
    )

    /** Обработка удалась: снимаем ошибку и переводим запись в structured. */
    fun markAiDone(id: UUID): Boolean = transaction {
        Entries.update({ Entries.id eq id }) {
            it[status] = "structured"
            it[aiError] = null
            it[updatedAt] = Instant.now()
        } > 0
    }

    /**
     * Обработка не удалась. [retry] = true → возвращаем в очередь (воркер попробует ещё раз),
     * false → окончательно failed. Текст ошибки сохраняем в обоих случаях: пользователь должен
     * видеть, почему не вышло, а не просто «ошибка ИИ».
     */
    fun markAiFailed(id: UUID, error: String, retry: Boolean): Boolean = transaction {
        Entries.update({ Entries.id eq id }) {
            it[status] = if (retry) "queued" else "failed"
            it[aiError] = error.take(500)
            it[updatedAt] = Instant.now()
        } > 0
    }

    /** Вернуть запись в очередь, не тратя попытку (например, упёрлись в суточный лимит ИИ). */
    fun releaseAiJob(id: UUID, attempt: Int, error: String?): Boolean = transaction {
        Entries.update({ Entries.id eq id }) {
            it[status] = "queued"
            it[aiAttempts] = (attempt - 1).coerceAtLeast(0)
            it[aiError] = error?.take(500)
            it[updatedAt] = Instant.now()
        } > 0
    }

    /** Поставить запись в очередь заново «с чистого листа» (кнопка «Переобработать»). */
    fun requeueForAi(userId: UUID, id: UUID): Boolean = transaction {
        Entries.update({ (Entries.id eq id) and (Entries.userId eq userId) }) {
            it[status] = "queued"
            it[aiAttempts] = 0
            it[aiError] = null
            it[aiStartedAt] = null
            it[updatedAt] = Instant.now()
        } > 0
    }

    private fun findInTx(userId: UUID, id: UUID): EntryDto? =
        Entries.selectAll().where { (Entries.id eq id) and (Entries.userId eq userId) }
            .map { it.toEntryDto() }
            .singleOrNull()

    private fun ResultRow.toEntryDto() = EntryDto(
        id = this[Entries.id].toString(),
        projectId = this[Entries.projectId]?.toString(),
        occurredOn = this[Entries.occurredOn].toString(),
        rawText = this[Entries.rawText],
        source = this[Entries.sourceType],
        status = this[Entries.status],
        timeSpentMin = this[Entries.timeSpentMin],
        createdAt = this[Entries.createdAt].toString(),
        updatedAt = this[Entries.updatedAt].toString(),
        structured = null,
        aiError = this[Entries.aiError],
    )
}
