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
        // Статус выбирает сам репозиторий в той же транзакции: у проекта с выключенным ИИ
        // запись сразу "draft" (в Groq не уходит), иначе "queued" — её подберёт AiWorker
        // (очередь живёт в базе, поэтому обработка не потеряется даже при перезапуске сервера).
        call.respond(EntryRepository.create(call.userId(), input))
    }

    /**
     * Синхронизация: что изменилось после курсора `since` (включая удалённые записи).
     * `since`/`serverTime` — НЕПРОЗРАЧНАЯ строка для клиента (он получает `serverTime` из
     * ответа и присылает его же обратно как `since`, не пытаясь разобрать) — внутри это
     * `sync_seq` (см. Tables.kt): строго монотонный счётчик записи, НЕ `updated_at`
     * (тот мог повториться у нескольких записей — см. историю в docs/architecture.md).
     */
    get("/api/entries/changes") {
        val sinceRaw = call.request.queryParameters["since"]
        val since = sinceRaw?.takeIf { it.isNotBlank() }?.let {
            it.toLongOrNull()
                ?: return@get call.badRequest("Некорректный since — подставьте serverTime из предыдущего ответа как есть")
        }
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 500)
        val page = EntryRepository.changesSince(call.userId(), since, limit)
        // Курсор для следующего запроса — sync_seq ПОСЛЕДНЕЙ отданной записи, а не "текущее
        // время сервера": иначе записи, которые уже лежат в базе, но не попали в эту страницу
        // (обрезало лимитом), были бы навсегда пропущены.
        val nextCursor = page.lastSeq ?: since ?: 0L // 0 — самая первая синхронизация, и то пусто
        call.respond(
            EntryChangesDto(
                entries = page.entries,
                serverTime = nextCursor.toString(),
                hasMore = page.entries.size == limit,
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
        // Файл дня с разделами разбирает заново код — ИИ ему не нужен.
        if (patch.rawText == null) {
            call.respond(updated)
            return@put
        }
        val projUuid = updated.projectId?.let(UUID::fromString)
        when {
            DayFileImporter.restructure(call.userId(), updated) -> Unit
            ProjectRepository.isAiEnabled(call.userId(), projUuid) -> EntryRepository.requeueForAi(call.userId(), id)
            else -> EntryRepository.setStatus(call.userId(), id, "draft")
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
        // Файл дня с разделами разбирает код — «переобработать» значит разобрать заново им же.
        if (DayFileImporter.restructure(call.userId(), entry)) {
            call.respond(HttpStatusCode.Accepted)
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
