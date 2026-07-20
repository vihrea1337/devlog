package io.github.vihrea1337.devlog

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date
import java.util.UUID

/**
 * Выдача и проверка JWT (JSON Web Token). Токен — это подписанный сервером "пропуск":
 * клиент входит по паролю, получает токен и дальше шлёт его в каждом запросе. Внутри
 * лежит id пользователя (claim "userId"); подпись не даёт токен подделать.
 *
 * Секрет подписи берётся из переменной окружения JWT_SECRET (в проде — обязательно свой!),
 * локально — дефолт для разработки.
 */
object JwtService {
    private val secret = System.getenv("JWT_SECRET") ?: "dev-secret-change-me"
    private val algorithm = Algorithm.HMAC256(secret)

    const val ISSUER = "devlog"          // кто выдал токен
    const val AUDIENCE = "devlog-users"  // для кого он

    private const val VALIDITY_MS = 30L * 24 * 60 * 60 * 1000 // 30 дней

    /** Проверяльщик: подпись верна + правильные issuer/audience + не истёк. */
    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .build()

    fun makeToken(userId: UUID, email: String): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withClaim("userId", userId.toString())
        .withClaim("email", email)
        .withExpiresAt(Date(System.currentTimeMillis() + VALIDITY_MS))
        .sign(algorithm)
}
