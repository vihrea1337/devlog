package io.github.vihrea1337.devlog

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

// --- DTO записей ---

/** Тело POST /api/entries: сырьё за день. id/статус/даты назначает сервер. */
@Serializable
data class NewEntry(
    val occurredOn: String,               // "2026-07-20"
    val rawText: String,
    val projectId: String? = null,
    val sourceType: String = "manual",
    val timeSpentMin: Int? = null,
)

/** Тело PUT /api/entries/{id}: меняем только переданные поля (остальные — null = не трогать). */
@Serializable
data class UpdateEntry(
    val rawText: String? = null,
    val occurredOn: String? = null,
    val projectId: String? = null,
    val timeSpentMin: Int? = null,
)

/** Структурированная часть, которую заполняет ИИ. Значения по умолчанию — чтобы
 * устойчиво разбирать неполный JSON от модели (пропущенное поле = пусто). */
@Serializable
data class StructuredDto(
    val summary: String = "",
    val steps: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val outcome: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class EntryDto(
    val id: String,
    val projectId: String?,
    val occurredOn: String,
    val rawText: String,
    val source: String,
    val status: String,
    val timeSpentMin: Int?,
    val createdAt: String,
    val updatedAt: String,
    val structured: StructuredDto? = null,
)

/** Ручки записей — все под токеном и в контексте своего пользователя (call.userId()). */
fun Route.entryRoutes() = authenticate("auth-jwt") {

    get("/api/entries") {
        val from = call.request.queryParameters["from"]?.let(LocalDate::parse)
        val to = call.request.queryParameters["to"]?.let(LocalDate::parse)
        val projectId = call.request.queryParameters["projectId"]?.let(UUID::fromString)
        call.respond(EntryRepository.list(call.userId(), from, to, projectId))
    }

    post("/api/entries") {
        val body = call.receive<NewEntry>()
        if (body.rawText.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Текст записи пуст"))
            return@post
        }
        val saved = EntryRepository.create(call.userId(), body)
        // Поставить запись в очередь на обработку ИИ (в фоне; без ключа Groq — просто пропустится).
        AiProcessor.schedule(call.userId(), UUID.fromString(saved.id), saved.rawText)
        call.respond(saved)
    }

    get("/api/entries/{id}") {
        val id = call.entryId() ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный id"))
        val entry = EntryRepository.getById(call.userId(), id)
        if (entry == null) call.respond(HttpStatusCode.NotFound) else call.respond(entry)
    }

    put("/api/entries/{id}") {
        val id = call.entryId() ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный id"))
        val updated = EntryRepository.update(call.userId(), id, call.receive<UpdateEntry>())
        if (updated == null) call.respond(HttpStatusCode.NotFound) else call.respond(updated)
    }

    delete("/api/entries/{id}") {
        val id = call.entryId() ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный id"))
        val removed = EntryRepository.delete(call.userId(), id)
        call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }

    post("/api/entries/{id}/reprocess") {
        val id = call.entryId() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный id"))
        val entry = EntryRepository.getById(call.userId(), id)
        if (entry == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        EntryRepository.setStatus(call.userId(), id, "queued")
        AiProcessor.schedule(call.userId(), id, entry.rawText)
        call.respond(HttpStatusCode.Accepted)
    }
}

/** Разобрать path-параметр {id} в UUID; вернуть null, если он битый. */
private fun ApplicationCall.entryId(): UUID? =
    runCatching { UUID.fromString(parameters["id"]) }.getOrNull()
