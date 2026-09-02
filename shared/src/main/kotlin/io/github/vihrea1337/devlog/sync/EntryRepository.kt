package io.github.vihrea1337.devlog.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.NewEntry
import io.github.vihrea1337.devlog.db.DevLogDatabase
import io.github.vihrea1337.devlog.db.EntryEntity
import io.github.vihrea1337.devlog.db.toDto
import io.github.vihrea1337.devlog.db.toEntityJson
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Единая точка доступа к записям для UI: читает из локальной SQLDelight-базы (мгновенно,
 * работает офлайн), умеет подтягивать изменения с сервера (since-протокол) и умеет
 * принимать изменения ОТ пользователя офлайн — через очередь [pendingOperation].
 *
 * Экран не должен сам решать, идти в сеть или нет — он вызывает [enqueueCreate]/[enqueueDelete]
 * (мгновенно, локально) и [sync] (когда угодно: pull-to-refresh, восстановление сети, таймер).
 * Всё, что уже лежит в базе, никуда не денется, если sync() упадёт из-за отсутствия сети.
 *
 * Все .sq-запросы по записям и по курсору синхронизации лежат в Entries.sq → `entriesQueries`;
 * очередь исходящих операций — в отдельном PendingOperations.sq → `pendingOperationsQueries`.
 */
