package io.github.vihrea1337.devlog

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Доступ к личному токену GitHub пользователя (таблица github_integrations).
 *
 * ВАЖНО: [tokenFor] — единственное место, где расшифрованный токен покидает эту таблицу,
 * и он ДОЛЖЕН использоваться только для похода в GitHub API (см. GithubImport.kt), никогда —
 * для логов или ответов клиенту. Наружу через API отдаётся только факт "привязан"/"не привязан"
 * ([isBound]) — см. UserDto.githubTokenBound в ImportRoutes/AuthRoutes.
 */
object GithubTokenRepository {

    /** Привязать (или заменить) токен. */
    fun save(userId: UUID, token: String) = transaction {
        val encrypted = TokenCipher.encrypt(token)
        val updated = GithubIntegrations.update({ GithubIntegrations.userId eq userId }) {
            it[encryptedToken] = encrypted
            it[updatedAt] = Instant.now()
        }
        if (updated == 0) {
            GithubIntegrations.insert {
                it[GithubIntegrations.userId] = userId
                it[encryptedToken] = encrypted
                it[updatedAt] = Instant.now()
            }
        }
        Unit
    }

    /** Отвязать токен. true — что-то реально удалили (был привязан). */
    fun remove(userId: UUID): Boolean = transaction {
        GithubIntegrations.deleteWhere { GithubIntegrations.userId eq userId } > 0
    }

    fun isBound(userId: UUID): Boolean = transaction {
        GithubIntegrations.selectAll().where { GithubIntegrations.userId eq userId }.count() > 0
    }

    /**
     * Расшифрованный токен для похода в GitHub API. null — не привязан, или (крайне
     * маловероятно) строка в БД зашифрована другим JWT_SECRET, чем текущий, — тогда тоже
     * ведём себя как "не привязан", а не роняем импорт исключением.
     */
    fun tokenFor(userId: UUID): String? = transaction {
        GithubIntegrations.selectAll().where { GithubIntegrations.userId eq userId }
            .map { it[GithubIntegrations.encryptedToken] }
            .singleOrNull()
    }?.let { TokenCipher.decrypt(it) }
}
