package io.github.vihrea1337.devlog

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.time.LocalDate
import java.util.UUID

// DTO отчётов (NewReport, ReportDto, UpdateReport, ShareResponse) переехали
// в общий модуль `shared` — тот же пакет, поэтому импорт не нужен. Так одно
// определение контракта видят и сервер, и Android-приложение.

fun Route.reportRoutes() {
    authenticate("auth-jwt") {
        // Собрать отчёт за период (может занять секунды, если включён ИИ).
        post("/api/reports") {
            val body = call.receive<NewReport>()
            val from = parseDateOrNull(body.periodStart)
                ?: return@post call.badRequest("Некорректная дата начала периода, нужен формат ГГГГ-ММ-ДД")
            val to = parseDateOrNull(body.periodEnd)
                ?: return@post call.badRequest("Некорректная дата конца периода, нужен формат ГГГГ-ММ-ДД")
            if (to.isBefore(from)) return@post call.badRequest("Конец периода раньше начала")
            val projectId = body.projectId?.takeIf { it.isNotBlank() }?.let {
                parseUuidOrNull(it) ?: return@post call.badRequest("Некорректный id проекта")
            }
            // Отчёт можно строить только по своему проекту.
            if (projectId != null && ProjectRepository.getById(call.userId(), projectId) == null) {
                return@post call.badRequest("Проект не найден")
            }
            val title = body.title?.trim()?.ifBlank { null }?.take(200) ?: ReportGenerator.defaultTitle(from, to)
            val md = ReportGenerator.generate(call.userId(), title, from, to, projectId)
            call.respond(ReportRepository.create(call.userId(), projectId, title, from, to, body.format, md))
        }

        get("/api/reports") {
            call.respond(ReportRepository.list(call.userId()))
        }

        get("/api/reports/{id}") {
            val id = call.reportId() ?: return@get call.badRequest("Некорректный id")
            val report = ReportRepository.getById(call.userId(), id)
            if (report == null) call.respond(HttpStatusCode.NotFound) else call.respond(report)
        }

        // Правка отчёта перед отправкой: формулировки ИИ можно поправить руками.
        put("/api/reports/{id}") {
            val id = call.reportId() ?: return@put call.badRequest("Некорректный id")
            val body = call.receive<UpdateReport>()
            val text = body.contentMd.trim()
            if (text.isEmpty()) return@put call.badRequest("Текст отчёта пуст")
            if (text.length > MAX_REPORT_LENGTH) {
                return@put call.badRequest("Отчёт длиннее $MAX_REPORT_LENGTH символов")
            }
            val updated = ReportRepository.updateContent(call.userId(), id, text, body.title?.trim()?.take(200))
            if (updated == null) call.respond(HttpStatusCode.NotFound) else call.respond(updated)
        }

        delete("/api/reports/{id}") {
            val id = call.reportId() ?: return@delete call.badRequest("Некорректный id")
            val removed = ReportRepository.delete(call.userId(), id)
            call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }

        /**
         * Экспорт отчёта в GitHub — обратная сторона импорта коммитов (см. GithubExporter.kt).
         * Ручное действие по кнопке, не автоматика: обновляет ОДИН файл-changelog в репозитории
         * проекта, а не коммитит на каждую запись дневника.
         */
        post("/api/reports/{id}/export-github") {
            val id = call.reportId() ?: return@post call.badRequest("Некорректный id")
            val report = ReportRepository.getById(call.userId(), id)
                ?: return@post call.respond(HttpStatusCode.NotFound)

            val project = report.projectId?.let { ProjectRepository.getById(call.userId(), UUID.fromString(it)) }
            val repo = project?.githubExportRepo?.trim()
            if (repo.isNullOrBlank()) {
                return@post call.badRequest(
                    "У отчёта нет проекта с настроенным экспортом в GitHub — привяжите отчёт к проекту " +
                        "и укажите репозиторий в его настройках",
                )
            }
            val path = project.githubExportPath?.trim()?.ifBlank { null } ?: GithubExporter.DEFAULT_EXPORT_PATH

            val token = GithubTokenRepository.tokenFor(call.userId())
                ?: return@post call.badRequest(
                    "Сначала привяжите личный токен GitHub в настройках — без него нельзя писать в репозиторий",
                )

            val section = GithubExporter.ExportSection(
                reportId = report.id,
                title = report.title,
                periodStart = LocalDate.parse(report.periodStart),
                periodEnd = LocalDate.parse(report.periodEnd),
                markdown = report.contentMd,
            )

            runCatching { GithubExporter.export(repo, path, token, section) }
                .onFailure { error ->
                    call.application.environment.log.info("Экспорт отчёта в GitHub не удался: ${error.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(error.message ?: "Не удалось записать в GitHub"))
                }
                .onSuccess { call.respond(ExportGithubResultDto(repo, path)) }
        }

        // Включить публичную ссылку (для работодателя): выдаём/переиспользуем share-токен.
        post("/api/reports/{id}/share") {
            val id = call.reportId() ?: return@post call.badRequest("Некорректный id")
            val report = ReportRepository.getById(call.userId(), id)
            if (report == null) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            val token = report.shareToken ?: UUID.randomUUID().toString().replace("-", "")
            if (report.shareToken == null) ReportRepository.setShareToken(call.userId(), id, token)
            call.respond(ShareResponse(token, "/r/$token"))
        }

        /**
         * Отозвать публичную ссылку. Раньше выданная ссылка жила вечно: отдал отчёт
         * одному работодателю — он остаётся доступен всем, кому эту ссылку переслали.
         */
        delete("/api/reports/{id}/share") {
            val id = call.reportId() ?: return@delete call.badRequest("Некорректный id")
            val report = ReportRepository.getById(call.userId(), id)
            if (report == null) {
                call.respond(HttpStatusCode.NotFound)
                return@delete
            }
            ReportRepository.setShareToken(call.userId(), id, null)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    /**
     * Короткий адрес «пример отчёта» для страницы установки: /demo → публичная ссылка
     * демо-отчёта. Какой именно отчёт показывать — задаёт владелец сервера переменной
     * DEMO_REPORT_TOKEN (share-токен отчёта); не задана — ведём на страницу установки.
     * Так ссылку на странице не надо менять при пересборке демо-отчёта.
     */
    get("/demo") {
        val token = System.getenv("DEMO_REPORT_TOKEN")?.trim()?.ifEmpty { null }
        call.respondRedirect(if (token != null) "/r/$token" else "/install.html")
    }

    // Публичный просмотр отчёта по ссылке — БЕЗ токена (ссылку знает получатель).
    get("/r/{token}") {
        val token = call.parameters["token"]
        val report = token?.let { ReportRepository.getByShareToken(it) }
        val owner = token?.let { ReportRepository.ownerByShareToken(it) }
        if (report == null || owner == null) {
            call.respondText("Отчёт не найден", ContentType.Text.Html, HttpStatusCode.NotFound)
        } else {
            // Записи периода — для плиток с цифрами и графика активности. Тот же фильтр,
            // что при сборке отчёта (период + проект), поэтому цифры совпадают с текстом.
            val projectId = report.projectId?.let(::parseUuidOrNull)
            val entries = EntryRepository.list(owner, LocalDate.parse(report.periodStart), LocalDate.parse(report.periodEnd), projectId)
            val projects = ProjectRepository.list(owner).associateBy { it.id }
            val ctx = PublicReportPage.Context(
                authorName = UserRepository.findById(owner)?.displayName,
                projectName = report.projectId?.let { projects[it]?.name },
                entries = entries,
                projectCount = entries.mapNotNull { it.projectId }.distinct().size,
            )
            // Адрес сервера — для картинки превью ссылки (og:image должен быть абсолютным).
            val proto = call.request.headers["X-Forwarded-Proto"] ?: "https"
            val origin = "$proto://${call.request.headers["Host"] ?: "localhost"}"
            val autoPrint = call.request.queryParameters["print"] == "1"
            call.respondText(PublicReportPage.render(report, ctx, origin, autoPrint), ContentType.Text.Html)
        }
    }
}

private fun ApplicationCall.reportId(): UUID? = parameters["id"]?.let(::parseUuidOrNull)
