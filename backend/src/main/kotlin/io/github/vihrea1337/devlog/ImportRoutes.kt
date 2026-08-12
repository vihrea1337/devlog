package io.github.vihrea1337.devlog

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Тело запроса на импорт: период и (необязательно) проект, к которому привязать записи. */
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

/** Максимальный период за один импорт: у публичной активности GitHub всё равно ~90 дней. */
private const val MAX_IMPORT_DAYS = 120L

/**
 * Импорт коммитов с GitHub в записи дневника.
 *
 * Смысл фичи: «нет истории кроме коммитов» — самая частая боль из концепции продукта.
 * Коммиты за день собираются в одну запись, дальше её разбирает ИИ ровно так же,
 * как заметку, написанную руками.
 */
fun Route.importRoutes() = authenticate("auth-jwt") {
    post("/api/import/github") {
        val body = call.receive<GithubImportRequest>()
        val from = parseDateOrNull(body.periodStart)
            ?: return@post call.badRequest("Некорректная дата начала периода")
        val to = parseDateOrNull(body.periodEnd)
            ?: return@post call.badRequest("Некорректная дата конца периода")
        if (to.isBefore(from)) return@post call.badRequest("Конец периода раньше начала")
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_IMPORT_DAYS) {
            return@post call.badRequest("Слишком длинный период: максимум $MAX_IMPORT_DAYS дней за раз")
        }
        if (from.isBefore(LocalDate.now().minusDays(MAX_IMPORT_DAYS))) {
            return@post call.badRequest(
                "GitHub отдаёт только недавнюю публичную активность — примерно за последние 90 дней",
            )
        }

        val projectId = body.projectId?.takeIf { it.isNotBlank() }?.let {
            parseUuidOrNull(it) ?: return@post call.badRequest("Некорректный id проекта")
        }
        if (projectId != null && ProjectRepository.getById(call.userId(), projectId) == null) {
            return@post call.badRequest("Проект не найден")
        }

        val login = UserRepository.findById(call.userId())?.githubLogin
        if (login.isNullOrBlank()) {
            return@post call.badRequest("Сначала укажите свой логин на GitHub в профиле")
        }

        val result = runCatching { GithubImporter.import(call.userId(), login, from, to, projectId) }
            .getOrElse { error ->
                // Сюда попадают ответы GitHub «нет такого пользователя», «превышен лимит» и сбои сети.
                call.application.environment.log.info("Импорт с GitHub не удался: ${error.message}")
                return@post call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse(error.message ?: "Не удалось получить данные с GitHub"),
                )
            }

        call.respond(ImportResultDto(result.days, result.commits, result.created, result.skipped))
    }
}
