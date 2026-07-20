package io.github.vihrea1337.devlog

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/** Строка таблицы users в удобном виде (без деталей Exposed). */
data class UserRow(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val displayName: String,
)

/**
 * Доступ к таблице users. Вся работа с БД — внутри transaction { } (обязательно для Exposed).
 * Наружу отдаём простые UserRow, чтобы остальной код не зависел от деталей БД.
 */
object UserRepository {
    fun findByEmail(email: String): UserRow? = transaction {
        Users.selectAll().where { Users.email eq email }
            .map { it.toUserRow() }
            .singleOrNull()
    }

    fun findById(id: UUID): UserRow? = transaction {
        Users.selectAll().where { Users.id eq id }
            .map { it.toUserRow() }
            .singleOrNull()
    }

    fun create(email: String, passwordHash: String, displayName: String): UserRow = transaction {
        val newId = UUID.randomUUID()
        Users.insert {
            it[id] = newId
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
            it[Users.displayName] = displayName
            it[createdAt] = Instant.now()
        }
        UserRow(newId, email, passwordHash, displayName)
    }

    private fun ResultRow.toUserRow() = UserRow(
        id = this[Users.id],
        email = this[Users.email],
        passwordHash = this[Users.passwordHash],
        displayName = this[Users.displayName],
    )
}
