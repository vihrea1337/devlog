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

/** Ручки проектов — все под токеном и в контексте своего пользователя. */
fun Route.projectRoutes() = authenticate("auth-jwt") {

    get("/api/projects") {
        call.respond(ProjectRepository.list(call.userId()))
    }

    post("/api/projects") {
        val body = call.receive<NewProject>()
        if (body.name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Имя проекта пустое"))
            return@post
        }
        call.respond(ProjectRepository.create(call.userId(), body))
    }

    get("/api/projects/{id}") {
        val id = call.projectId() ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный id"))
        val project = ProjectRepository.getById(call.userId(), id)
        if (project == null) call.respond(HttpStatusCode.NotFound) else call.respond(project)
    }

    put("/api/projects/{id}") {
        val id = call.projectId() ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный id"))
        val updated = ProjectRepository.update(call.userId(), id, call.receive<UpdateProject>())
        if (updated == null) call.respond(HttpStatusCode.NotFound) else call.respond(updated)
    }

    delete("/api/projects/{id}") {
        val id = call.projectId() ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный id"))
        val removed = ProjectRepository.delete(call.userId(), id)
        call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }
}

/** Разобрать path-параметр {id} в UUID; вернуть null, если он битый. */
private fun ApplicationCall.projectId(): UUID? =
    runCatching { UUID.fromString(parameters["id"]) }.getOrNull()
