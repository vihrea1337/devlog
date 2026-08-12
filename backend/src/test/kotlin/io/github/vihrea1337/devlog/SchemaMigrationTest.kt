package io.github.vihrea1337.devlog

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Страховка от расхождения схемы: SQL-миграции (то, что реально накатывается на базу)
 * и описание таблиц в Tables.kt (то, чем пользуется код) должны совпадать.
 *
 * Как проверяем: на пустой H2 выполняем все миграции, а потом спрашиваем у Exposed
 * «каких таблиц и колонок тебе не хватает?». Список должен быть пустым. Если кто-то добавит
 * колонку в Tables.kt и забудет миграцию (или наоборот) — этот тест покраснеет сразу,
 * а не сервер в проде на первом же запросе.
 */
class SchemaMigrationTest {

    private val tables = arrayOf(Users, Projects, Entries, EntryStructured, Reports)

    @BeforeTest
    fun setup() {
        // Отдельная база в памяти, режим совместимости с PostgreSQL — чтобы понимала тот же SQL.
        Database.connect("jdbc:h2:mem:devlog_schema;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(Reports, EntryStructured, Entries, Projects, Users) }
    }

    @Test
    fun `миграции создают ровно ту схему, которую ожидает код`() {
        transaction {
            for (statement in migrationStatements()) exec(statement)
        }

        val missing = transaction { SchemaUtils.statementsRequiredToActualizeScheme(*tables) }
        assertTrue(
            missing.isEmpty(),
            "Схема из миграций не совпадает с Tables.kt. Не хватает:\n" + missing.joinToString("\n"),
        )
    }

    @Test
    fun `миграции применяются к базе, где уже что-то есть (повторный запуск)`() {
        transaction {
            for (statement in migrationStatements()) exec(statement)
        }
        // Все миграции написаны через IF NOT EXISTS, поэтому повторный прогон безвреден:
        // так ведёт себя рестарт сервера на уже мигрированной базе.
        transaction {
            for (statement in migrationStatements()) exec(statement)
        }
        val missing = transaction { SchemaUtils.statementsRequiredToActualizeScheme(*tables) }
        assertTrue(missing.isEmpty(), "После повторного прогона схема разъехалась: $missing")
    }

    /** Прочитать все файлы миграций по порядку версий и разбить их на отдельные SQL-команды. */
    private fun migrationStatements(): List<String> =
        migrationFiles()
            .map { name -> readResource("/db/migration/$name") }
            .flatMap { sql -> splitStatements(sql) }

    /** Имена файлов миграций в порядке версии: V1__..., V2__... */
    private fun migrationFiles(): List<String> {
        val dir = javaClass.getResource("/db/migration")?.toURI()?.let { java.io.File(it) }
        val names = dir?.list()?.toList().orEmpty().filter { it.endsWith(".sql") }
        assertTrue(names.isNotEmpty(), "Не найдено ни одного файла миграции в resources/db/migration")
        return names.sortedBy { it.substringAfter('V').substringBefore("__").toInt() }
    }

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("Не найден файл миграции $path")

    /** Убрать комментарии (-- ...) и разбить скрипт на команды по ';'. */
    private fun splitStatements(sql: String): List<String> = sql
        .lineSequence()
        .filterNot { it.trimStart().startsWith("--") }
        .joinToString("\n")
        .split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    // Exposed выполняет произвольный SQL через текущую транзакцию.
    private fun org.jetbrains.exposed.sql.Transaction.exec(statement: String) {
        TransactionManager.current().exec(statement)
    }
}
