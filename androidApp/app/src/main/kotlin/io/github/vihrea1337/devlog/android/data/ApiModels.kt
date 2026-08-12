package io.github.vihrea1337.devlog.android.data

import kotlinx.serialization.Serializable

/**
 * Модели данных для обмена с бэкендом DevLog (те же поля, что и в JSON сервера).
 * @Serializable разрешает kotlinx.serialization превращать эти классы в JSON и обратно.
 */

// --- Авторизация ---

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String = "")

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String,
    /** Логин на GitHub, если привязан (импорт коммитов делается на вебе). */
    val githubLogin: String? = null,
)

/** Ответ на регистрацию/вход: токен + кто вошёл. */
@Serializable
data class AuthResponse(val token: String, val user: UserDto)

// --- Записи ---

/** Что ИИ извлёк из записи. Значения по умолчанию — на случай неполного JSON. */
@Serializable
data class StructuredDto(
    val summary: String = "",
    val steps: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val outcome: String = "",
    val tags: List<String> = emptyList(),
)

/** Запись, которую сервер ПРИСЫЛАЕТ (с id, статусом обработки ИИ и, если готова, структурой). */
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
    /** Почему обработка ИИ не удалась — показываем прямо в карточке, а не молчим. */
    val aiError: String? = null,
    /** Удалённые записи приходят только в синхронизации; в ленте их нет. */
    val deleted: Boolean = false,
)

/** Проект — группировка записей. aiEnabled = false: записи проекта в ИИ не отправляются. */
@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val color: String? = null,
    val aiEnabled: Boolean = true,
    val archived: Boolean = false,
    val createdAt: String = "",
)

/** Запись, которую мы ОТПРАВЛЯЕМ (id/статус/даты назначает сервер). */
@Serializable
data class NewEntry(
    val occurredOn: String,
    val rawText: String,
    /** К какому проекту привязать запись; null — без проекта. */
    val projectId: String? = null,
    /**
     * id, придуманный клиентом. Сервер по нему поймёт повторную отправку и не создаст дубль —
     * телефон часто отправляет при плохой сети.
     */
    val id: String? = null,
)
