package io.github.vihrea1337.devlog.android

import io.github.vihrea1337.devlog.ErrorResponse
import kotlinx.serialization.json.Json

// Общие для всех экранов тексты ошибок. Раньше эта функция жила внутри ReportsViewModel
// (private), а экран входа и лента показывали одно и то же «Не удалось…» на любую причину —
// и на короткий пароль, и на отсутствие сети. Теперь все экраны берут причину отсюда.

/**
 * Сообщение об ошибке человеческим языком. Retrofit на ответ 4xx/5xx бросает
 * HttpException с текстом вида «HTTP 400 Bad Request» — сам по себе он ничего
 * не объясняет. Но сервер кладёт причину в тело ответа (`{"error": "…"}`),
 * поэтому сначала пробуем достать её оттуда.
 */
internal fun Exception.humanMessage(): String = when (this) {
    is java.net.SocketTimeoutException -> "Сервер долго не отвечает — попробуйте ещё раз"
    is java.io.IOException -> "Нет связи с сервером"
    is retrofit2.HttpException -> serverError() ?: message ?: "Ошибка сервера"
    else -> message ?: "Неизвестная ошибка"
}

/**
 * Текст ошибки из тела ответа. `errorBody()` читается один раз и может быть чем
 * угодно (например, HTML от прокси), поэтому всё в runCatching: не смогли
 * разобрать — вернём null, и наверху останется код HTTP.
 */
private fun retrofit2.HttpException.serverError(): String? = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    errorJson.decodeFromString(ErrorResponse.serializer(), body).error.ifBlank { null }
}.getOrNull()

/** Один разборщик на всё приложение: создавать Json на каждую ошибку дорого. */
private val errorJson = Json { ignoreUnknownKeys = true }
