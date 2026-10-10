package io.github.vihrea1337.devlog

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Демо-вход «одной кнопкой» — для показа сервиса работодателю без регистрации.
 *
 * Включается переменной окружения DEMO_LOGIN_EMAIL (email демо-аккаунта). Не задана —
 * кнопки нет, ручка отвечает 404. Так демо-доступ можно открыть на время собеседования
 * и закрыть после, не трогая код.
 *
 * Вход выдаёт токен «только для чтения» (claim readOnly): смотреть ленту и отчёты можно,
 * а любые изменения отклоняет [ReadOnlyGuard] — иначе любой посетитель мог бы стереть
 * или испортить демо-данные.
 */
object DemoAccess {
    // var, а не val — тесты включают демо-вход без переменных окружения.
    @Volatile var email: String? = System.getenv("DEMO_LOGIN_EMAIL")?.trim()?.lowercase()?.ifEmpty { null }
    val enabled: Boolean get() = email != null
}

fun Route.demoRoutes() {
    // Нужна странице входа ДО входа: показывать ли кнопку «Посмотреть демо».
    get("/api/auth/demo") {
        call.respond(DemoAvailabilityDto(enabled = DemoAccess.enabled))
    }

    post("/api/auth/demo") {
        val email = DemoAccess.email
        val user = email?.let { UserRepository.findByEmail(it) }
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Демо-доступ сейчас выключен"))
            return@post
        }
        val token = JwtService.makeToken(user.id, user.email, readOnly = true)
        call.respond(AuthResponse(token, user.toDto().copy(readOnly = true)))
    }
}

/**
 * Не пускает токены «только для чтения» к изменяющим запросам (POST/PUT/DELETE/PATCH
 * под /api/). Проверка одна на всё приложение — а не в каждой ручке, где её легко забыть.
 * Вход/регистрация (/api/auth/) не трогаются: там токена ещё нет.
 */
val ReadOnlyGuard = createApplicationPlugin("ReadOnlyGuard") {
    onCall { call ->
        val method = call.request.httpMethod
        if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return@onCall
        val path = call.request.path()
        if (!path.startsWith("/api/") || path.startsWith("/api/auth/")) return@onCall
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim() ?: return@onCall
        if (JwtService.isReadOnly(token)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Демо-режим: только просмотр"))
        }
    }
}
