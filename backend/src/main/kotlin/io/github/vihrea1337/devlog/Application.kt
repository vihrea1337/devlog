package io.github.vihrea1337.devlog

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
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
    // Подключиться к базе и создать таблицы (если их ещё нет) ДО старта сервера.
    configureDatabase()
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

    // Единая обработка непойманных ошибок: пишем в лог и отдаём аккуратный JSON, а не стектрейс.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Необработанная ошибка запроса", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Внутренняя ошибка сервера"))
        }
    }

    // Проверка JWT на защищённых ручках: токен приходит в заголовке "Authorization: Bearer <token>".
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "DevLog"
            verifier(JwtService.verifier)
            validate { credential ->
                // Токен валиден и содержит наш claim userId → пускаем как этого пользователя.
                if (credential.payload.getClaim("userId").asString() != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }

    routing {
        // Проверка живости сервера — открытая ручка для мониторинга и деплоя.
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }
        // Регистрация/вход (открыты) и /api/me (под токеном).
        authRoutes()
        // CRUD записей (все под токеном).
        entryRoutes()
    }
}

/** Ответ /health: сериализуется в {"status":"ok"}. */
@Serializable
data class HealthResponse(val status: String)
