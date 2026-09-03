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
 * Доступ к личному токену GitLab пользователя (таблица gitlab_integrations) — по образцу
 * GithubTokenRepository.kt, тот же TokenCipher (общий, шифрование не завязано на провайдера).
 *
 * ВАЖНО: [tokenFor] — единственное место, где расшифрованный токен покидает эту таблицу,
 * и он ДОЛЖЕН использоваться только для похода в GitLab API (см. GitlabImport.kt), никогда —
 * для логов или ответов клиенту. Наружу через API отдаётся только факт "привязан"/"не привязан"
 * ([isBound]) — см. UserDto.gitlabTokenBound в AuthRoutes.
 */
object GitlabTokenRepository {

    /** Привязать (или заменить) токен. */
    fun save(userId: UUID, token: String) = transaction {
        val encrypted = TokenCipher.encrypt(token)
        val updated = GitlabIntegrations.update({ GitlabIntegrations.userId eq userId }) {
            it[encryptedToken] = encrypted
            it[updatedAt] = Instant.now()
        }
        if (updated == 0) {
            GitlabIntegrations.insert {
                it[GitlabIntegrations.userId] = userId
                it[encryptedToken] = encrypted
                it[updatedAt] = Instant.now()
            }
        }
        Unit
    }

    /** Отвязать токен. true — что-то реально удалили (был привязан). */
    fun remove(userId: UUID): Boolean = transaction {
        GitlabIntegrations.deleteWhere { GitlabIntegrations.userId eq userId } > 0
    }

    fun isBound(userId: UUID): Boolean = transaction {
        GitlabIntegrations.selectAll().where { GitlabIntegrations.userId eq userId }.count() > 0
    }

    /**
     * Расшифрованный токен для похода в GitLab API. null — не привязан, или (крайне
     * маловероятно) строка в БД зашифрована другим JWT_SECRET, чем текущий, — тогда тоже
     * ведём себя как "не привязан", а не роняем импорт исключением.
     */
    fun tokenFor(userId: UUID): String? = transaction {
        GitlabIntegrations.selectAll().where { GitlabIntegrations.userId eq userId }
            .map { it[GitlabIntegrations.encryptedToken] }
            .singleOrNull()
    }?.let { TokenCipher.decrypt(it) }
}
