package io.github.vihrea1337.devlog

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

/**
 * Подключение к PostgreSQL и приведение схемы к актуальной версии.
 *
 * Настройки берутся из переменных окружения (для прода/Docker), а по умолчанию —
 * локальная база: порт 5433 (чтобы не конфликтовать с expenses-pg на 5432), БД "devlog".
 * HikariCP держит пул готовых подключений (открывать новое на каждый запрос дорого).
 *
 * Схему ведёт **Flyway** (см. migrateSchema ниже), а НЕ SchemaUtils.create, как было раньше:
 * SchemaUtils умеет только создавать отсутствующие таблицы и молча проходит мимо таблицы,
 * в которой не хватает колонки. То есть на боевой базе новая колонка не появилась бы никогда,
 * и сервер падал бы на запросах к ней.
 */
fun configureDatabase() {
    val config = HikariConfig().apply {
        jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/devlog"
        driverClassName = "org.postgresql.Driver"
        username = System.getenv("DB_USER") ?: "postgres"
        password = System.getenv("DB_PASSWORD") ?: "dev"
        maximumPoolSize = 5
    }
    val dataSource = HikariDataSource(config)
    migrateSchema(dataSource)
    Database.connect(dataSource)
}

/**
 * Накатить миграции из resources/db/migration (файлы вида `V2__описание.sql`, по возрастанию версии).
 * Какие версии уже применены, Flyway хранит в служебной таблице `flyway_schema_history` в самой базе,
 * поэтому повторный запуск сервера ничего не выполняет заново.
 *
 * `baselineOnMigrate` — про боевую базу, где таблицы уже созданы старым способом (Exposed).
 * Увидев непустую схему без истории миграций, Flyway отметит V1 как уже применённую (не выполняя её)
 * и накатит только V2 и дальше. На пустой базе (локально, новый сервер) выполнятся все миграции с V1.
 */
fun migrateSchema(dataSource: DataSource) {
    val result = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("1")
        // Падать сразу, если файл миграции назван не по правилам. Иначе Flyway молча
        // пропускает его — сервер стартует с пустой базой и валится на первом же запросе.
        .validateMigrationNaming(true)
        .load()
        .migrate()
    println(
        if (result.migrationsExecuted == 0) "Схема БД актуальна (версия ${result.targetSchemaVersion ?: "1"})."
        else "Применено миграций: ${result.migrationsExecuted}, схема БД версии ${result.targetSchemaVersion}.",
    )
}
