package io.github.vihrea1337.devlog

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import java.util.UUID

// --- DTO отчётов ---

@Serializable
data class NewReport(
    val periodStart: String,      // "2026-07-01"
    val periodEnd: String,        // "2026-07-31"
    val projectId: String? = null,
    val title: String? = null,
    val format: String = "markdown",
)

@Serializable
data class ReportDto(
    val id: String,
    val projectId: String?,
    val title: String,
    val periodStart: String,
    val periodEnd: String,
    val format: String,
    val contentMd: String,
    val shareToken: String?,
    val createdAt: String,
    /**
     * Тот же отчёт, но уже в HTML — чтобы клиент показывал человеческий документ,
     * а не исходник Markdown со звёздочками. Рендерим тем же кодом, что и публичную
     * страницу `/r/{token}`, а не дублируем конвертер на JavaScript.
     * (Значение по умолчанию считается от contentMd — писать его руками не нужно.)
     */
    val contentHtml: String = Markdown.toHtml(contentMd),
)

/** Тело PUT /api/reports/{id}: правка отчёта перед отправкой работодателю. */
@Serializable
data class UpdateReport(val contentMd: String, val title: String? = null)

/** Ответ на «поделиться»: токен и относительная ссылка публичной страницы. */
@Serializable
data class ShareResponse(val shareToken: String, val url: String)

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
            val title = body.title?.trim()?.ifBlank { null }?.take(200) ?: "Отчёт за $from — $to"
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

    // Публичный просмотр отчёта по ссылке — БЕЗ токена (ссылку знает получатель).
    get("/r/{token}") {
        val token = call.parameters["token"]
        val report = token?.let { ReportRepository.getByShareToken(it) }
        if (report == null) {
            call.respondText("Отчёт не найден", ContentType.Text.Html, HttpStatusCode.NotFound)
        } else {
            call.respondText(renderReportPage(report), ContentType.Text.Html)
        }
    }
}

private fun ApplicationCall.reportId(): UUID? = parameters["id"]?.let(::parseUuidOrNull)

/**
 * «2026-08-12T15:18:52.211558Z» → «12.08.2026». Страницу отчёта читает работодатель,
 * машинная метка времени в подвале ему ни о чём не говорит.
 */
private fun humanDate(iso: String): String {
    val date = iso.take(10).split("-")
    return if (date.size == 3) "${date[2]}.${date[1]}.${date[0]}" else Markdown.escapeHtml(iso)
}

/** Публичная HTML-страница отчёта (Markdown → HTML). */
private fun renderReportPage(report: ReportDto): String = """
<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<!-- Отчёт открыт по секретной ссылке, но попасть в поисковую выдачу он не должен. -->
<meta name="robots" content="noindex, nofollow">
<title>${Markdown.escapeHtml(report.title)} — DevLog</title>
<style>
  body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;max-width:760px;margin:0 auto;padding:32px 18px;color:#1c2330;line-height:1.6;background:#fff}
  h1{font-size:24px} h2{font-size:18px;margin-top:24px;border-top:1px solid #eee;padding-top:14px}
  h3{font-size:15px} ul{padding-left:20px} .foot{margin-top:40px;color:#8a92a1;font-size:12px}
  table{border-collapse:collapse;width:100%;margin:12px 0;font-size:14px;display:block;overflow-x:auto}
  th,td{border:1px solid #e3e6ec;padding:6px 10px;text-align:left}
  th{background:#f5f6f8;font-weight:600}
</style></head><body>
${Markdown.toHtml(report.contentMd)}
<div class="foot">Сгенерировано DevLog · ${humanDate(report.createdAt)}</div>
</body></html>
""".trimIndent()
