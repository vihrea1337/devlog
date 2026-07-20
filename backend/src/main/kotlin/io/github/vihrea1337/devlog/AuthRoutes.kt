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
import kotlinx.serialization.Serializable
import java.util.UUID

// --- Форматы запросов/ответов (DTO — Data Transfer Object, то, что летает в JSON) ---

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String = "")

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class UserDto(val id: String, val email: String, val displayName: String)

/** Ответ на регистрацию/вход: токен + кто вошёл. */
@Serializable
data class AuthResponse(val token: String, val user: UserDto)

/** Единый формат ошибки. */
@Serializable
data class ErrorResponse(val error: String)

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
        get("/api/me") {
            val user = UserRepository.findById(call.userId())
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Пользователь не найден"))
            } else {
                call.respond(user.toDto())
            }
        }
    }
}

private fun UserRow.toDto() = UserDto(id.toString(), email, displayName)
