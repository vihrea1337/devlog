package io.github.vihrea1337.devlog

import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Живые обновления ленты: сервер сам сообщает клиенту, что запись обработана.
 *
 * Было: браузер каждые 4 секунды спрашивал «уже готово?» — лишние запросы и задержка.
 * Стало: одно открытое соединение (SSE, Server-Sent Events), по которому сервер шлёт
 * события сам. Поток односторонний (сервер → клиент), поэтому WebSocket здесь не нужен.
 *
 * **Про токен.** Браузерный EventSource не умеет добавлять заголовок Authorization,
 * поэтому токен читаем из cookie `devlog_sse`, которую страница ставит после входа
 * с `path=/api/events`. В адресной строке токена нет специально: адреса попадают
 * в логи прокси, а cookie с таким path уходит только на эту одну ручку.
 */
private val eventJson = Json { encodeDefaults = true }

fun Route.eventRoutes() {
    sse("/api/events") {
        val token = call.request.cookies["devlog_sse"]
        val userId = token?.let(JwtService::userIdFromToken)
        if (userId == null) {
            // Не представился — молча закрываем: у SSE ответ уже начат, кодом 401 не ответить.
            send(ServerSentEvent(event = "unauthorized", data = "нет или истёк токен"))
            return@sse
        }

        // Прокси (у нас Caddy) рвут соединения, в которых долго тихо. Раз в 25 секунд
        // шлём пустое событие «я жив»; клиент его игнорирует.
        val heartbeat = launch {
            while (isActive) {
                delay(HEARTBEAT_MILLIS)
                runCatching { send(ServerSentEvent(event = "ping", data = "")) }.onFailure { return@launch }
            }
        }

        try {
            send(ServerSentEvent(event = "ready", data = "подключено"))
            EntryEventBus.subscribe(userId).collect { event ->
                send(ServerSentEvent(event = "entry", data = eventJson.encodeToString(event)))
            }
        } finally {
            heartbeat.cancel()
        }
    }
}

private const val HEARTBEAT_MILLIS = 25_000L
