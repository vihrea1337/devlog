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
import java.time.LocalDate
import java.util.UUID

/** Доступ к таблице reports. Все методы (кроме публичного getByShareToken) фильтруют по userId. */
object ReportRepository {

    fun create(
        userId: UUID,
        projectId: UUID?,
        title: String,
        from: LocalDate,
        to: LocalDate,
        format: String,
        contentMd: String,
    ): ReportDto = transaction {
        val id = UUID.randomUUID()
        val now = Instant.now()
        Reports.insert {
            it[Reports.id] = id
            it[Reports.userId] = userId
            it[Reports.projectId] = projectId
            it[Reports.title] = title
            it[periodStart] = from
            it[periodEnd] = to
            it[Reports.format] = format
            it[Reports.contentMd] = contentMd
            it[createdAt] = now
        }
        ReportDto(
            id = id.toString(),
            projectId = projectId?.toString(),
            title = title,
            periodStart = from.toString(),
            periodEnd = to.toString(),
            format = format,
            contentMd = contentMd,
            shareToken = null,
            createdAt = now.toString(),
            contentHtml = Markdown.toHtml(contentMd),
        )
    }

    fun list(userId: UUID): List<ReportDto> = transaction {
        Reports.selectAll().where { Reports.userId eq userId }
            .orderBy(Reports.createdAt to SortOrder.DESC)
            .map { it.toDto() }
    }

    fun getById(userId: UUID, id: UUID): ReportDto? = transaction {
        Reports.selectAll().where { (Reports.id eq id) and (Reports.userId eq userId) }
            .map { it.toDto() }
            .singleOrNull()
    }

    fun setShareToken(userId: UUID, id: UUID, token: String?): Boolean = transaction {
        Reports.update({ (Reports.id eq id) and (Reports.userId eq userId) }) {
            it[shareToken] = token
        } > 0
    }

    /**
     * Заменить текст отчёта. Нужно, чтобы человек мог поправить формулировки ИИ
     * ПЕРЕД отправкой работодателю: отчёт о своей работе не отдают не глядя.
     */
    fun updateContent(userId: UUID, id: UUID, contentMd: String, title: String?): ReportDto? = transaction {
        val changed = Reports.update({ (Reports.id eq id) and (Reports.userId eq userId) }) {
            it[Reports.contentMd] = contentMd
            if (title != null) it[Reports.title] = title
        }
        if (changed == 0) null
        else Reports.selectAll().where { Reports.id eq id }.map { it.toDto() }.singleOrNull()
    }

    fun delete(userId: UUID, id: UUID): Boolean = transaction {
        Reports.deleteWhere { (Reports.id eq id) and (Reports.userId eq userId) } > 0
    }

    /** Публичный доступ по токену ссылки — БЕЗ фильтра по пользователю (ссылку знает получатель). */
    fun getByShareToken(token: String): ReportDto? = transaction {
        Reports.selectAll().where { Reports.shareToken eq token }
            .map { it.toDto() }
            .singleOrNull()
    }

    /**
     * Строка таблицы → DTO. `contentHtml` считаем здесь, а не в самом DTO: общий модуль
     * `shared` держит только данные, конвертер Markdown живёт на сервере.
     */
    private fun ResultRow.toDto(): ReportDto {
        val md = this[Reports.contentMd]
        return ReportDto(
            id = this[Reports.id].toString(),
            projectId = this[Reports.projectId]?.toString(),
            title = this[Reports.title],
            periodStart = this[Reports.periodStart].toString(),
            periodEnd = this[Reports.periodEnd].toString(),
            format = this[Reports.format],
            contentMd = md,
            shareToken = this[Reports.shareToken],
            createdAt = this[Reports.createdAt].toString(),
            contentHtml = Markdown.toHtml(md),
        )
    }
}
