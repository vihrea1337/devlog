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
    /** Почему обработка ИИ не удалась (для статуса failed). null — ошибок не было. */
    val aiError: String? = null,
    /** Запись удалена. В обычной ленте таких нет — они приходят только в синхронизации. */
    val deleted: Boolean = false,
)

/** Ответ синхронизации: изменения по возрастанию updatedAt + время сервера. */
@Serializable
data class EntryChangesDto(
    val entries: List<EntryDto>,
    val serverTime: String,
    /** true — упёрлись в лимит, надо запросить следующую порцию с новым since. */
    val hasMore: Boolean,
)

/** Ручки записей — все под токеном и в контексте своего пользователя (call.userId()). */
fun Route.entryRoutes() = authenticate("auth-jwt") {

    get("/api/entries") {
        // Кривой параметр в адресе — ошибка клиента (400), а не поломка сервера (500).
        val fromRaw = call.request.queryParameters["from"]
        val toRaw = call.request.queryParameters["to"]
        val projectRaw = call.request.queryParameters["projectId"]
        val from = fromRaw?.let { parseDateOrNull(it) ?: return@get call.badRequest("Некорректная дата from") }
        val to = toRaw?.let { parseDateOrNull(it) ?: return@get call.badRequest("Некорректная дата to") }
        val projectId = projectRaw?.takeIf { it.isNotBlank() }?.let {
            parseUuidOrNull(it) ?: return@get call.badRequest("Некорректный id проекта")
        }
        val search = call.request.queryParameters["q"]?.take(200)
        val tag = call.request.queryParameters["tag"]?.take(60)
        call.respond(EntryRepository.list(call.userId(), from, to, projectId, search, tag))
    }

    post("/api/entries") {
        val input = when (val checked = validateNewEntry(call.userId(), call.receive<NewEntry>())) {
            is Validated.Invalid -> return@post call.badRequest(checked.message)
            is Validated.Ok -> checked.value
        }
        val saved = EntryRepository.create(call.userId(), input)
        if (!ProjectRepository.isAiEnabled(call.userId(), input.projectId)) {
            // Проект с выключенным ИИ (конфиденциально) — в Groq не отправляем, помечаем черновиком.
            EntryRepository.setStatus(call.userId(), UUID.fromString(saved.id), "draft")
            call.respond(saved.copy(status = "draft"))
            return@post
        }
        // Иначе запись остаётся в статусе queued: её подберёт AiWorker (очередь живёт в базе,
        // поэтому обработка не потеряется, даже если сервер прямо сейчас перезапустят).
        call.respond(saved)
    }

    /**
     * Синхронизация: что изменилось после момента `since` (включая удалённые записи).
     * Клиент запоминает `serverTime` из ответа и в следующий раз присылает его как `since`.
     */
    get("/api/entries/changes") {
        val sinceRaw = call.request.queryParameters["since"]
        val since = sinceRaw?.takeIf { it.isNotBlank() }?.let {
            runCatching { java.time.Instant.parse(it) }.getOrNull()
                ?: return@get call.badRequest("Некорректный since: нужен момент времени вида 2026-08-12T10:00:00Z")
        }
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 500)
        val changes = EntryRepository.changesSince(call.userId(), since, limit)
        call.respond(
            EntryChangesDto(
                entries = changes,
                serverTime = java.time.Instant.now().toString(),
                hasMore = changes.size == limit,
            ),
        )
    }

    get("/api/entries/{id}") {
        val id = call.entryId() ?: return@get call.badRequest("Некорректный id")
        val entry = EntryRepository.getById(call.userId(), id)
        if (entry == null) call.respond(HttpStatusCode.NotFound) else call.respond(entry)
    }

    put("/api/entries/{id}") {
        val id = call.entryId() ?: return@put call.badRequest("Некорректный id")
        val patch = when (val checked = validateEntryPatch(call.userId(), call.receive<UpdateEntry>())) {
            is Validated.Invalid -> return@put call.badRequest(checked.message)
            is Validated.Ok -> checked.value
        }
        val updated = EntryRepository.update(call.userId(), id, patch)
        if (updated == null) {
            call.respond(HttpStatusCode.NotFound)
            return@put
        }
        // Текст поменялся — структура от ИИ относится к старому тексту, она больше не верна.
        // Ставим запись в очередь заново (или в черновик, если у проекта ИИ выключен).
        if (patch.rawText == null) {
            call.respond(updated)
            return@put
        }
        val projUuid = updated.projectId?.let(UUID::fromString)
        if (ProjectRepository.isAiEnabled(call.userId(), projUuid)) {
            EntryRepository.requeueForAi(call.userId(), id)
        } else {
            EntryRepository.setStatus(call.userId(), id, "draft")
        }
        call.respond(EntryRepository.getById(call.userId(), id) ?: updated)
    }

    delete("/api/entries/{id}") {
        val id = call.entryId() ?: return@delete call.badRequest("Некорректный id")
        val removed = EntryRepository.delete(call.userId(), id)
        call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }

    post("/api/entries/{id}/reprocess") {
        val id = call.entryId() ?: return@post call.badRequest("Некорректный id")
        val entry = EntryRepository.getById(call.userId(), id)
        if (entry == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        // Уважаем выключатель ИИ проекта: если он выключен — просто помечаем черновиком.
        val projUuid = entry.projectId?.let(UUID::fromString)
        if (!ProjectRepository.isAiEnabled(call.userId(), projUuid)) {
            EntryRepository.setStatus(call.userId(), id, "draft")
            call.respond(HttpStatusCode.Accepted)
            return@post
        }
        // Обнуляем счётчик попыток и ошибку — воркер возьмёт запись как новую.
        EntryRepository.requeueForAi(call.userId(), id)
        call.respond(HttpStatusCode.Accepted)
    }
}

/** Разобрать path-параметр {id} в UUID; вернуть null, если он битый. */
private fun ApplicationCall.entryId(): UUID? = parameters["id"]?.let(::parseUuidOrNull)

/** Ответ 400 с понятным текстом: клиент прислал что-то не то. */
suspend fun ApplicationCall.badRequest(message: String) =
    respond(HttpStatusCode.BadRequest, ErrorResponse(message))
