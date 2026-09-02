package io.github.vihrea1337.devlog.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.vihrea1337.devlog.EntryChangesDto
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.NewEntry
import io.github.vihrea1337.devlog.db.DevLogDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Офлайн-запись: enqueueCreate/enqueueDelete пишут сразу в локальный кэш и в очередь
 * pendingOperation, а flushPending() (часть sync()) разбирает эту очередь. Сеть везде
 * подменена MockEngine — реальных HTTP-запросов нет.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EntryRepositoryPendingQueueTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun newDatabase(): DevLogDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DevLogDatabase.Schema.create(driver)
        return DevLogDatabase(driver)
    }

    private fun MockRequestHandleScope.noChanges() = respond(
        content = json.encodeToString(
            EntryChangesDto.serializer(),
            EntryChangesDto(emptyList(), serverTime = "t0", hasMore = false),
        ),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.entryCreated(dto: EntryDto) = respond(
        content = json.encodeToString(EntryDto.serializer(), dto),
        status = HttpStatusCode.Created,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun isChangesRequest(request: HttpRequestData) =
        request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/entries/changes")

    private fun isCreateRequest(request: HttpRequestData) =
        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/entries")

    private fun isDeleteRequest(request: HttpRequestData) =
        request.method == HttpMethod.Delete && request.url.encodedPath.contains("/entries/")

    private fun newEntry(id: String) = NewEntry(occurredOn = "2026-09-02", rawText = "офлайн текст", id = id)

    @Test
    fun `enqueueCreate сразу показывает запись в кэше ещё до сети`() = runTest {
        val db = newDatabase()
        val client = DevLogSyncClient("http://localhost", { null }, MockEngine { noChanges() })
        val repo = EntryRepository(db, client)

        repo.enqueueCreate(newEntry("local-1"))

        val cached = db.entriesQueries.selectAll().executeAsList()
        assertEquals(1, cached.size)
        assertEquals("queued", cached.single().status)
        assertEquals(1, db.pendingOperationsQueries.countPending().executeAsOne())
    }

    @Test
    fun `sync отправляет отложенное создание и снимает его с очереди`() = runTest {
        var createCalls = 0
        val db = newDatabase()
        val client = DevLogSyncClient(
            "http://localhost",
            { "token" },
            MockEngine { request ->
                when {
                    isCreateRequest(request) -> {
                        createCalls++
                        entryCreated(
                            EntryDto(
                                id = "local-1",
                                occurredOn = "2026-09-02",
                                rawText = "офлайн текст",
                                status = "queued",
                                createdAt = "2026-09-02T00:00:00Z",
                                updatedAt = "2026-09-02T00:00:00Z",
                            ),
                        )
                    }
                    isChangesRequest(request) -> noChanges()
                    else -> error("неожиданный запрос: ${request.method} ${request.url}")
                }
            },
        )
        val repo = EntryRepository(db, client)
        repo.enqueueCreate(newEntry("local-1"))

        repo.sync()

        assertEquals(1, createCalls)
        assertEquals(0, db.pendingOperationsQueries.countPending().executeAsOne())
        assertEquals(1, db.entriesQueries.selectAll().executeAsList().size)
    }

    @Test
    fun `удаление ещё не отправленной записи отменяет создание без единого сетевого запроса`() = runTest {
        val db = newDatabase()
        val client = DevLogSyncClient(
            "http://localhost",
            { null },
            MockEngine { request -> error("сеть не должна была понадобиться: ${request.method} ${request.url}") },
        )
        val repo = EntryRepository(db, client)

        repo.enqueueCreate(newEntry("local-1"))
        repo.enqueueDelete("local-1")

        assertEquals(0, db.entriesQueries.selectAll().executeAsList().size)
        assertEquals(0, db.pendingOperationsQueries.countPending().executeAsOne())
    }

    @Test
    fun `удаление уже синхронизированной записи ставит DELETE в очередь и убирает её из кэша сразу`() = runTest {
        var deleteCalls = 0
        val db = newDatabase()
        val client = DevLogSyncClient(
            "http://localhost",
            { null },
            MockEngine { request ->
                when {
                    isDeleteRequest(request) -> {
                        deleteCalls++
                        respond("", status = HttpStatusCode.NoContent)
                    }
                    isChangesRequest(request) -> noChanges()
                    else -> error("неожиданный запрос: ${request.method} ${request.url}")
                }
            },
        )
        val repo = EntryRepository(db, client)
        db.entriesQueries.upsert(
            id = "server-1", projectId = null, occurredOn = "2026-09-01", rawText = "старая",
            source = "manual", status = "structured", timeSpentMin = null,
            createdAt = "2026-09-01T00:00:00Z", updatedAt = "2026-09-01T00:00:00Z",
            structuredJson = null, aiError = null,
        )

        repo.enqueueDelete("server-1")
        // Локально запись пропадает мгновенно, ещё до сети.
        assertEquals(0, db.entriesQueries.selectAll().executeAsList().size)
        assertEquals(1, db.pendingOperationsQueries.countPending().executeAsOne())

        repo.sync()

        assertEquals(1, deleteCalls)
        assertEquals(0, db.pendingOperationsQueries.countPending().executeAsOne())
    }

    @Test
    fun `404 на удаление считается успехом — очередь очищается без бесконечных повторов`() = runTest {
        val db = newDatabase()
        val client = DevLogSyncClient(
            "http://localhost",
            { null },
            MockEngine { request ->
                when {
                    isDeleteRequest(request) -> respond("", status = HttpStatusCode.NotFound)
                    isChangesRequest(request) -> noChanges()
                    else -> error("неожиданный запрос: ${request.method} ${request.url}")
                }
            },
        )
        val repo = EntryRepository(db, client)
        repo.enqueueDelete("уже-не-существует")

        repo.sync()

        assertEquals(0, db.pendingOperationsQueries.countPending().executeAsOne())
    }

    @Test
    fun `сбой отправки не роняет sync и планирует повтор с задержкой, не раньше срока`() = runTest {
        var createAttempts = 0
        var clock = 1_000_000L
        val db = newDatabase()
        val client = DevLogSyncClient(
            "http://localhost",
            { null },
            MockEngine { request ->
                when {
                    isCreateRequest(request) -> {
                        createAttempts++
                        throw RuntimeException("сети нет")
                    }
                    isChangesRequest(request) -> noChanges()
                    else -> error("неожиданный запрос: ${request.method} ${request.url}")
                }
            },
        )
        val repo = EntryRepository(db, client, nowMillis = { clock })
        repo.enqueueCreate(newEntry("local-1"))

        // Первая попытка проваливается, но sync() не бросает исключение — запись остаётся
        // в кэше (оптимистично), операция остаётся в очереди с nextAttemptAtMillis в будущем.
        repo.sync()
        assertEquals(1, createAttempts)
        assertEquals(1, db.pendingOperationsQueries.countPending().executeAsOne())
        assertEquals(1, db.entriesQueries.selectAll().executeAsList().size)

        // Время не сдвинулось — до срока следующей попытки, повторной отправки быть не должно.
        repo.sync()
        assertEquals(1, createAttempts, "не время для повтора — не должно было уйти второго запроса")

        // Сдвигаем часы за пределы backoff-задержки — теперь повтор должен уйти.
        clock += 10_000L
        repo.sync()
        assertEquals(2, createAttempts)
    }

    @Test
    fun `удаление во время отправки того же create не воскрешает запись после его ответа`() = runTest {
        val db = newDatabase()
        val proceedWithCreateResponse = CompletableDeferred<Unit>()
        var createCalls = 0
        var deleteCalls = 0
        val client = DevLogSyncClient(
            "http://localhost",
            { null },
            MockEngine { request ->
                when {
                    isCreateRequest(request) -> {
                        createCalls++
                        // Застреваем здесь, пока тест не разрешит ответить — имитируем
                        // "запрос уже ушёл, ответа ещё нет".
                        proceedWithCreateResponse.await()
                        entryCreated(
                            EntryDto(
                                id = "local-1",
                                occurredOn = "2026-09-02",
                                rawText = "офлайн текст",
                                status = "queued",
                                createdAt = "2026-09-02T00:00:00Z",
                                updatedAt = "2026-09-02T00:00:00Z",
                            ),
                        )
                    }
                    isDeleteRequest(request) -> {
                        deleteCalls++
                        respond("", status = HttpStatusCode.NoContent)
                    }
                    isChangesRequest(request) -> noChanges()
                    else -> error("неожиданный запрос: ${request.method} ${request.url}")
                }
            },
        )
        val repo = EntryRepository(db, client)
        repo.enqueueCreate(newEntry("local-1"))

        val syncJob = launch { repo.sync() }
        runCurrent() // довести sync() до сетевого вызова create, где он застрянет на await()

        // Пользователь удаляет запись ровно в момент, когда create уже улетел на сервер.
        repo.enqueueDelete("local-1")

        proceedWithCreateResponse.complete(Unit)
        syncJob.join()

        // На этот момент create применил свой ответ (кратковременно "воскресив" запись
        // локально — это и есть задокументированная плата за отсутствие отмены in-flight
        // запроса), но delete уже стоит в очереди и уедет следующим sync().
        repo.sync()

        assertEquals(1, createCalls)
        assertEquals(1, deleteCalls)
        assertEquals(0, db.entriesQueries.selectAll().executeAsList().size)
        assertEquals(0, db.pendingOperationsQueries.countPending().executeAsOne())
    }

    @Test
    fun `формула задержки растёт и не превышает потолок`() {
        assertEquals(2_000L, EntryRepository.backoffDelayMillis(1))
        assertEquals(4_000L, EntryRepository.backoffDelayMillis(2))
        assertEquals(8_000L, EntryRepository.backoffDelayMillis(3))
        assertTrue(EntryRepository.backoffDelayMillis(20) <= 60_000L)
    }
}
