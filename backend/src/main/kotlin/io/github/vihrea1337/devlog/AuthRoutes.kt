package io.github.vihrea1337.devlog

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import java.util.UUID

/** Правила GitHub: латиница, цифры и дефис, не длиннее 39 символов. */
private val GITHUB_LOGIN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")

/** id текущего пользователя из проверенного JWT (claim "userId"). */
fun ApplicationCall.userId(): UUID =
    principal<JWTPrincipal>()?.let { UUID.fromString(it.payload.getClaim("userId").asString()) }
        // Вход по токену скрипта (только ручки импорта, см. ImportTokens.kt).
        ?: principal<ImportTokenPrincipal>()!!.userId

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
        // Проверка выше не спасает от гонки: два одновременных запроса с одним email оба
        // видят «такого нет» и оба вставляют — второй упирается в уникальный индекс базы.
        // Раньше это был 500 «Внутренняя ошибка»; теперь — тот же понятный 409.
        val user = runCatching { UserRepository.create(email, Passwords.hash(body.password), name) }
            .getOrElse { e ->
                if (UserRepository.findByEmail(email) != null) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Пользователь с таким email уже есть"))
                    return@post
                }
                throw e
            }
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
                // readOnly — из токена: демо-вход видит те же данные, но только на просмотр.
                val readOnly = call.principal<JWTPrincipal>()?.payload?.getClaim("readOnly")?.asBoolean() == true
                call.respond(user.toDto().copy(readOnly = readOnly))
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

        // Личный токен GitHub — для импорта ПРИВАТНЫХ репозиториев (в отличие от githubLogin,
        // который нужен и для публичного импорта). Токен никогда не возвращается обратно —
        // только UserDto.githubTokenBound (факт привязки), см. ApiContract.kt.
        post("/api/me/github-token") {
            val body = call.receive<BindGithubTokenRequest>()
            val token = body.token.trim()
            if (token.isEmpty()) return@post call.badRequest("Токен пуст")
            if (token.length > 255) return@post call.badRequest("Похоже, это не токен GitHub — слишком длинная строка")
            GithubTokenRepository.save(call.userId(), token)
            val user = UserRepository.findById(call.userId())
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Пользователь не найден"))
            call.respond(user.toDto())
        }

        delete("/api/me/github-token") {
            GithubTokenRepository.remove(call.userId())
            val user = UserRepository.findById(call.userId())
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Пользователь не найден"))
            call.respond(user.toDto())
        }

        // Личный токен GitLab — в отличие от GitHub, здесь он ОБЯЗАТЕЛЕН для любого импорта
        // (у GitLab нет анонимного режима, см. GitlabImport.kt), поэтому логина в профиле нет —
        // только сам токен.
        post("/api/me/gitlab-token") {
            val body = call.receive<BindGitlabTokenRequest>()
            val token = body.token.trim()
            if (token.isEmpty()) return@post call.badRequest("Токен пуст")
            if (token.length > 255) return@post call.badRequest("Похоже, это не токен GitLab — слишком длинная строка")
            GitlabTokenRepository.save(call.userId(), token)
            val user = UserRepository.findById(call.userId())
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Пользователь не найден"))
            call.respond(user.toDto())
        }

        delete("/api/me/gitlab-token") {
            GitlabTokenRepository.remove(call.userId())
            val user = UserRepository.findById(call.userId())
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Пользователь не найден"))
            call.respond(user.toDto())
        }
    }
}

internal fun UserRow.toDto() = UserDto(
    id = id.toString(),
    email = email,
    displayName = displayName,
    githubLogin = githubLogin,
    githubTokenBound = GithubTokenRepository.isBound(id),
    gitlabTokenBound = GitlabTokenRepository.isBound(id),
)
