package io.github.vihrea1337.devlog.sync

import io.github.vihrea1337.devlog.EntryChangesDto
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.NewEntry
import io.github.vihrea1337.devlog.UpdateEntry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Клиент для синхронизации записей: чтение изменений (since-протокол) и запись
 * (создание/правка/удаление) — то, что нужно офлайн-очереди в EntryRepository. Вход, отчёты,
 * проекты и т.п. — в DevLogApi в androidApp, на том же движке Ktor (один движок на приложение).
 *
 * engine передаётся параметром ради тестов (io.ktor:ktor-client-mock подменяет сеть);
 * по умолчанию — CIO; Android передаёт свой движок OkHttp (TLS платформы).
 */
class DevLogSyncClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    engine: HttpClientEngine = CIO.create(),
) {
    private val http = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /**
     * Одна "страница" изменений с сервера. since = null — самая первая синхронизация
     * (запросить всё). Если hasMore = true в ответе — нужно вызвать ещё раз с новым since
     * (это делает EntryRepository.sync(), а не этот класс).
     */
    suspend fun fetchChanges(since: String?, limit: Int = 200): EntryChangesDto {
        val base = baseUrl.trimEnd('/')
        return http.get("$base/api/entries/changes") {
            since?.let { parameter("since", it) }
            parameter("limit", limit)
            tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }.body()
    }

    /**
     * POST /api/entries. newEntry.id задан клиентом заранее — сервер идемпотентен по нему,
     * поэтому повторный вызов после сетевого сбоя (не дошёл ответ, а не сам запрос)
     * не создаст вторую запись, а просто вернёт ту же самую.
     */
    suspend fun createEntry(newEntry: NewEntry): EntryDto {
        val base = baseUrl.trimEnd('/')
        return http.post("$base/api/entries") {
            contentType(ContentType.Application.Json)
            tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(newEntry)
        }.body()
    }

    /**
     * PUT /api/entries/{id} — правка текста офлайн-очередью (см. EntryRepository.enqueueUpdate).
     * Сервер сам ставит запись обратно в очередь на ИИ-обработку при смене текста — здесь
     * просто отдаём то, что он ответил (уже с новым статусом).
     */
    suspend fun updateEntry(id: String, rawText: String): EntryDto {
        val base = baseUrl.trimEnd('/')
        return http.put("$base/api/entries/$id") {
            contentType(ContentType.Application.Json)
            tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(UpdateEntry(rawText = rawText))
        }.body()
    }

    /**
     * DELETE /api/entries/{id}. 404 (записи уже нет) считаем успехом наравне с 204 —
     * цель операции ("этой записи на сервере быть не должно") в обоих случаях достигнута,
     * и повторять запрос до бесконечности незачем.
     */
    suspend fun deleteEntry(id: String) {
        val base = baseUrl.trimEnd('/')
        val response = http.delete("$base/api/entries/$id") {
            tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        when (response.status) {
            HttpStatusCode.NoContent, HttpStatusCode.NotFound -> Unit
            else -> error("Не удалось удалить запись $id: HTTP ${response.status}")
        }
    }

    fun close() = http.close()
}
