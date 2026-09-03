package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.Base64

/**
 * Экспорт отчёта в GitHub — обратная сторона импорта коммитов (см. GithubImport.kt).
 * Идея из концепции продукта: не только "коммиты становятся дневником", но и "дневник
 * становится частью репозитория" — рядом с настоящими коммитами лежит человеческий текст
 * "что и зачем делалось в этот период", со ссылкой на сам отчёт.
 *
 * Пишем через GitHub Contents API (`GET`/`PUT /repos/{repo}/contents/{path}`) в ОДИН файл
 * (по умолчанию `docs/devlog.md`), а не коммитим по записи дневника — иначе это была бы
 * слишком навязчивая запись в чужую историю коммитов. Экспорт — ручное действие по кнопке
 * на конкретном отчёте (см. `POST /api/reports/{id}/export-github` в ReportRoutes.kt),
 * не автоматика: запись коммитов в чужой репозиторий — видимое всем действие, его не делают
 * молча в фоне.
 *
 * Требует токен пользователя (см. GithubTokenRepository) — тот же, что для импорта, но
 * теперь ему нужны уже права на ЗАПИСЬ в конкретный репозиторий (это не в нашей власти —
 * если у токена нет прав, GitHub откажет 403/404, и мы честно покажем это пользователю,
 * а не проглотим ошибку).
 */
