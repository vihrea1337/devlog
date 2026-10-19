package io.github.vihrea1337.devlog

import kotlinx.serialization.Serializable

/**
 * КОНТРАКТ API DevLog — то, что летает между сервером и клиентами в JSON.
 *
 * Зачем отдельный модуль: раньше эти же классы были описаны трижды — на бэкенде,
 * в Android-приложении и в JavaScript веб-страницы. Достаточно было добавить поле
 * на сервере и забыть про Android, чтобы клиенты разъехались с сервером молча.
 * Теперь определение одно, и оба Kotlin-проекта используют именно его: несовпадение
 * полей превращается из ошибки в рантайме в ошибку компиляции.
 *
 * Пакет специально тот же, что у бэкенда, — чтобы серверному коду не пришлось
 * ничего импортировать.
 *
 * Правила для этого файла:
 * - только данные, никакой логики и никаких серверных типов (ни Exposed, ни Ktor);
 * - у необязательных полей — значения по умолчанию, иначе старый клиент не разберёт
 *   ответ нового сервера;
 * - даты и id — строками (ISO-8601 и UUID): так одинаково понятно и Kotlin, и JavaScript.
 *
 * _Веб-страница пока разбирает JSON сама: чистый JS без сборки — осознанный выбор,
 * поэтому её на этот модуль не перевести._
 */

// --- Пользователь и вход ---

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String = "")

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String,
    /** Логин на GitHub, если привязан: с ним работает импорт коммитов. */
    val githubLogin: String? = null,
    /**
     * Привязан ли личный токен GitHub (для импорта ПРИВАТНЫХ репозиториев). Сам токен
     * сюда никогда не попадает — только факт привязки, см. POST/DELETE /api/me/github-token.
     */
    val githubTokenBound: Boolean = false,
    /**
     * Привязан ли личный токен GitLab. В отличие от GitHub, у GitLab нет отдельного
     * "логина" в настройках — токен обязателен для импорта вообще, а не только для
     * приватных репозиториев (см. GitlabImport.kt), поэтому логина здесь нет.
     */
    val gitlabTokenBound: Boolean = false,
    /** Вход через демо-кнопку: только просмотр, любые изменения сервер отклонит (403). */
    val readOnly: Boolean = false,
)

/** Ответ GET /api/auth/demo: включён ли на сервере демо-вход (кнопка на экране входа). */
@Serializable
data class DemoAvailabilityDto(val enabled: Boolean)

/** Тело POST /api/me/github-token: привязать (или заменить) личный токен GitHub. */
@Serializable
data class BindGithubTokenRequest(val token: String)

/** Тело POST /api/me/gitlab-token: привязать (или заменить) личный токен GitLab. */
@Serializable
data class BindGitlabTokenRequest(val token: String)

/** Тело PUT /api/me: пока меняем только привязку к GitHub. Пустая строка — отвязать. */
@Serializable
data class UpdateProfile(val githubLogin: String? = null)

/** Ответ на регистрацию/вход: токен + кто вошёл. */
@Serializable
data class AuthResponse(val token: String, val user: UserDto)

/** Единый формат ошибки. */
@Serializable
data class ErrorResponse(val error: String)

/**
 * Настройки сервера, важные клиенту. Главное — включён ли ИИ: без ключа записи честно
 * висят в очереди, и интерфейс должен писать «ИИ выключен», а не притворяться, что вот-вот
 * обработает.
 */
@Serializable
data class ServerConfigDto(val aiEnabled: Boolean, val aiDailyLimit: Int)

// --- Записи ---

/** Что ИИ извлёк из записи. Значения по умолчанию — чтобы устойчиво разбирать неполный JSON. */
@Serializable
data class StructuredDto(
    val summary: String = "",
    val steps: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val outcome: String = "",
    val tags: List<String> = emptyList(),
)

/** Запись дневника, как её отдаёт сервер. */
@Serializable
data class EntryDto(
    val id: String,
    val projectId: String? = null,
    val occurredOn: String,
    val rawText: String,
    val source: String = "manual",
    val status: String,
    val timeSpentMin: Int? = null,
    val createdAt: String,
    val updatedAt: String,
    val structured: StructuredDto? = null,
    /** Почему обработка ИИ не удалась (для статуса failed). null — ошибок не было. */
    val aiError: String? = null,
    /** Запись удалена. В обычной ленте таких нет — они приходят только в синхронизации. */
    val deleted: Boolean = false,
)

