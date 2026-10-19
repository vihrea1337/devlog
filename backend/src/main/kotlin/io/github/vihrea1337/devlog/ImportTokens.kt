package io.github.vihrea1337.devlog

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Токены для скриптов — долгие ключи, которые умеют ТОЛЬКО импорт.
 *
 * Сценарий: файлы дня пишутся на ПК, и отправлять их в DevLog удобнее всего маленьким
 * скриптом (Планировщик заданий Windows, хук Claude Code) — без входа паролем. Обычный JWT
 * для этого плох: он открывает весь аккаунт (читать, удалять, делиться отчётами) и истекает
 * через 30 дней. Токен скрипта:
 * - пускает только в ручки импорта (`/api/import/day-files`, `/api/import/text`) — провайдер
 *   "import-token" подключён только к ним, остальные ручки его не принимают;
 * - не истекает, но отзывается одной кнопкой — без смены пароля;
 * - хранится в базе хешем (SHA-256): утечка базы не даёт рабочих токенов.
 *
 * Выпустить/отозвать токен может только сам владелец, вошедший обычным способом;
 * демо-вход «только для просмотра» — нет (его остановит ReadOnlyGuard).
 */

/** Кто пришёл с токеном скрипта — «принципал» Ktor для провайдера "import-token". */
data class ImportTokenPrincipal(val userId: UUID)

object ImportTokenRepository {
    /** Префикс видно глазами: «dli_…» — это токен DevLog для импорта, а не пароль. */
    const val PREFIX = "dli_"

    /** Сколько токенов на пользователя: ПК, ноутбук, хук — с запасом. */
    const val MAX_PER_USER = 10

    /** Время последнего использования обновляем не чаще раза в минуту — не пишем в базу на каждый запрос. */
    private val TOUCH_EVERY: Duration = Duration.ofMinutes(1)

    private val random = SecureRandom()

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Выпустить токен. Возвращает описание и сам токен — его больше никто не увидит. null — лимит. */
    fun create(userId: UUID, name: String): CreatedImportTokenDto? = transaction {
        val count = ImportTokens.selectAll().where { ImportTokens.userId eq userId }.count()
        if (count >= MAX_PER_USER) return@transaction null
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val id = UUID.randomUUID()
        val now = Instant.now()
        ImportTokens.insert {
            it[ImportTokens.id] = id
            it[ImportTokens.userId] = userId
            it[ImportTokens.name] = name
            it[tokenHash] = hash(token)
            it[createdAt] = now
        }
        CreatedImportTokenDto(token, ImportTokenDto(id.toString(), name, now.toString(), null))
    }

    fun list(userId: UUID): List<ImportTokenDto> = transaction {
        ImportTokens.selectAll().where { ImportTokens.userId eq userId }
            .orderBy(ImportTokens.createdAt to SortOrder.DESC)
            .map {
                ImportTokenDto(
                    id = it[ImportTokens.id].toString(),
                    name = it[ImportTokens.name],
                    createdAt = it[ImportTokens.createdAt].toString(),
                    lastUsedAt = it[ImportTokens.lastUsedAt]?.toString(),
                )
            }
    }

    fun delete(userId: UUID, id: UUID): Boolean = transaction {
        ImportTokens.deleteWhere { (ImportTokens.id eq id) and (ImportTokens.userId eq userId) } > 0
    }

    /** Чей это токен. null — такого нет (отозван или выдуман). Заодно отмечает использование. */
    fun userFor(token: String): UUID? {
        if (!token.startsWith(PREFIX)) return null
        val h = hash(token)
        return transaction {
            val row = ImportTokens.selectAll().where { ImportTokens.tokenHash eq h }.singleOrNull() ?: return@transaction null
            val now = Instant.now()
            val last = row[ImportTokens.lastUsedAt]
            if (last == null || last.isBefore(now.minus(TOUCH_EVERY))) {
                ImportTokens.update({ ImportTokens.id eq row[ImportTokens.id] }) { it[lastUsedAt] = now }
            }
            row[ImportTokens.userId]
        }
    }
}

/** Выдача, список и отзыв токенов — только под обычным входом (JWT), не под самим токеном скрипта. */
fun Route.importTokenRoutes() = authenticate("auth-jwt") {
    get("/api/me/import-tokens") {
        call.respond(ImportTokenRepository.list(call.userId()))
    }

    post("/api/me/import-tokens") {
        val name = call.receive<NewImportTokenRequest>().name.trim().take(60)
        if (name.isEmpty()) return@post call.badRequest("Назовите токен — например, «Домашний ПК»: так потом понятно, какой отзывать")
        val created = ImportTokenRepository.create(call.userId(), name)
            ?: return@post call.badRequest("Не больше ${ImportTokenRepository.MAX_PER_USER} токенов — отзовите ненужный")
        call.respond(created)
    }

    delete("/api/me/import-tokens/{id}") {
        val id = call.parameters["id"]?.let(::parseUuidOrNull) ?: return@delete call.badRequest("Некорректный id")
        call.respond(if (ImportTokenRepository.delete(call.userId(), id)) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }
}
