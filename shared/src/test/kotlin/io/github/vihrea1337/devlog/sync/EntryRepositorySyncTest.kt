package io.github.vihrea1337.devlog.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.vihrea1337.devlog.EntryChangesDto
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.db.DevLogDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Проверяет EntryRepository.sync() целиком: страницы since-протокола реально
 * ложатся в локальную SQLDelight-базу (in-memory, живёт только на время теста),
 * а сеть подменена MockEngine — реального HTTP-запроса тут нет.
 */
class EntryRepositorySyncTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun newDatabase(): DevLogDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DevLogDatabase.Schema.create(driver)
        return DevLogDatabase(driver)
    }

    private fun entry(id: String, deleted: Boolean = false) = EntryDto(
        id = id,
        occurredOn = "2026-09-01",
        rawText = "текст $id",
        status = "queued",
        createdAt = "2026-09-01T00:00:00Z",
        updatedAt = "2026-09-01T00:00:00Z",
        deleted = deleted,
    )

    private fun MockRequestHandleScope.respondWith(body: EntryChangesDto) = respond(
        content = json.encodeToString(EntryChangesDto.serializer(), body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun `первая синхронизация проходит все страницы и запоминает курсор сервера`() = runTest {
        val sentSince = mutableListOf<String?>()
        var call = 0
        val engine = MockEngine { request ->
            sentSince += request.url.parameters["since"]
            call++
            if (call == 1) {
                respondWith(EntryChangesDto(listOf(entry("e1"), entry("e2")), serverTime = "t1", hasMore = true))
            } else {
                respondWith(EntryChangesDto(listOf(entry("e3")), serverTime = "t2", hasMore = false))
            }
        }
        val db = newDatabase()
        val repo = EntryRepository(db, DevLogSyncClient("http://localhost", { "token" }, engine))

        repo.sync()

        assertEquals(3, db.entriesQueries.selectAll().executeAsList().size)
        assertEquals("t2", db.entriesQueries.getSyncCursor().executeAsOneOrNull())
        // Первый запрос — без since (самая первая синхронизация), второй — с курсором из первой страницы.
        assertEquals(listOf(null, "t1"), sentSince)
    }

    @Test
    fun `мягко удалённая запись убирается из локального кэша при следующей синхронизации`() = runTest {
        var call = 0
        val engine = MockEngine {
            call++
            if (call == 1) {
                respondWith(EntryChangesDto(listOf(entry("e1")), serverTime = "t1", hasMore = false))
            } else {
                respondWith(EntryChangesDto(listOf(entry("e1", deleted = true)), serverTime = "t2", hasMore = false))
            }
        }
        val db = newDatabase()
        val repo = EntryRepository(db, DevLogSyncClient("http://localhost", { null }, engine))

        repo.sync()
        assertEquals(1, db.entriesQueries.selectAll().executeAsList().size)

        repo.sync()
        assertEquals(0, db.entriesQueries.selectAll().executeAsList().size)
        assertEquals("t2", db.entriesQueries.getSyncCursor().executeAsOneOrNull())
    }

    @Test
    fun `неотправленная правка новее серверной версии — остаётся в кэше, сервер не затирает её`() = runTest {
        val engine = MockEngine {
            // Сервер отдаёт СТАРУЮ версию (например, устаревшую переобработку ИИ) — наша
            // ещё не отправленная правка текста новее.
            respondWith(
                EntryChangesDto(
                    listOf(entry("e1").copy(rawText = "старый серверный текст", updatedAt = "2026-09-01T00:00:00Z")),
                    serverTime = "t1",
                    hasMore = false,
                ),
            )
        }
        val db = newDatabase()
        val repo = EntryRepository(db, DevLogSyncClient("http://localhost", { null }, engine))
        db.entriesQueries.upsert(
            id = "e1", projectId = null, occurredOn = "2026-09-01", rawText = "наша свежая правка",
            source = "manual", status = "queued", timeSpentMin = null,
            createdAt = "2026-09-01T00:00:00Z", updatedAt = "2026-09-02T00:00:00Z",
            structuredJson = null, aiError = null,
        )
        db.pendingOperationsQueries.insertOperation(
            id = "op-1", entryId = "e1", type = "update", payloadJson = "наша свежая правка",
            createdAtMillis = 1, attempts = 0, nextAttemptAtMillis = 0,
        )

        repo.sync()

        assertEquals("наша свежая правка", db.entriesQueries.selectAll().executeAsList().single().rawText)
        assertEquals(1, db.pendingOperationsQueries.countPending().executeAsOne(), "правка всё ещё в очереди — отправится сама следующим sync()")
    }

    @Test
    fun `серверная версия новее неотправленной правки — сервер побеждает, устаревшая правка выкидывается из очереди`() = runTest {
        val engine = MockEngine {
            respondWith(
                EntryChangesDto(
                    listOf(entry("e1").copy(rawText = "версия с другого устройства", updatedAt = "2026-09-03T00:00:00Z")),
                    serverTime = "t1",
                    hasMore = false,
                ),
            )
        }
        val db = newDatabase()
        val repo = EntryRepository(db, DevLogSyncClient("http://localhost", { null }, engine))
        db.entriesQueries.upsert(
            id = "e1", projectId = null, occurredOn = "2026-09-01", rawText = "наша устаревшая правка",
            source = "manual", status = "queued", timeSpentMin = null,
            createdAt = "2026-09-01T00:00:00Z", updatedAt = "2026-09-01T00:00:00Z",
            structuredJson = null, aiError = null,
        )
        db.pendingOperationsQueries.insertOperation(
            id = "op-1", entryId = "e1", type = "update", payloadJson = "наша устаревшая правка",
            createdAtMillis = 1, attempts = 0, nextAttemptAtMillis = 0,
        )

        repo.sync()

        assertEquals("версия с другого устройства", db.entriesQueries.selectAll().executeAsList().single().rawText)
        assertEquals(0, db.pendingOperationsQueries.countPending().executeAsOne(), "устаревшую правку незачем слать дальше — она бы затёрла более новые данные")
    }

    @Test
    fun `запись удалена с сервера — неотправленная правка её текста отменяется вместе с ней`() = runTest {
        val engine = MockEngine {
            respondWith(EntryChangesDto(listOf(entry("e1", deleted = true)), serverTime = "t1", hasMore = false))
        }
        val db = newDatabase()
        val repo = EntryRepository(db, DevLogSyncClient("http://localhost", { null }, engine))
        db.entriesQueries.upsert(
            id = "e1", projectId = null, occurredOn = "2026-09-01", rawText = "правка на удалённую запись",
            source = "manual", status = "queued", timeSpentMin = null,
            createdAt = "2026-09-01T00:00:00Z", updatedAt = "2026-09-01T00:00:00Z",
            structuredJson = null, aiError = null,
        )
        db.pendingOperationsQueries.insertOperation(
            id = "op-1", entryId = "e1", type = "update", payloadJson = "правка на удалённую запись",
            createdAtMillis = 1, attempts = 0, nextAttemptAtMillis = 0,
        )

        repo.sync()

        assertEquals(0, db.entriesQueries.selectAll().executeAsList().size)
        assertEquals(0, db.pendingOperationsQueries.countPending().executeAsOne(), "слать PUT на удалённую запись незачем — она бы вечно получала 404")
    }

    @Test
    fun `clearLocalData стирает и записи, и курсор синхронизации`() = runTest {
        val engine = MockEngine {
            respondWith(EntryChangesDto(listOf(entry("e1")), serverTime = "t1", hasMore = false))
        }
        val db = newDatabase()
        val repo = EntryRepository(db, DevLogSyncClient("http://localhost", { null }, engine))

        repo.sync()
        repo.clearLocalData()

        assertEquals(0, db.entriesQueries.selectAll().executeAsList().size)
        assertNull(db.entriesQueries.getSyncCursor().executeAsOneOrNull())
    }
}