/** Тело POST /api/entries: сырьё за день. Статус и даты назначает сервер. */
@Serializable
data class NewEntry(
    val occurredOn: String,               // "2026-07-20"
    val rawText: String,
    val projectId: String? = null,
    val sourceType: String = "manual",
    val timeSpentMin: Int? = null,
    /**
     * id, придуманный клиентом (UUID). Нужен для повторной отправки при плохой сети:
     * сервер по нему поймёт, что это та же самая запись, и не создаст дубль.
     * Не прислали — сервер придумает свой.
     */
    val id: String? = null,
)

/** Тело PUT /api/entries/{id}: меняем только переданные поля (остальные — null = не трогать). */
@Serializable
data class UpdateEntry(
    val rawText: String? = null,
    val occurredOn: String? = null,
    val projectId: String? = null,
    val timeSpentMin: Int? = null,
)

/** Ответ синхронизации: изменения по возрастанию updatedAt + время сервера. */
@Serializable
data class EntryChangesDto(
    val entries: List<EntryDto>,
    val serverTime: String,
    /** true — упёрлись в лимит, надо запросить следующую порцию с новым since. */
    val hasMore: Boolean,
)

/**
 * Событие живых обновлений (SSE): с записью что-то произошло. Данных записи не шлём —
 * только «сходи обнови», чтобы событие не приходилось держать в актуальном виде.
 */
@Serializable
data class EntryEvent(val entryId: String, val status: String)

// --- Проекты ---

/** Тело POST /api/projects. Цвет и выключатель ИИ — необязательны. */
@Serializable
data class NewProject(val name: String, val color: String? = null, val aiEnabled: Boolean = true)

/**
 * Тело PUT /api/projects/{id}: меняем только переданные поля.
 *
 * githubExportRepo/githubExportPath — как color: null = не трогать, пустая строка = очистить
 * (отключить экспорт). Формат репозитория — "owner/repo", путь — обычный путь в репозитории
 * (например "docs/devlog.md"); пустой путь при непустом репозитории означает "путь по умолчанию" —
 * подставляется на сервере (см. GithubExporter.DEFAULT_EXPORT_PATH).
 */
@Serializable
data class UpdateProject(
    val name: String? = null,
    val color: String? = null,
    val aiEnabled: Boolean? = null,
    val archived: Boolean? = null,
    val githubExportRepo: String? = null,
    val githubExportPath: String? = null,
)

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val color: String? = null,
    val aiEnabled: Boolean = true,
    val archived: Boolean = false,
    val createdAt: String = "",
    /** Репозиторий ("owner/repo") для экспорта отчётов; null — экспорт выключен. */
    val githubExportRepo: String? = null,
    /** Путь до файла-changelog внутри репозитория; null вместе с непустым githubExportRepo
     *  означает путь по умолчанию (docs/devlog.md) — конкретное значение решает сервер. */
    val githubExportPath: String? = null,
)

// --- Отчёты за период ---

/** Тело POST /api/reports: за какой период и по какому проекту собрать отчёт. */
@Serializable
data class NewReport(
    val periodStart: String,      // "2026-07-01"
    val periodEnd: String,        // "2026-07-31"
    val projectId: String? = null,
    val title: String? = null,
    val format: String = "markdown",
    /**
     * Вид отчёта: "default" — по темам (Кратко / Результаты / Решения / Проблемы), "employer" —
     * «Отчёт работодателю»: Сделано / Проверено / Передано другим / Не сделано и почему, цифры
     * периода сложены кодом из разделов «Цифры» записей дня.
     */
    val template: String = "default",
    /**
     * Собрать из уже готовых (и вычитанных) недельных отчётов за этот период, а не из всех
     * записей сразу: длинный месяц иначе не влезает в лимит бесплатного ИИ и теряет детали.
     * Дни, не покрытые недельными отчётами, добавляются из записей.
     */
    val fromWeeklyReports: Boolean = false,
)

/** Готовый отчёт. */
@Serializable
data class ReportDto(
    val id: String,
    val projectId: String? = null,
    val title: String,
    val periodStart: String,
    val periodEnd: String,
    val format: String,
    val contentMd: String,
    /** Токен публичной ссылки `/r/{token}`; null — ссылка не выдана или отозвана. */
    val shareToken: String? = null,
    val createdAt: String,
    /**
     * Тот же отчёт, но уже в HTML — чтобы клиент показывал человеческий документ,
     * а не исходник Markdown со звёздочками. Собирает его сервер тем же кодом, что
     * и публичную страницу `/r/{token}`: конвертер Markdown живёт в одном месте,
     * а не дублируется на JavaScript в браузере и на Kotlin в приложении.
     */
    val contentHtml: String = "",
)

