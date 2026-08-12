package io.github.vihrea1337.devlog

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import java.util.UUID

// --- Форматы запросов/ответов (DTO — Data Transfer Object, то, что летает в JSON) ---

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String = "")

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String,
    /** Логин на GitHub, если привязан: с ним работает импорт коммитов. */
    val githubLogin: String? = null,
)

/** Тело PUT /api/me: пока меняем только привязку к GitHub. Пустая строка — отвязать. */
@Serializable
data class UpdateProfile(val githubLogin: String? = null)

/** Ответ на регистрацию/вход: токен + кто вошёл. */
@Serializable
data class AuthResponse(val token: String, val user: UserDto)

/** Единый формат ошибки. */
@Serializable
data class ErrorResponse(val error: String)

/**
 * Настройки сервера, важные клиенту. Главное — включён ли ИИ: без ключа записи честно
 * висят в очереди, и интерфейс должен писать «ИИ выключен», а не притворяться, что вот-вот
 * обработает.
 */
@Serializable
data class ServerConfigDto(val aiEnabled: Boolean, val aiDailyLimit: Int)

/** Правила GitHub: латиница, цифры и дефис, не длиннее 39 символов. */
private val GITHUB_LOGIN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")

/** id текущего пользователя из проверенного JWT (claim "userId"). */
fun ApplicationCall.userId(): UUID =
    UUID.fromString(principal<JWTPrincipal>()!!.payload.getClaim("userId").asString())

/** Ручки авторизации: регистрация, вход, "кто я". */
fun Route.authRoutes() {
    // Регистрация — открыта (иначе новый пользователь не завёл бы аккаунт).
    post("/api/auth/register") {
        val body = call.receive<RegisterRequest>()
        val email = body.email.trim().lowercase()
        if (email.isBlank() || body.password.length < 6) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Нужен email и пароль от 6 символов"))
            return@post
        }
        if (UserRepository.findByEmail(email) != null) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Пользователь с таким email уже есть"))
            return@post
        }
        val name = body.displayName.trim().ifBlank { email.substringBefore('@') }
        val user = UserRepository.create(email, Passwords.hash(body.password), name)
        call.respond(AuthResponse(JwtService.makeToken(user.id, user.email), user.toDto()))
    }

    // Вход — открыт. Верный пароль → новый токен; неверный → 401.
    post("/api/auth/login") {
        val body = call.receive<LoginRequest>()
        val email = body.email.trim().lowercase()
        val user = UserRepository.findByEmail(email)
        if (user == null || !Passwords.verify(body.password, user.passwordHash)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Неверный email или пароль"))
            return@post
        }
        call.respond(AuthResponse(JwtService.makeToken(user.id, user.email), user.toDto()))
    }

    // "Кто я" — только с валидным токеном.
    authenticate("auth-jwt") {
        // Что умеет этот сервер (включён ли ИИ и какой суточный лимит).
        get("/api/config") {
            call.respond(ServerConfigDto(aiEnabled = AiProcessor.enabled, aiDailyLimit = AiLimiter.dailyLimit))
        }

        get("/api/me") {
            val user = UserRepository.findById(call.userId())
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Пользователь не найден"))
            } else {
                call.respond(user.toDto())
            }
        }

        // Настройки профиля. Сейчас это только логин GitHub для импорта коммитов.
        put("/api/me") {
            val body = call.receive<UpdateProfile>()
            val login = body.githubLogin?.trim()?.removePrefix("@").orEmpty()
            if (login.isNotEmpty() && !GITHUB_LOGIN.matches(login)) {
                return@put call.badRequest("Некорректный логин GitHub: латиница, цифры и дефис, до 39 символов")
            }
            UserRepository.setGithubLogin(call.userId(), login.ifEmpty { null })
            val user = UserRepository.findById(call.userId())
                ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Пользователь не найден"))
            call.respond(user.toDto())
        }
    }
}

private fun UserRow.toDto() = UserDto(id.toString(), email, displayName, githubLogin)
