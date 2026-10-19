package io.github.vihrea1337.devlog

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

/** Максимальный период за один импорт: у публичной активности GitHub всё равно ~90 дней. */
private const val MAX_IMPORT_DAYS = 120L

/** Режим «один репозиторий» лимита ленты не имеет — год за раз (до 1000 коммитов, см. MAX_REPO_PAGES). */
private const val MAX_REPO_IMPORT_DAYS = 366L

/**
 * Импорт коммитов с GitHub в записи дневника.
 *
 * Смысл фичи: «нет истории кроме коммитов» — самая частая боль из концепции продукта.
 * Коммиты за день собираются в одну запись, дальше её разбирает ИИ ровно так же,
 * как заметку, написанную руками.
 */
fun Route.importRoutes() {
    commitImportRoutes()
    textImportRoutes()
}

/** Импорт коммитов (GitHub, GitLab) — только под обычным входом. */
private fun Route.commitImportRoutes() = authenticate("auth-jwt") {
    post("/api/import/github") {
        val body = call.receive<GithubImportRequest>()
        val from = parseDateOrNull(body.periodStart)
            ?: return@post call.badRequest("Некорректная дата начала периода")
        val to = parseDateOrNull(body.periodEnd)
            ?: return@post call.badRequest("Некорректная дата конца периода")
        if (to.isBefore(from)) return@post call.badRequest("Конец периода раньше начала")

        // Режим «один репозиторий» (см. GithubImporter.importRepo): коммиты берутся напрямую
        // из репозитория, поэтому ограничения ленты событий (~90 дней) к нему не относятся.
        val repo = body.repo?.takeIf { it.isNotBlank() }?.let {
            GithubImporter.normalizeRepo(it)
                ?: return@post call.badRequest("Репозиторий — в виде «владелец/имя» или ссылкой https://github.com/владелец/имя")
        }
        val maxDays = if (repo != null) MAX_REPO_IMPORT_DAYS else MAX_IMPORT_DAYS
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > maxDays) {
            return@post call.badRequest("Слишком длинный период: максимум $maxDays дней за раз")
        }
        if (repo == null && from.isBefore(LocalDate.now().minusDays(MAX_IMPORT_DAYS))) {
            return@post call.badRequest(
                "GitHub отдаёт только недавнюю активность (Events API) — примерно за последние 90 дней, " +
                    "даже с привязанным токеном. Для более старых коммитов укажите репозиторий",
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
        // День коммита — по поясу из «Автоматики» (как у файлов дня), иначе по UTC, как раньше.
        val zone = AutomationRepository.zoneFor(call.userId())
        val result = runCatching {
            if (repo != null) GithubImporter.importRepo(call.userId(), login, repo, from, to, projectId, token, zone)
            else GithubImporter.import(call.userId(), login, from, to, projectId, token)
        }
            .getOrElse { error ->
                // Сюда попадают ответы GitHub «нет такого пользователя», «превышен лимит» и сбои сети.
                call.application.environment.log.info("Импорт с GitHub не удался: ${error.message}")
                return@post call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse(error.message ?: "Не удалось получить данные с GitHub"),
                )
            }

        call.respond(ImportResultDto(result.days, result.commits, result.created, result.skipped, result.truncated, result.updated))
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
}

/**
 * Импорт текста и файлов дня — под обычным входом ИЛИ под токеном скрипта (ImportTokens.kt):
 * их отправляет автоимпорт с ПК. Ручки только добавляют/обновляют записи — ничего не отдают
 * наружу, кроме итога импорта, поэтому токену скрипта сюда можно.
 */
private fun Route.textImportRoutes() = authenticate("auth-jwt", "import-token") {
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

    /**
     * Импорт файлов дня (`ГГГГ-ММ-ДД.md` с разделами «Сделано», «Проверено», «Цифры»…) —
     * см. DayFileImport.kt. Сразу несколько файлов (папка месяца); повторная загрузка того же
     * дня обновляет запись, а не плодит вторую. Ответ — итог по каждому файлу.
     */
    post("/api/import/day-files") {
        val body = call.receive<DayFilesImportRequest>()
        if (body.files.isEmpty()) return@post call.badRequest("Не выбрано ни одного файла")
        if (body.files.size > DayFileImporter.MAX_FILES) {
            return@post call.badRequest("Не больше ${DayFileImporter.MAX_FILES} файлов за раз")
        }
        val projectId = call.resolveProject(body.projectId, body.projectName).getOrElse { return@post call.badRequest(it.message!!) }
        call.respond(DayFileImporter.import(call.userId(), body.files, projectId, body.overwrite))
    }

    /**
     * Коммиты из `git log` локальной копии репозитория (см. GithubImporter.importCommits) —
     * без токена GitHub. Записи те же, что у импорта GitHub «один репозиторий».
     */
    post("/api/import/commits") {
        val body = call.receive<CommitsImportRequest>()
        val repo = GithubImporter.normalizeRepo(body.repo)
            ?: return@post call.badRequest("Репозиторий — в виде «владелец/имя»")
        if (body.commits.size > GithubImporter.MAX_PUSHED_COMMITS) {
            return@post call.badRequest("Не больше ${GithubImporter.MAX_PUSHED_COMMITS} коммитов за раз — сократите период")
        }
        val projectId = call.resolveProject(body.projectId, body.projectName).getOrElse { return@post call.badRequest(it.message!!) }
        val result = GithubImporter.importCommits(call.userId(), repo, body.commits, projectId)
            ?: return@post call.badRequest("У коммита неверная дата (нужно ГГГГ-ММ-ДД), sha или пустое сообщение")
        call.respond(ImportResultDto(result.days, result.commits, result.created, result.skipped, result.truncated, result.updated))
    }

    /**
     * Свежие отчёты, собранные автоматикой (за 3 дня), — чтобы скрипт на ПК показал
     * уведомление «отчёт готов». Только вид, название и ссылка, без текста: токену
     * скрипта читать отчёты по-прежнему нельзя.
     */
    get("/api/import/notices") {
        call.respond(AutomationRepository.recentNotices(call.userId()))
    }
}

/**
 * Проект для импорта: по id или по имени (для скриптов). Ничего не задано — Result(null),
 * т.е. без проекта; задано, но не найдено — ошибка с текстом для 400.
 */
private fun ApplicationCall.resolveProject(id: String?, name: String?): Result<UUID?> {
    id?.takeIf { it.isNotBlank() }?.let { raw ->
        val uuid = parseUuidOrNull(raw) ?: return Result.failure(IllegalArgumentException("Некорректный id проекта"))
        return if (ProjectRepository.getById(userId(), uuid) != null) Result.success(uuid)
        else Result.failure(IllegalArgumentException("Проект не найден"))
    }
    val wanted = name?.trim()?.takeIf { it.isNotEmpty() } ?: return Result.success(null)
    val project = ProjectRepository.list(userId()).firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        ?: return Result.failure(IllegalArgumentException("Проект «$wanted» не найден — создайте его в DevLog"))
    return Result.success(UUID.fromString(project.id))
}
