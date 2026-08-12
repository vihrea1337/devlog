package io.github.vihrea1337.devlog

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты очереди обработки ИИ. Сеть не участвует: вместо Groq подставляем поддельный
 * [AiStructurer], который либо возвращает готовую структуру, либо кидает ошибку.
 * Так проверяется именно логика очереди — статусы, попытки, зависшие записи, лимит.
 */
class AiWorkerTest {

    /** Поддельный ИИ: считает вызовы и делает то, что скажет тест. */
    private class FakeAi(
        private val result: (String) -> StructuredDto = { StructuredDto(summary = "суть: $it") },
    ) : AiStructurer {
        override val modelName = "fake-model"
        var calls = 0
            private set

        override suspend fun structure(rawText: String): StructuredDto {
            calls++
            return result(rawText)
        }
    }

    private lateinit var userId: UUID

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_worker;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, Projects, Entries, EntryStructured) }
        userId = UserRepository.create("worker@test.co", Passwords.hash("secret1"), "W").id
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(EntryStructured, Entries, Projects, Users) }
    }

    private fun newEntry(text: String = "чинил баг с виджетом"): UUID {
        val input = ValidEntryInput(
            occurredOn = java.time.LocalDate.parse("2026-08-12"),
            rawText = text,
            projectId = null,
            sourceType = "manual",
            timeSpentMin = null,
        )
        return UUID.fromString(EntryRepository.create(userId, input).id)
    }

    private fun statusOf(id: UUID): String = EntryRepository.getById(userId, id)!!.status
    private fun attemptsOf(id: UUID): Int = transaction {
        Entries.selectAll().where { Entries.id eq id }.single()[Entries.aiAttempts]
    }

    @Test
    fun `запись из очереди обрабатывается и получает структуру`() = runBlocking {
        val id = newEntry()
        val ai = FakeAi { StructuredDto(summary = "починил виджет", steps = listOf("нашёл", "поправил")) }
        val worker = AiWorker(ai, limiter = { true })

        assertTrue(worker.runOnce(), "в очереди была запись — воркер должен был её взять")

        assertEquals("structured", statusOf(id))
        assertEquals(1, ai.calls)
        val structured = EntryStructuredRepository.forEntry(id)
        assertNotNull(structured)
        assertEquals("починил виджет", structured.summary)
        assertEquals(2, structured.steps.size)
        assertNull(EntryRepository.getById(userId, id)!!.aiError, "у успешной записи не должно быть ошибки")
    }

    @Test
    fun `пустая очередь — воркеру нечего делать`() = runBlocking {
        assertFalse(AiWorker(FakeAi(), limiter = { true }).runOnce())
    }

    @Test
    fun `ошибка ИИ — запись возвращается в очередь, попытка засчитана`() = runBlocking {
        val id = newEntry()
        val worker = AiWorker(FakeAi { error("Groq ответил 429: too many requests") }, maxAttempts = 3, limiter = { true })

        worker.runOnce()

        assertEquals("queued", statusOf(id), "попытки ещё есть — запись должна ждать следующего захода")
        assertEquals(1, attemptsOf(id))
        val err = EntryRepository.getById(userId, id)!!.aiError
        assertNotNull(err)
        assertTrue(err.contains("429"), "текст ошибки должен сохраняться: $err")
    }

    @Test
    fun `после исчерпания попыток запись помечается failed с причиной`() = runBlocking {
        val id = newEntry()
        val worker = AiWorker(FakeAi { error("модель вернула мусор") }, maxAttempts = 2, limiter = { true })

        worker.runOnce()
        assertEquals("queued", statusOf(id))
        worker.runOnce()

        assertEquals("failed", statusOf(id))
        assertEquals(2, attemptsOf(id))
        assertTrue(EntryRepository.getById(userId, id)!!.aiError!!.contains("мусор"))

        // Больше воркер за неё не берётся — иначе крутился бы вечно на битой записи.
        assertFalse(worker.runOnce())
    }

    @Test
    fun `зависшую в processing запись воркер подбирает заново`() = runBlocking {
        val id = newEntry()
        // Имитируем обрыв: запись взяли в работу давно, а сервер перезапустили.
        transaction {
            Entries.update({ Entries.id eq id }) {
                it[status] = "processing"
                it[aiStartedAt] = Instant.now().minus(Duration.ofMinutes(30))
            }
        }

        val worker = AiWorker(FakeAi(), stuckAfter = Duration.ofMinutes(5), limiter = { true })
        assertTrue(worker.runOnce(), "зависшая запись должна быть подобрана")
        assertEquals("structured", statusOf(id))
    }

    @Test
    fun `свежую processing запись воркер не трогает (её обрабатывают прямо сейчас)`() = runBlocking {
        val id = newEntry()
        transaction {
            Entries.update({ Entries.id eq id }) {
                it[status] = "processing"
                it[aiStartedAt] = Instant.now()
            }
        }

        assertFalse(AiWorker(FakeAi(), stuckAfter = Duration.ofMinutes(5), limiter = { true }).runOnce())
        assertEquals("processing", statusOf(id))
    }

    @Test
    fun `при исчерпанном суточном лимите запись ждёт, попытка не тратится`() = runBlocking {
        val id = newEntry()
        val ai = FakeAi()
        val worker = AiWorker(ai, limiter = { false })

        assertTrue(worker.runOnce())

        assertEquals("queued", statusOf(id), "запись должна остаться в очереди до завтра")
        assertEquals(0, attemptsOf(id), "упёрлись в лимит — попытка не должна сгорать")
        assertEquals(0, ai.calls, "в ИИ ходить не должны")
        assertTrue(EntryRepository.getById(userId, id)!!.aiError!!.contains("лимит"))
        // И воркер не крутится вхолостую на этом же пользователе.
        assertFalse(worker.runOnce())
    }

    @Test
    fun `переобработка обнуляет попытки и ошибку`() = runBlocking {
        val id = newEntry()
        val worker = AiWorker(FakeAi { error("сеть недоступна") }, maxAttempts = 1, limiter = { true })
        worker.runOnce()
        assertEquals("failed", statusOf(id))

        EntryRepository.requeueForAi(userId, id)
        assertEquals("queued", statusOf(id))
        assertEquals(0, attemptsOf(id))
        assertNull(EntryRepository.getById(userId, id)!!.aiError)

        // Теперь ИИ отвечает нормально — запись доходит до structured.
        assertTrue(AiWorker(FakeAi(), limiter = { true }).runOnce())
        assertEquals("structured", statusOf(id))
    }
}
