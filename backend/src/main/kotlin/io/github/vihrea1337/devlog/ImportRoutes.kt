package io.github.vihrea1337.devlog

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.LocalDate

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
                "GitHub отдаёт только недавнюю активность (Events API) — примерно за последние 90 дней, " +
                    "даже с привязанным токеном",
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

        val token = GithubTokenRepository.tokenFor(call.userId())
        val result = runCatching { GithubImporter.import(call.userId(), login, from, to, projectId, token) }
            .getOrElse { error ->
                // Сюда попадают ответы GitHub «нет такого пользователя», «превышен лимит» и сбои сети.
                call.application.environment.log.info("Импорт с GitHub не удался: ${error.message}")
                return@post call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse(error.message ?: "Не удалось получить данные с GitHub"),
                )
            }

        call.respond(ImportResultDto(result.days, result.commits, result.created, result.skipped, result.truncated))
    }

    /**
     * Импорт коммитов с GitLab — второй провайдер, см. GitlabImport.kt за тем, чем реально
     * отличается его устройство от GitHub (не просто "то же самое, другое имя"). Токен
     * здесь ОБЯЗАТЕЛЕН (у GitLab нет анонимного режима) — без привязанного токена сразу 400,
     * а не тихий "пустой" импорт.
     */
    post("/api/import/gitlab") {
        val body = call.receive<GitlabImportRequest>()
        val from = parseDateOrNull(body.periodStart)
            ?: return@post call.badRequest("Некорректная дата начала периода")
        val to = parseDateOrNull(body.periodEnd)
            ?: return@post call.badRequest("Некорректная дата конца периода")
        if (to.isBefore(from)) return@post call.badRequest("Конец периода раньше начала")
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_IMPORT_DAYS) {
            return@post call.badRequest("Слишком длинный период: максимум $MAX_IMPORT_DAYS дней за раз")
        }

        val projectId = body.projectId?.takeIf { it.isNotBlank() }?.let {
            parseUuidOrNull(it) ?: return@post call.badRequest("Некорректный id проекта")
        }
        if (projectId != null && ProjectRepository.getById(call.userId(), projectId) == null) {
            return@post call.badRequest("Проект не найден")
        }

        val token = GitlabTokenRepository.tokenFor(call.userId())
            ?: return@post call.badRequest("Сначала привяжите личный токен GitLab в настройках — без него импортировать нечего")

        val result = runCatching { GitlabImporter.import(call.userId(), from, to, projectId, token) }
            .getOrElse { error ->
                call.application.environment.log.info("Импорт с GitLab не удался: ${error.message}")
                return@post call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse(error.message ?: "Не удалось получить данные с GitLab"),
                )
            }

        call.respond(ImportResultDto(result.days, result.commits, result.created, result.skipped, result.truncated))
    }

    /**
     * Импорт произвольного текстового логa (в т.ч. выгрузки/транскрипта Claude Code) —
     * см. `docs/architecture.md` за обоснованием, почему формат общий, а не заточенный
     * под конкретный экспорт.
     */
    post("/api/import/text") {
        val body = call.receive<TextImportRequest>()
        val text = body.text.trim()
        if (text.isEmpty()) return@post call.badRequest("Текст пуст")
        if (text.length > MAX_TEXT_IMPORT_LENGTH) {
            return@post call.badRequest("Текст длиннее $MAX_TEXT_IMPORT_LENGTH символов — разбейте на части")
        }

        val projectId = body.projectId?.takeIf { it.isNotBlank() }?.let {
            parseUuidOrNull(it) ?: return@post call.badRequest("Некорректный id проекта")
        }
        if (projectId != null && ProjectRepository.getById(call.userId(), projectId) == null) {
            return@post call.badRequest("Проект не найден")
        }

        val result = TextImporter.import(call.userId(), text, projectId)
        if (result.days == 0) {
            return@post call.badRequest(
                "Не нашли в тексте ни одной даты в формате ГГГГ-ММ-ДД — без неё непонятно, " +
                    "к какому дню отнести содержимое",
            )
        }
        call.respond(TextImportResultDto(result.days, result.linesMatched, result.created, result.skipped))
    }
}
