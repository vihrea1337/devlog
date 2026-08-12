package io.github.vihrea1337.devlog

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Шина событий: сервер сообщает открытым вкладкам, что запись обработана,
 * вместо того чтобы клиент опрашивал его каждые несколько секунд.
 */
class EventBusTest {

    @BeforeTest
    fun setup() = EntryEventBus.reset()

    @AfterTest
    fun teardown() = EntryEventBus.reset()

    @Test
    fun `подписчик получает событие о своей записи`() = runBlocking {
        val userId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        val got = CompletableDeferred<EntryEvent>()

        val job = launch {
            EntryEventBus.subscribe(userId).collect { got.complete(it) }
        }
        delay(100) // дать подписке встать до отправки: события не копятся про запас
        EntryEventBus.publish(userId, entryId, "structured")

        val event = withTimeout(2000) { got.await() }
        assertEquals(entryId.toString(), event.entryId)
        assertEquals("structured", event.status)
        job.cancel()
    }

    @Test
    fun `чужие события не приходят`() = runBlocking {
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        val got = CompletableDeferred<EntryEvent>()

        val job = launch { EntryEventBus.subscribe(mine).collect { got.complete(it) } }
        delay(100)
        EntryEventBus.publish(other, UUID.randomUUID(), "structured")

        assertNull(withTimeoutOrNull(400) { got.await() }, "событие другого пользователя пришло не тому")
        job.cancel()
    }

    @Test
    fun `отправка без подписчиков не роняет сервер`() {
        // Никто не слушает (вкладок нет) — событие просто теряется.
        EntryEventBus.publish(UUID.randomUUID(), UUID.randomUUID(), "structured")
    }

    @Test
    fun `без токена поток событий закрывается, а не отдаёт чужие данные`() = testApplication {
        application { module() }

        // Cookie devlog_sse не ставим — сервер должен сразу попрощаться.
        val response = client.get("/api/events")

        val body = response.bodyAsText()
        assertTrue(body.contains("unauthorized"), "ожидали событие unauthorized, пришло: $body")
    }
}
