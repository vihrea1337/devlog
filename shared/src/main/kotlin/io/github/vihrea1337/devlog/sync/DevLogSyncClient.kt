package io.github.vihrea1337.devlog.sync

import io.github.vihrea1337.devlog.EntryChangesDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Тонкий клиент только для синхронизации записей (GET /api/entries/changes).
 * Остальные операции (вход, добавление, удаление записи) по-прежнему идут через
 * Retrofit в androidApp — переводить их сюда не требовалось этой задачей.
 *
 * engine передаётся параметром ради тестов (io.ktor:ktor-client-mock подменяет сеть);
 * в реальном приложении и в проде используется CIO — тот же движок, что и на бэкенде.
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

    fun close() = http.close()
}
