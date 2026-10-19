package io.github.vihrea1337.devlog

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Sequence
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Источник значений sync_seq (см. Entries.syncSeq) — строго монотонный курсор синхронизации,
 * в отличие от updated_at (timestamp может повториться у нескольких записей).
 */
val EntriesSyncSeq = Sequence("entries_sync_seq")

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

/**
 * Персональный токен GitHub — по одному на пользователя, для импорта ПРИВАТНЫХ репозиториев
 * (без токена импорт видит только публичную активность). Отдельная таблица, а не колонка
 * в Users: изолирует секрет от общей таблицы аккаунтов, "привязать/отвязать" — самостоятельная
 * операция. Значение хранится зашифрованным (см. TokenCipher.kt) — здесь только шифротекст.
 */
object GithubIntegrations : Table("github_integrations") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val encryptedToken = text("encrypted_token")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

/**
 * Персональный токен GitLab — тот же принцип, что у [GithubIntegrations], но у GitLab
 * токен не опционален: в отличие от GitHub, у GitLab Events API нет анонимного/публичного
 * режима (см. GitlabImport.kt) — без токена импортировать вообще нечего.
 */
object GitlabIntegrations : Table("gitlab_integrations") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val encryptedToken = text("encrypted_token")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

/**
 * Токены для скриптов (см. ImportTokens.kt, миграция V9): умеют только импорт. Хранится хеш,
 * сам токен показывается один раз при выдаче.
 */
object ImportTokens : Table("import_tokens") {
    val id = uuid("id")
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 60)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
    }
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
    // Экспорт отчётов проекта в GitHub (см. GithubExporter.kt): "owner/repo" и путь до файла
    // внутри него. Оба nullable — по умолчанию экспорт выключен, включается в настройках проекта.
    val githubExportRepo = varchar("github_export_repo", 200).nullable()
    val githubExportPath = varchar("github_export_path", 300).nullable()
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
    val updatedAt = timestamp("updated_at")                // для отображения человеку
    // Курсор синхронизации: строго монотонный (см. EntriesSyncSeq выше), а не updated_at —
    // тот может совпасть у нескольких записей и сломать пагинацию "since X".
    // .autoIncrement(EntriesSyncSeq) — не только для инсерта "бесплатным" значением
    // по умолчанию (мы всё равно проставляем его явно и при insert, и при update),
    // а прежде всего чтобы SchemaUtils.create() в тестах создавал саму последовательность —
    // без этой привязки Exposed не знает, что entries_sync_seq вообще нужно создать.
    val syncSeq = long("sync_seq").autoIncrement(EntriesSyncSeq)

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
    // Кем собран: "day" | "week" | "month" — автоматикой (Automation.kt, V10); null — руками.
    val autoKind = varchar("auto_kind", 10).nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Автоматика отчётов (см. Automation.kt, миграция V10): одна строка на пользователя —
 * что сервер делает сам по расписанию и чем это кончилось в последний раз.
 */
object ReportAutomation : Table("report_automation") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val projectId = reference("project_id", Projects.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val githubRepo = varchar("github_repo", 140).nullable()
    val dailyReport = bool("daily_report").default(false)
    val weeklyReport = bool("weekly_report").default(false)
    val monthlyReport = bool("monthly_report").default(false)
    val timeZone = varchar("time_zone", 40).default("Europe/Moscow")
    val lastCommitImportOn = date("last_commit_import_on").nullable()
    val lastCommitImportResult = varchar("last_commit_import_result", 300).nullable()
    val lastDailyOn = date("last_daily_on").nullable()       // за какой день собран последний дневной
    val lastWeeklyOn = date("last_weekly_on").nullable()     // понедельник последней собранной недели
    val lastMonthly = varchar("last_monthly", 7).nullable()  // «2026-10»
    val lastReportResult = varchar("last_report_result", 300).nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}
