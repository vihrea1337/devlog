package io.github.vihrea1337.devlog

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/**
 * Точка входа сервера. Пока это "шагающий скелет": сервер поднимается и отвечает
 * только на /health. Базу данных, авторизацию и остальные ручки добавим в Фазе 1.
 *
 * host = "127.0.0.1" — слушаем только локально (снаружи до сервера дотянется Caddy,
 * который раздаёт HTTPS). PORT/HOST можно переопределить переменными окружения
 * (пригодится в Docker: внутри контейнера надо слушать 0.0.0.0).
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "127.0.0.1"
    embeddedServer(Netty, port = port, host = host) {
        module()
    }.start(wait = true)
}

/**
 * Настройка приложения: какие плагины включены и какие есть маршруты (ручки).
 * Вынесено в отдельную функцию, чтобы её же могли поднимать тесты (без реального порта).
 */
fun Application.module() {
    // Плагин: превращает наши классы в JSON и обратно.
    install(ContentNegotiation) { json() }

    routing {
        // Проверка живости сервера — открытая ручка для мониторинга и деплоя.
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }
    }
}

/** Ответ /health: сериализуется в {"status":"ok"}. */
@Serializable
data class HealthResponse(val status: String)
