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
