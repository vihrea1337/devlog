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
data class UserDto(val id: String, val email: String, val displayName: String)

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
)

/** Запись, которую мы ОТПРАВЛЯЕМ (id/статус/даты назначает сервер). */
@Serializable
data class NewEntry(
    val occurredOn: String,
    val rawText: String,
)