class EntryRepository(
    private val database: DevLogDatabase,
    private val syncClient: DevLogSyncClient,
    private val ioContext: CoroutineContext = Dispatchers.Default,
    /** Подменяется в тестах, чтобы не зависеть от настоящих часов при проверке backoff. */
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val entryQueries = database.entriesQueries
    private val pendingQueries = database.pendingOperationsQueries
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * sync() и flushPending() трогают одну и ту же таблицу pendingOperation: без мьютекса
     * два параллельных вызова sync() могли бы оба прочитать один и тот же "due"-элемент
     * очереди и отправить его на сервер дважды, прежде чем первый успеет удалить запись
     * из очереди. Дубль создания безопасен (сервер идемпотентен по id), а вот два DELETE
     * подряд — уже гонка похуже, поэтому синхронизация целиком сериализована.
     */
    private val syncMutex = Mutex()

    /**
     * id записей, чей "create" прямо сейчас в полёте (запрос ушёл, ответа ещё нет).
     * Нужно enqueueDelete(): без этой отметки удаление, вызванное ровно в этот момент,
     * решило бы, что создание ещё не отправлялось, и просто стёрло бы операцию из очереди
     * локально — а когда ответ на самом деле пришедшего запроса всё равно прилетит,
     * applyRemoteEntry() тихо воскресит только что удалённую запись. ConcurrentHashMap.newKeySet,
     * а не обычный Set: enqueueDelete зовут из другой корутины/потока, а не из-под syncMutex.
     */
    private val inFlightCreateEntryIds = ConcurrentHashMap.newKeySet<String>()

    /** Вся лента из локального кэша, свежие сверху. Обновляется сама при любом sync(). */
    fun entries(): Flow<List<EntryDto>> =
        mapDto(entryQueries.selectAll().asFlow().mapToList(ioContext))

    /** Лента одного проекта из локального кэша. */
    fun entriesByProject(projectId: String): Flow<List<EntryDto>> =
        mapDto(entryQueries.selectByProject(projectId).asFlow().mapToList(ioContext))

    private fun mapDto(flow: Flow<List<EntryEntity>>): Flow<List<EntryDto>> =
        flow.map { rows -> rows.map { it.toDto() } }

    /**
     * Добавить запись офлайн-first: сразу появляется в ленте (статус "queued", как будто
     * уже отправлена), реальный POST на сервер уходит в очередь и уедет при ближайшем sync().
     *
     * newEntry.id ОБЯЗАН быть задан заранее вызывающим кодом (см. FeedViewModel.addEntry,
     * который уже генерирует UUID) — это тот же id, что уйдёт на сервер, и именно он даёт
     * идемпотентность: повторная отправка после сбоя не создаст вторую запись.
     */
    fun enqueueCreate(newEntry: NewEntry) {
        val id = requireNotNull(newEntry.id) {
            "newEntry.id должен быть сгенерирован заранее — иначе повторная отправка при " +
                "сбое сети создаст на сервере вторую запись"
        }
        val now = Instant.now().toString()
        entryQueries.upsert(
            id = id,
            projectId = newEntry.projectId,
            occurredOn = newEntry.occurredOn,
            rawText = newEntry.rawText,
            source = newEntry.sourceType,
            status = "queued",
            timeSpentMin = newEntry.timeSpentMin?.toLong(),
            createdAt = now,
            updatedAt = now,
            structuredJson = null,
            aiError = null,
        )
        pendingQueries.insertOperation(
            id = UUID.randomUUID().toString(),
            entryId = id,
            type = OP_CREATE,
            payloadJson = json.encodeToString(NewEntry.serializer(), newEntry),
            createdAtMillis = nowMillis(),
            attempts = 0,
            nextAttemptAtMillis = 0,
        )
    }

    /**
     * Удалить запись офлайн-first. Если запись ещё не успела уехать на сервер (в очереди
     * лежит непосланный "create" для того же id, и он прямо сейчас НЕ в полёте — см.
     * [inFlightCreateEntryIds]) — отменяем создание целиком: стираем и локальную строку,
     * и саму операцию, серверу сообщать нечего. Если "create" уже в полёте — безопасно
     * отменить его нельзя (ответ всё равно применится), поэтому ставим настоящий DELETE
     * в очередь: запись всё равно пропадёт из ленты сейчас же, а с сервера её уберёт
     * следующий sync() — после того как create применит свой ответ, а delete снова
     * его сотрёт. Секундная "вспышка" воскресшей записи в БД — плата за то, что не
     * заводим полноценный протокол отмены in-flight запроса.
     */
    fun enqueueDelete(entryId: String) {
        val pendingCreate = pendingQueries.findByEntryAndType(entryId, OP_CREATE).executeAsOneOrNull()
        if (pendingCreate != null && entryId !in inFlightCreateEntryIds) {
            pendingQueries.deleteOperation(pendingCreate.id)
            entryQueries.deleteById(entryId)
            return
        }
        entryQueries.deleteById(entryId)
        // Повторный вызов (двойной тап) не должен наплодить несколько DELETE-операций подряд.
        if (pendingQueries.findByEntryAndType(entryId, OP_DELETE).executeAsOneOrNull() != null) return
        pendingQueries.insertOperation(
            id = UUID.randomUUID().toString(),
            entryId = entryId,
            type = OP_DELETE,
            payloadJson = null,
            createdAtMillis = nowMillis(),
            attempts = 0,
            nextAttemptAtMillis = 0,
        )
    }

    /**
     * Отправить очередь исходящих изменений и подтянуть изменения с сервера — именно в этом
     * порядке: сначала наши правки уезжают на сервер, потом since-протокол увидит их же
     * в /changes и просто ещё раз upsert'нет то, что уже лежит локально (дубля не будет:
     * upsert всегда по id, ключ один и тот же).
     *
     * Бросает исключение, только если сама подтяжка изменений (pull) не удалась — ошибки
     * отдельных операций очереди не прерывают sync() целиком, они уходят на повтор
     * с экспоненциальной задержкой (см. [flushPendingLocked]).
     */
    suspend fun sync() = syncMutex.withLock {
        flushPendingLocked()
        pullChangesLocked()
    }

    private suspend fun flushPendingLocked() {
        val due = pendingQueries.selectDue(nowMillis()).executeAsList()
        for (op in due) {
            if (op.type == OP_CREATE) inFlightCreateEntryIds += op.entryId
            try {
                when (op.type) {
                    OP_CREATE -> {
                        if (op.payloadJson == null) {
                            // Испорченная операция без данных — зависать в очереди незачем.
                            pendingQueries.deleteOperation(op.id)
                        } else {
                            val newEntry = json.decodeFromString(NewEntry.serializer(), op.payloadJson)
                            val created = syncClient.createEntry(newEntry)
                            applyRemoteEntry(created)
                            pendingQueries.deleteOperation(op.id)
                        }
                    }
                    OP_DELETE -> {
                        // true и на 204 (удалили), и на 404 (уже не было) — цель в обоих
                        // случаях достигнута, повторять запрос незачем.
                        syncClient.deleteEntry(op.entryId)
                        // Локальная строка обычно уже стёрта в enqueueDelete() — но если
                        // конкурентно прилетел ответ на её же "create" (см. inFlightCreateEntryIds
                        // выше), applyRemoteEntry() мог её воскресить. deleteById по отсутствующей
                        // строке — no-op, поэтому безопасно звать всегда, а не только "на всякий".
                        entryQueries.deleteById(op.entryId)
                        pendingQueries.deleteOperation(op.id)
                    }
                    else -> pendingQueries.deleteOperation(op.id) // неизвестный тип не должен виснуть в очереди вечно
                }
            } catch (e: CancellationException) {
                // Не глушим отмену корутины (например, ViewModel очистили посреди flush) —
                // проглоченный CancellationException ломает структурную конкурентность:
                // цикл продолжил бы слать запросы уже после того, как его должны были остановить.
                throw e
            } catch (e: Exception) {
                val attempts = op.attempts + 1
                pendingQueries.markAttempt(
                    attempts = attempts,
                    nextAttemptAtMillis = nowMillis() + backoffDelayMillis(attempts),
                    id = op.id,
                )
            } finally {
                if (op.type == OP_CREATE) inFlightCreateEntryIds -= op.entryId
            }
        }
    }

    private suspend fun pullChangesLocked() {
        var since = entryQueries.getSyncCursor().executeAsOneOrNull()
        var hasMore = true
        while (hasMore) {
            val page = syncClient.fetchChanges(since)
            page.entries.forEach { entry ->
                if (entry.deleted) entryQueries.deleteById(entry.id) else applyRemoteEntry(entry)
            }
            since = page.serverTime
            hasMore = page.hasMore
            entryQueries.setSyncCursor(page.serverTime)
        }
    }

    private fun applyRemoteEntry(entry: EntryDto) {
        entryQueries.upsert(
            id = entry.id,
            projectId = entry.projectId,
            occurredOn = entry.occurredOn,
            rawText = entry.rawText,
            source = entry.source,
            status = entry.status,
            timeSpentMin = entry.timeSpentMin?.toLong(),
            createdAt = entry.createdAt,
            updatedAt = entry.updatedAt,
            structuredJson = entry.toEntityJson(),
            aiError = entry.aiError,
        )
    }

    /** Выйти из аккаунта: следующий вход должен скачать всё заново, а не чужой хвост. */
    fun clearLocalData() {
        entryQueries.deleteAll()
        entryQueries.clearSyncCursor()
        pendingQueries.deleteAllPending()
    }

    companion object {
        private const val OP_CREATE = "create"
        private const val OP_DELETE = "delete"

        /** 2с, 4с, 8с, 16с, 32с, дальше не растёт — 60с потолок. */
        internal fun backoffDelayMillis(attempts: Long): Long {
            val base = 2_000L
            val max = 60_000L
            val shift = attempts.coerceIn(1, 5).toInt() - 1
            return (base shl shift).coerceAtMost(max)
        }
    }
}
