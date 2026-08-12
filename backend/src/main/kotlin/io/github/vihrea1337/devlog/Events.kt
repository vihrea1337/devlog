package io.github.vihrea1337.devlog

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Событие для клиента: с записью что-то произошло (ИИ дообработал, обработка сорвалась).
 * Данные записи не шлём — только «сходи обнови», чтобы событие не пришлось держать
 * в актуальном виде и чтобы содержимое дневника не летало лишний раз по сети.
 */
@Serializable
data class EntryEvent(val entryId: String, val status: String)

/**
 * Кто кому шлёт события. Раньше клиент сам дёргал сервер каждые несколько секунд
 * («не готово ли?»), теперь сервер сам говорит «готово» по открытому соединению (SSE).
 *
 * Для каждого пользователя — свой поток. Хранится в памяти процесса, и это осознанно:
 * события живут секунды и не жалко потерять при перезапуске (сама очередь обработки
 * лежит в базе, см. AiWorker). Если однажды серверов станет несколько, сюда добавится
 * общая шина — например, LISTEN/NOTIFY у PostgreSQL.
 */
object EntryEventBus {
    private val flows = ConcurrentHashMap<UUID, MutableSharedFlow<EntryEvent>>()

    private fun flow(userId: UUID): MutableSharedFlow<EntryEvent> = flows.computeIfAbsent(userId) {
        // Никто не слушает → события просто теряются, отправитель не блокируется.
        MutableSharedFlow(replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    /** Поток событий пользователя — на него подписывается открытое SSE-соединение. */
    fun subscribe(userId: UUID): SharedFlow<EntryEvent> = flow(userId)

    /** Сообщить, что запись сменила статус. Вызывается из воркера ИИ. */
    fun publish(userId: UUID, entryId: UUID, status: String) {
        flow(userId).tryEmit(EntryEvent(entryId.toString(), status))
    }

    /** Только для тестов: забыть все потоки. */
    internal fun reset() = flows.clear()
}