/** Тело PUT /api/reports/{id}: правка отчёта перед отправкой работодателю. */
@Serializable
data class UpdateReport(val contentMd: String, val title: String? = null)

/** Ответ на «поделиться»: токен и ОТНОСИТЕЛЬНАЯ ссылка публичной страницы («/r/…»). */
@Serializable
data class ShareResponse(val shareToken: String, val url: String)

// --- Статистика активности (календарь года и серии дней) ---

@Serializable
data class ActivityStatsDto(
    /** Дата (ГГГГ-ММ-ДД) → сколько записей в этот день. Дни без записей не передаём. */
    val days: Map<String, Int>,
    /** Сколько дней подряд ведётся дневник прямо сейчас. */
    val currentStreak: Int,
    /** Самая длинная серия за период. */
    val longestStreak: Int,
    val totalEntries: Int,
    val activeDays: Int,
    val from: String,
    val to: String,
)

// --- Импорт коммитов с GitHub ---

@Serializable
data class GithubImportRequest(
    val periodStart: String,
    val periodEnd: String,
    val projectId: String? = null,
    /**
     * "владелец/репозиторий" — режим «один репозиторий»: коммиты берутся из него напрямую
     * (`/repos/{repo}/commits?author=…`), а не из ленты событий. Нет лимита ~90 дней ленты,
     * видны и чужие репозитории, куда пользователь коммитит (с токеном — и приватные).
     * null/пусто — прежний режим по ленте событий.
     */
    val repo: String? = null,
)

@Serializable
data class ImportResultDto(
    val days: Int,
    val commits: Int,
    val created: Int,
    val skipped: Int,
    /** Догрузка коммитов остановилась раньше конца периода — упёрлись в лимит запросов. */
    val truncated: Boolean = false,
    /** Сколько уже импортированных дней обновлено (за день появились новые коммиты). */
    val updated: Int = 0,
)

// --- Импорт файлов дня (записи вида «ГГГГ-ММ-ДД.md» с разделами «Сделано», «Проверено»…) ---

/** Один файл: имя (из него берётся дата) и содержимое. */
@Serializable
data class DayFileDto(val name: String, val text: String)

/** Тело POST /api/import/day-files: сразу несколько файлов (например, папка месяца). */
@Serializable
data class DayFilesImportRequest(
    val files: List<DayFileDto>,
    val projectId: String? = null,
    /**
     * Заменить файлом и те записи, что правили руками в DevLog или удалили. По умолчанию
     * false: повторный (в т.ч. автоматический) импорт не должен молча терять ручные правки.
     */
    val overwrite: Boolean = false,
)

/**
 * Что стало с одним файлом. status:
 * created — новая запись; updated — файл дописан, запись обновлена; unchanged — ничего не
 * поменялось; edited — запись правили руками в DevLog, импорт её не перезаписал; deleted —
 * запись удалена пользователем, не воскрешаем; error — файл не принят (см. message).
 */
@Serializable
data class DayFileResultDto(
    val name: String,
    val date: String? = null,
    val status: String,
    /** Разделы записи разобраны кодом (без ИИ). false — запись ушла в очередь ИИ/черновик. */
    val parsedByCode: Boolean = false,
    val message: String? = null,
)

@Serializable
data class DayFilesImportResultDto(
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val failed: Int,
    val files: List<DayFileResultDto>,
)

// --- Импорт коммитов с GitLab (та же форма результата — ImportResultDto выше) ---

/** Тело POST /api/import/gitlab: без логина — токен обязателен, GET /events сам возвращает его владельца. */
@Serializable
data class GitlabImportRequest(
    val periodStart: String,
    val periodEnd: String,
    val projectId: String? = null,
)

/** Тело POST /api/import/text: любой текстовый лог с датами (ГГГГ-ММ-ДД) внутри строк. */
@Serializable
data class TextImportRequest(val text: String, val projectId: String? = null)

@Serializable
data class TextImportResultDto(
    val days: Int,
    /** Сколько строк вообще попало хоть в один день (для обратной связи пользователю). */
    val linesMatched: Int,
    val created: Int,
    val skipped: Int,
)

// --- Экспорт отчёта в GitHub (обратная сторона импорта коммитов) ---

/** Ответ POST /api/reports/{id}/export-github: куда именно записали. */
@Serializable
data class ExportGithubResultDto(val repo: String, val path: String)
