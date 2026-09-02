package io.github.vihrea1337.devlog.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.db.DevLogDatabase
import io.github.vihrea1337.devlog.db.EntryEntity
import io.github.vihrea1337.devlog.db.toDto
import io.github.vihrea1337.devlog.db.toEntityJson
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Единая точка доступа к записям для UI: читает из локальной SQLDelight-базы (мгновенно,
 * работает офлайн) и умеет подтягивать изменения с сервера через since-протокол.
 *
 * Экран не должен сам решать, идти в сеть или нет — он просто подписывается на [entries]
 * и по необходимости зовёт [sync]. Если sync() упадёт (нет сети) — то, что уже лежит
 * в базе, никуда не денется и продолжит показываться.
 *
 * Все .sq-запросы (и по записям, и по курсору синхронизации) лежат в одном файле
 * Entries.sq, поэтому SQLDelight генерирует для них один объект — entriesQueries.
 */
class EntryRepository(
    private val database: DevLogDatabase,
    private val syncClient: DevLogSyncClient,
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {
    private val queries = database.entriesQueries

    /** Вся лента из локального кэша, свежие сверху. Обновляется сама при любом sync(). */
    fun entries(): Flow<List<EntryDto>> =
        mapDto(queries.selectAll().asFlow().mapToList(ioContext))

    /** Лента одного проекта из локального кэша. */
    fun entriesByProject(projectId: String): Flow<List<EntryDto>> =
        mapDto(queries.selectByProject(projectId).asFlow().mapToList(ioContext))

    private fun mapDto(flow: Flow<List<EntryEntity>>): Flow<List<EntryDto>> =
        flow.map { rows -> rows.map { it.toDto() } }

    /**
     * Подтянуть изменения с сервера в локальную базу. Идёт страницами, пока сервер
     * говорит hasMore = true (лимит на страницу — внутри DevLogSyncClient).
     * Мягко удалённые записи (deleted = true) просто убираются из локальной таблицы —
     * хранить надгробия клиенту незачем, это забота сервера.
     *
     * Бросает исключение при сетевой ошибке — вызывающий код (ViewModel) решает,
     * как её показать пользователю; локальный кэш при этом не трогается.
     */
    suspend fun sync() {
        var since = queries.getSyncCursor().executeAsOneOrNull()
        var hasMore = true
        while (hasMore) {
            val page = syncClient.fetchChanges(since)
            page.entries.forEach { entry ->
                if (entry.deleted) {
                    queries.deleteById(entry.id)
                } else {
                    queries.upsert(
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
            }
            since = page.serverTime
            hasMore = page.hasMore
            queries.setSyncCursor(page.serverTime)
        }
    }

    /** Выйти из аккаунта: следующий вход должен скачать всё заново, а не чужой хвост. */
    fun clearLocalData() {
        queries.deleteAll()
        queries.clearSyncCursor()
    }
}
