package io.github.vihrea1337.devlog

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Схема базы данных (чертёж таблиц). Каждый `object : Table` = одна таблица в PostgreSQL.
 * Подробное описание полей и связей — в docs/data-model.md.
 *
 * Важно: это НЕ данные, а описание структуры. Exposed по нему умеет и создать таблицы
 * (SchemaUtils.create), и строить типобезопасные запросы.
 */

/** Пользователи (аккаунты). С них начинается изоляция: каждый видит только своё. */
object Users : Table("users") {
    val id = uuid("id")
    val email = varchar("email", 255).uniqueIndex()   // логин, уникальный
    val passwordHash = varchar("password_hash", 255)   // хеш пароля, НЕ открытый пароль
    val displayName = varchar("display_name", 100)
    // Логин на GitHub — чтобы подтягивать коммиты как сырьё для дневника.
    // Токен не храним: берём только публичную активность.
    val githubLogin = varchar("github_login", 39).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Проекты — группировка записей. У проекта есть выключатель ИИ (приватность). */
object Projects : Table("projects") {
    val id = uuid("id")
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 120)
    val color = varchar("color", 9).nullable()          // hex-цвет для интерфейса
    val aiEnabled = bool("ai_enabled").default(true)
    val archived = bool("archived").default(false)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Записи — сырьё за день. raw_text как ввели/импортировали; status ведёт обработку ИИ. */
object Entries : Table("entries") {
    val id = uuid("id")
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    // Запись может быть вне проекта → nullable; при удалении проекта запись остаётся (SET_NULL).
    val projectId = reference("project_id", Projects.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val occurredOn = date("occurred_on")                // за какой ДЕНЬ работа (не дата создания)
    val rawText = text("raw_text")
    val sourceType = varchar("source", 20).default("manual") // manual | github | claude | import ("source" занято Exposed)
    val sourceRef = varchar("source_ref", 500).nullable()  // ссылка на коммит/URL источника
    val status = varchar("status", 20).default("queued")   // draft | queued | processing | structured | failed
    val timeSpentMin = integer("time_spent_min").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")                // для синхронизации клиентов

    // --- Очередь обработки ИИ (её ведёт AiWorker) ---
    val aiAttempts = integer("ai_attempts").default(0)     // сколько раз пытались обработать
    val aiError = text("ai_error").nullable()              // текст последней ошибки (почему failed)
    val aiStartedAt = timestamp("ai_started_at").nullable() // когда взяли в работу (ловим зависшие)

    // Мягкое удаление: строка остаётся «надгробием», чтобы другие устройства узнали,
    // что запись удалили. Содержимое при удалении стирается.
    val deletedAt = timestamp("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // Индекс под ленту и сборку отчёта за период: выбираем записи пользователя по дате.
        index(false, userId, occurredOn)
        // Индекс под запрос воркера: «дай записи, ждущие обработки».
        index(false, status)
        // Индекс под синхронизацию: «что изменилось после такого-то момента».
        index(false, userId, updatedAt)
    }
}

/** Что ИИ извлёк из записи (связь один-к-одному с entries). */
object EntryStructured : Table("entry_structured") {
    val entryId = reference("entry_id", Entries.id, onDelete = ReferenceOption.CASCADE)
    val summary = text("summary")                         // суть одной фразой
    // Массивы (шаги/решения/проблемы/теги) храним JSON-строкой в text — просто и переносимо
    // (одинаково на Postgres и на H2 в тестах); кодирование/декодирование — в репозитории.
    val steps = text("steps")
    val decisions = text("decisions")
    val problems = text("problems")
    val outcome = text("outcome")
    val tags = text("tags")
    val aiModel = varchar("ai_model", 60)                 // какая модель обработала
    val processedAt = timestamp("processed_at")
    override val primaryKey = PrimaryKey(entryId)         // тот же id, что у записи
}

/** Отчёты за период. share_token — для публичной ссылки работодателю. */
object Reports : Table("reports") {
    val id = uuid("id")
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val projectId = reference("project_id", Projects.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val title = varchar("title", 200)
    val periodStart = date("period_start")
    val periodEnd = date("period_end")
    val format = varchar("format", 20).default("markdown")  // markdown | pdf | html
    val contentMd = text("content_md")                      // сгенерированный ИИ Markdown
    val shareToken = varchar("share_token", 64).uniqueIndex().nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