object GithubExporter {
    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 20_000 } }
    }

    /** Путь по умолчанию, если пользователь не указал свой в настройках проекта. */
    const val DEFAULT_EXPORT_PATH = "docs/devlog.md"

    /** Заголовок файла, когда мы создаём его с нуля. */
    private const val FILE_HEADING = "# Dev Log"

    /** Один отчёт, готовый лечь в changelog-файл. */
    data class ExportSection(
        val reportId: String,
        val title: String,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val markdown: String,
    )

    /** Текущее содержимое файла в GitHub (для PUT нужен его sha — иначе GitHub отклонит перезапись). */
    data class RemoteFile(val content: String, val sha: String)

    /** Ошибка похода в GitHub — сообщение уже готово для показа пользователю (см. ReportRoutes.kt). */
    class GithubExportException(message: String) : RuntimeException(message)

    /**
     * Прочитать текущий файл. Вынесена отдельно (как fetchEvents/fetchCompare в GithubImport.kt),
     * чтобы тесты подставляли свой ответ без реальной сети. null — файла ещё нет (первый экспорт).
     */
    var fetchFile: suspend (repo: String, path: String, token: String) -> RemoteFile? = { repo, path, token ->
        val response = client.get("https://api.github.com/repos/$repo/contents/$path") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "DevLog")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        when {
            response.status.value == 404 -> null
            response.status.isSuccess() -> {
                val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val encoded = obj["content"]?.jsonPrimitive?.content.orEmpty()
                val sha = obj["sha"]?.jsonPrimitive?.content.orEmpty()
                // GitHub отдаёт base64 с переносами строк каждые ~60 символов — MIME-декодер
                // их и ожидает (обычный Base64.getDecoder() на них падает).
                RemoteFile(String(Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8), sha)
            }
            else -> throw GithubExportException("GitHub ответил ${response.status.value} при чтении файла $path")
        }
    }

    /**
     * Записать (создать или обновить) файл. sha != null — обновление существующего файла,
     * GitHub требует его как доказательство "мы читали актуальную версию перед перезаписью".
     */
    var putFile: suspend (repo: String, path: String, token: String, content: String, sha: String?, message: String) -> Unit =
        { repo, path, token, content, sha, message ->
            val payload = buildJsonObject {
                put("message", message)
                put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
                if (sha != null) put("sha", sha)
            }
            val response = client.put("https://api.github.com/repos/$repo/contents/$path") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "DevLog")
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            if (!response.status.isSuccess()) {
                val message2 = when (response.status.value) {
                    401 -> "Токен GitHub недействителен или истёк — привяжите новый в настройках"
                    403, 404 -> "GitHub отказал в записи — проверьте, что у токена есть права на запись " +
                        "в репозиторий $repo, а путь $path указан верно"
                    409 -> "Файл изменился в GitHub с момента чтения — попробуйте экспортировать ещё раз"
                    else -> "GitHub ответил ${response.status.value} при записи файла $path"
                }
                throw GithubExportException(message2)
            }
        }

    /**
     * Собрать новое содержимое changelog-файла. Функция ЧИСТАЯ (без сети) — тестируется
     * без сети, см. GithubExporterTest.
     *
     * Раздел отчёта помечается HTML-комментарием `<!-- devlog:report:{id} -->` прямо перед
     * его заголовком — по нему, а не по заголовку/периоду, ищем "тот же" раздел при повторном
     * экспорте ЭТОГО ЖЕ отчёта (например, после правки текста). Заголовок и период для этого
     * не годятся: пользователь мог отредактировать текст отчёта в дневнике (тогда period/title
     * не меняются, а перезаписать раздел всё равно нужно), а два РАЗНЫХ отчёта на один и тот же
     * период (собрали черновик, не понравилось, собрали заново) должны стать двумя разными
     * разделами, а не молча слиться в один.
     *
     * Новый (ещё не встречавшийся) раздел вставляется В НАЧАЛО списка разделов — самые
     * свежие записи сверху, как в обычном changelog. Уже существующий раздел при повторном
     * экспорте обновляется НА МЕСТЕ, порядок остальных разделов не трогаем.
     *
     * Всё, что стоит в файле ДО первого нашего маркера (например, ручной заголовок или текст,
     * который пользователь мог написать сам, или заголовок из самого первого экспорта), считается
     * "преамбулой" и переносится в результат как есть — экспорт никогда не трогает чужой текст.
     *
     * Известный компромисс: если текст самого отчёта (contentMd, который частично пишет ИИ)
     * случайно содержит строку вида `<!-- devlog:report:... -->`, разбор перепутает её с
     * границей раздела. Экранировать это в MVP не стали — вероятность практически нулевая
     * (это не то, что естественно появляется в тексте отчёта о работе), а исправление раздела
     * при следующем экспорте того же отчёта всё равно не потеряет данные безвозвратно.
     */
    fun buildUpdatedContent(existing: String?, section: ExportSection): String {
        val (rawPreamble, sections) = if (existing != null) parseSections(existing) else FILE_HEADING to emptyList()
        // Файл существовал, но был пустым (0 байт, без единого маркера) — считаем это тем же
        // случаем, что и "файла ещё нет", иначе результат начинался бы с двух пустых строк.
        val preamble = rawPreamble.ifBlank { FILE_HEADING }
        val newBlock = renderSection(section)

        val idx = sections.indexOfFirst { it.first == section.reportId }
        val updatedSections = if (idx >= 0) {
            sections.toMutableList().also { it[idx] = section.reportId to newBlock }
        } else {
            listOf(section.reportId to newBlock) + sections
        }

        val body = updatedSections.joinToString("\n\n") { it.second }
        return preamble.trimEnd('\n') + "\n\n" + body + "\n"
    }

    private fun markerFor(reportId: String) = "<!-- devlog:report:$reportId -->"

    private fun renderSection(section: ExportSection): String {
        val heading = "## ${section.periodStart} – ${section.periodEnd}: ${section.title}"
        return "${markerFor(section.reportId)}\n$heading\n\n${section.markdown.trim()}"
    }

    /**
     * Разобрать файл на "преамбулу" (всё до первого маркера) и список разделов (id → полный
     * текст раздела, включая маркер), в том порядке, в каком они встретились в файле.
     * Файл без единого маркера (первый экспорт в файл, который пользователь мог начать сам,
     * или файл, созданный до этой фичи) — целиком преамбула, разделов нет.
     */
    private fun parseSections(text: String): Pair<String, List<Pair<String, String>>> {
        val lines = text.lines()
        val markerPrefix = "<!-- devlog:report:"
        val firstMarkerIdx = lines.indexOfFirst { it.trim().startsWith(markerPrefix) }
        if (firstMarkerIdx == -1) return text to emptyList()

        val preamble = lines.subList(0, firstMarkerIdx).joinToString("\n")
        val sections = mutableListOf<Pair<String, String>>()
        var i = firstMarkerIdx
        while (i < lines.size) {
            val markerLine = lines[i].trim()
            val id = markerLine.removePrefix(markerPrefix).removeSuffix("-->").trim()
            var j = i + 1
            while (j < lines.size && !lines[j].trim().startsWith(markerPrefix)) j++
            sections.add(id to lines.subList(i, j).joinToString("\n").trimEnd())
            i = j
        }
        return preamble to sections
    }

    /**
     * Экспортировать один отчёт: прочитать текущий файл, вставить/обновить его раздел,
     * записать обратно. Кидает [GithubExportException] на любой сбой сети/прав — вызывающий
     * код (ReportRoutes.kt) превращает это в понятный ответ клиенту, а не в 500.
     */
    suspend fun export(repo: String, path: String, token: String, section: ExportSection) {
        val current = fetchFile(repo, path, token)
        val newContent = buildUpdatedContent(current?.content, section)
        val message = "devlog: ${section.title} (${section.periodStart}..${section.periodEnd})"
        putFile(repo, path, token, newContent, current?.sha, message)
    }
}
