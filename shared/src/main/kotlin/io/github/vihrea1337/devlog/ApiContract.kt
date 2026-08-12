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
)

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

/** Тело PUT /api/projects/{id}: меняем только переданные поля. */
@Serializable
data class UpdateProject(
    val name: String? = null,
    val color: String? = null,
    val aiEnabled: Boolean? = null,
    val archived: Boolean? = null,
)

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val color: String? = null,
    val aiEnabled: Boolean = true,
    val archived: Boolean = false,
    val createdAt: String = "",
)

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
)

@Serializable
data class ImportResultDto(
    val days: Int,
    val commits: Int,
    val created: Int,
    val skipped: Int,
)
