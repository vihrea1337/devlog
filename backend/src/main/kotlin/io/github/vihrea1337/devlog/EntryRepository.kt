package io.github.vihrea1337.devlog

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
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

    /**
     * Список записей пользователя с необязательными фильтрами: период (from/to), проект,
     * поиск по тексту [query] и тег [tag].
     *
     * Поиск смотрит и в сырой текст, и в то, что извлёк ИИ (суть и теги): человек ищет
     * «виджет», а слово может быть только в summary — иначе поиск выглядел бы сломанным.
     */
    fun list(
        userId: UUID,
        from: LocalDate?,
        to: LocalDate?,
        projectId: UUID?,
        query: String? = null,
        tag: String? = null,
    ): List<EntryDto> {
        val needle = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val tagNeedle = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val base = transaction {
            // Записи, у которых совпало в структуре от ИИ (суть или теги).
            val structuredHits: Set<UUID>? = when {
                needle != null -> EntryStructuredRepository.findEntriesMatching(needle)
                tagNeedle != null -> EntryStructuredRepository.findEntriesWithTag(tagNeedle)
                else -> null
            }

            // Удалённые записи (надгробия) в ленту не попадают — только в синхронизацию.
            val q = Entries.selectAll().where { (Entries.userId eq userId) and Entries.deletedAt.isNull() }
            if (from != null) q.andWhere { Entries.occurredOn greaterEq from }
            if (to != null) q.andWhere { Entries.occurredOn lessEq to }
            if (projectId != null) q.andWhere { Entries.projectId eq projectId }
            if (needle != null) {
                val pattern = "%" + needle.escapeLike() + "%"
                q.andWhere {
                    (Entries.rawText.lowerCase() like pattern) or
                        (Entries.id inList (structuredHits ?: emptySet()))
                }
            }
            // Тег живёт только в структуре от ИИ, поэтому фильтр — по найденным там id.
            if (tagNeedle != null) q.andWhere { Entries.id inList (structuredHits ?: emptySet()) }

            q.orderBy(Entries.occurredOn to SortOrder.DESC, Entries.createdAt to SortOrder.DESC)
                .map { it.toEntryDto() }
        }
        if (base.isEmpty()) return base
        // Подтянуть структуру ИИ одним запросом и приклеить к записям.
        val structured = EntryStructuredRepository.forEntries(base.map { UUID.fromString(it.id) })
        return base.map { it.copy(structured = structured[UUID.fromString(it.id)]) }
    }

    /**
     * Существует ли запись с таким id у ДРУГОГО пользователя. UUID случайные, так что
     * в жизни это столкновение почти невозможно — проверка нужна, чтобы на подобранный
     * чужой id ответить понятной ошибкой, а не падением на нарушении первичного ключа.
     */
    fun existsOwnedByOther(userId: UUID, id: UUID): Boolean = transaction {
        Entries.selectAll()
            .where { (Entries.id eq id) and (Entries.userId neq userId) }
            .limit(1)
            .any()
    }

    fun getById(userId: UUID, id: UUID): EntryDto? {
        val base = transaction { findInTx(userId, id) } ?: return null
        return base.copy(structured = EntryStructuredRepository.forEntry(id))
    }

    /**
     * Создать запись из уже проверенных данных (разбор и проверки — в Validation.kt).
     *
     * Если клиент прислал свой id и запись с таким id уже есть, ничего не создаём и
     * возвращаем существующую: при плохой сети клиент повторяет отправку, и без этого
     * в дневнике появлялись бы дубли.
     */
    fun create(userId: UUID, input: ValidEntryInput): EntryDto = transaction {
        input.id?.let { requested ->
            findInTx(userId, requested, includeDeleted = true)?.let { return@transaction it }
        }
        val newId = input.id ?: UUID.randomUUID()
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

    /**
     * Удаление записи. Строку НЕ стираем: остаётся «надгробие» (проставлен `deleted_at`),
     * иначе второе устройство, которое было офлайн, никогда не узнает об удалении
     * и запись у него «воскреснет» при следующей синхронизации.
     *
     * При этом содержимое стираем сразу — и текст, и разбор ИИ: пользователь удалил
     * запись, хранить её текст незачем. Для синхронизации хватает id и времени.
     */
    fun delete(userId: UUID, id: UUID): Boolean = transaction {
        val now = Instant.now()
        val affected = Entries.update({
            (Entries.id eq id) and (Entries.userId eq userId) and Entries.deletedAt.isNull()
        }) {
            it[rawText] = ""
            it[sourceRef] = null
            it[aiError] = null
            it[status] = "deleted"
            it[deletedAt] = now
            it[updatedAt] = now
        }
        if (affected > 0) EntryStructured.deleteWhere { EntryStructured.entryId eq id }
        affected > 0
    }

    /**
     * Что изменилось после момента [since] — основа синхронизации клиентов.
     * Отдаём и обычные записи, и надгробия (у них `deleted = true`), по возрастанию
     * `updated_at`: клиент запоминает время последней полученной записи и в следующий раз
     * просит только то, что новее.
     */
    fun changesSince(userId: UUID, since: Instant?, limit: Int): List<EntryDto> {
        val base = transaction {
            val q = Entries.selectAll().where { Entries.userId eq userId }
            if (since != null) q.andWhere { Entries.updatedAt greater since }
            q.orderBy(Entries.updatedAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toEntryDto() }
        }
        if (base.isEmpty()) return base
        val structured = EntryStructuredRepository.forEntries(base.map { UUID.fromString(it.id) })
        return base.map { it.copy(structured = structured[UUID.fromString(it.id)]) }
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

    /**
     * Экранировать спецсимволы LIKE: `%` — «любой текст», `_` — «любой символ».
     * Без этого поиск по «100%» вернул бы вообще всё.
     */
    private fun String.escapeLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

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

    private fun findInTx(userId: UUID, id: UUID, includeDeleted: Boolean = false): EntryDto? =
        Entries.selectAll()
            .where {
                val own = (Entries.id eq id) and (Entries.userId eq userId)
                if (includeDeleted) own else own and Entries.deletedAt.isNull()
            }
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
        deleted = this[Entries.deletedAt] != null,
    )
}
