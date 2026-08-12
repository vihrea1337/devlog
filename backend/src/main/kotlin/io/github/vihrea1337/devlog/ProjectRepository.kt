package io.github.vihrea1337.devlog

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Доступ к таблице projects. Как и записи — каждый метод фильтрует по userId (изоляция):
 * пользователь видит и меняет только свои проекты.
 *
 * Проект — это группировка записей (например, по клиенту или продукту) плюс "выключатель ИИ"
 * (aiEnabled): для конфиденциальной работы можно запретить отправку текста в ИИ.
 */
object ProjectRepository {

    /** Список активных (не архивных) проектов пользователя, старые сверху. */
    fun list(userId: UUID): List<ProjectDto> = transaction {
        Projects.selectAll()
            .where { (Projects.userId eq userId) and (Projects.archived eq false) }
            .orderBy(Projects.createdAt to SortOrder.ASC)
            .map { it.toDto() }
    }

    fun getById(userId: UUID, id: UUID): ProjectDto? = transaction { findInTx(userId, id) }

    fun create(userId: UUID, body: NewProject): ProjectDto = transaction {
        val newId = UUID.randomUUID()
        Projects.insert {
            it[id] = newId
            it[Projects.userId] = userId
            it[name] = body.name.trim()
            it[color] = body.color?.trim()?.ifBlank { null }
            it[aiEnabled] = body.aiEnabled
            it[archived] = false
            it[createdAt] = Instant.now()
        }
        findInTx(userId, newId)!!
    }

    /**
     * Частичная правка: меняем только переданные (не-null) поля.
     *
     * Поля сначала кладём в локальные переменные: DTO теперь живёт в общем модуле,
     * а через границу модуля Kotlin не делает умное приведение типов (`body.name != null`
     * не превращает `String?` в `String`).
     */
    fun update(userId: UUID, id: UUID, body: UpdateProject): ProjectDto? = transaction {
        val newName = body.name
        val newColor = body.color
        val newAiEnabled = body.aiEnabled
        val newArchived = body.archived
        val changed = Projects.update({ (Projects.id eq id) and (Projects.userId eq userId) }) {
            if (newName != null) it[name] = newName.trim()
            if (newColor != null) it[color] = newColor.trim().ifBlank { null }
            if (newAiEnabled != null) it[aiEnabled] = newAiEnabled
            if (newArchived != null) it[archived] = newArchived
        }
        if (changed == 0) null else findInTx(userId, id)
    }

    /** Удаление проекта. Записи проекта НЕ удаляются — их project_id обнуляется (SET_NULL в схеме). */
    fun delete(userId: UUID, id: UUID): Boolean = transaction {
        Projects.deleteWhere { (Projects.id eq id) and (Projects.userId eq userId) } > 0
    }

    /**
     * Включён ли ИИ для проекта, к которому привязана запись. Запись без проекта — ИИ включён.
     * Чужой/несуществующий проект — считаем включённым (изоляцию проверяет вызывающий код).
     */
    fun isAiEnabled(userId: UUID, projectId: UUID?): Boolean {
        if (projectId == null) return true
        return transaction {
            Projects.selectAll()
                .where { (Projects.id eq projectId) and (Projects.userId eq userId) }
                .map { it[Projects.aiEnabled] }
                .singleOrNull() ?: true
        }
    }

    private fun findInTx(userId: UUID, id: UUID): ProjectDto? =
        Projects.selectAll().where { (Projects.id eq id) and (Projects.userId eq userId) }
            .map { it.toDto() }
            .singleOrNull()

    private fun ResultRow.toDto() = ProjectDto(
        id = this[Projects.id].toString(),
        name = this[Projects.name],
        color = this[Projects.color],
        aiEnabled = this[Projects.aiEnabled],
        archived = this[Projects.archived],
        createdAt = this[Projects.createdAt].toString(),
    )
}
