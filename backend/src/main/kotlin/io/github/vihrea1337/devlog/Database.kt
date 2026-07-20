package io.github.vihrea1337.devlog

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Подключение к PostgreSQL и создание таблиц.
 *
 * Настройки берутся из переменных окружения (для прода/Docker), а по умолчанию —
 * локальная база: порт 5433 (чтобы не конфликтовать с expenses-pg на 5432), БД "devlog".
 * HikariCP держит пул готовых подключений (открывать новое на каждый запрос дорого).
 * SchemaUtils.create = CREATE TABLE IF NOT EXISTS — создаёт таблицы, если их ещё нет.
 */
fun configureDatabase() {
    val config = HikariConfig().apply {
        jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/devlog"
        driverClassName = "org.postgresql.Driver"
        username = System.getenv("DB_USER") ?: "postgres"
        password = System.getenv("DB_PASSWORD") ?: "dev"
        maximumPoolSize = 5
    }
    Database.connect(HikariDataSource(config))

    transaction {
        SchemaUtils.create(Users, Projects, Entries, EntryStructured, Reports)
    }
}
