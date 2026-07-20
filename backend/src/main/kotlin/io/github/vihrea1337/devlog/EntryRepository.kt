package io.github.vihrea1337.devlog

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
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
    fun list(userId: UUID, from: LocalDate?, to: LocalDate?, projectId: UUID?): List<EntryDto> = transaction {
        val query = Entries.selectAll().where { Entries.userId eq userId }
        if (from != null) query.andWhere { Entries.occurredOn greaterEq from }
        if (to != null) query.andWhere { Entries.occurredOn lessEq to }
        if (projectId != null) query.andWhere { Entries.projectId eq projectId }
        query.orderBy(Entries.occurredOn to SortOrder.DESC, Entries.createdAt to SortOrder.DESC)
            .map { it.toEntryDto() }
    }

    fun getById(userId: UUID, id: UUID): EntryDto? = transaction {
        findInTx(userId, id)
    }

    fun create(userId: UUID, body: NewEntry): EntryDto = transaction {
        val newId = UUID.randomUUID()
        val now = Instant.now()
        Entries.insert {
            it[id] = newId
            it[Entries.userId] = userId
            it[projectId] = body.projectId?.let(UUID::fromString)
            it[occurredOn] = LocalDate.parse(body.occurredOn)
            it[rawText] = body.rawText
            it[sourceType] = body.sourceType
            it[status] = "queued"
            it[timeSpentMin] = body.timeSpentMin
            it[createdAt] = now
            it[updatedAt] = now
        }
        findInTx(userId, newId)!!
    }

    /** Частичная правка: меняем только переданные (не-null) поля. */
    fun update(userId: UUID, id: UUID, body: UpdateEntry): EntryDto? = transaction {
        val changed = Entries.update({ (Entries.id eq id) and (Entries.userId eq userId) }) {
            if (body.rawText != null) it[rawText] = body.rawText
            if (body.occurredOn != null) it[occurredOn] = LocalDate.parse(body.occurredOn)
            if (body.timeSpentMin != null) it[timeSpentMin] = body.timeSpentMin
            if (body.projectId != null) it[projectId] = UUID.fromString(body.projectId)
            it[updatedAt] = Instant.now()
        }
        if (changed == 0) null else findInTx(userId, id)
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
    )
}
