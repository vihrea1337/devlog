package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Импорт коммитов с GitHub — киллер-фича из концепции: «нет истории кроме коммитов»
 * превращается в «коммиты сами становятся дневником».
 *
 * Берём **публичную активность** пользователя (`/users/{login}/events/public`) — это
 * открытый API, токен не нужен, поэтому нам не приходится хранить чужие секреты.
 * Ограничения такого источника честные и описаны в доках: только публичные репозитории,
 * примерно последние 90 дней и не больше 300 последних событий.
 *
 * Что делаем с данными: коммиты за один день собираем в ОДНУ запись дневника — она уходит
 * тем же путём, что и заметка руками, и её так же разбирает ИИ.
 */

/** Один коммит из события GitHub. */
data class GithubCommit(val repo: String, val message: String, val sha: String) {
    /** Ссылка на коммит — кладём в запись, чтобы можно было вернуться к исходнику. */
    val url: String get() = "https://github.com/$repo/commit/$sha"
}

/** Результат импорта для ответа клиенту. */
data class ImportResult(val days: Int, val commits: Int, val created: Int, val skipped: Int)

object GithubImporter {
    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 20_000 } }
    }

    /** Загрузка страницы событий. Вынесена отдельно, чтобы тесты подставляли свой ответ. */
    var fetchEvents: suspend (login: String) -> String = { login ->
        val response = client.get("https://api.github.com/users/$login/events/public?per_page=100") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "DevLog")
        }
        if (!response.status.isSuccess()) {
            error(
                when (response.status.value) {
                    404 -> "Пользователь GitHub не найден"
                    403 -> "GitHub временно отказывает в запросах (превышен лимит). Попробуйте позже"
                    else -> "GitHub ответил ${response.status.value}"
                },
            )
        }
        response.bodyAsText()
    }

    /**
     * Разобрать ответ GitHub в коммиты по дням. Функция чистая (без сети и БД) —
     * её удобно тестировать на сохранённом куске настоящего ответа.
     */
    fun parseCommitsByDay(body: String, from: LocalDate, to: LocalDate): Map<LocalDate, List<GithubCommit>> {
        val events = runCatching { json.parseToJsonElement(body).jsonArray }.getOrElse { return emptyMap() }
        val byDay = sortedMapOf<LocalDate, MutableList<GithubCommit>>()

        for (element in events) {
            val event = element.jsonObject
            // Нас интересуют только пуши — в них лежат сами коммиты.
            if (event["type"]?.jsonPrimitive?.content != "PushEvent") continue
            val createdAt = event["created_at"]?.jsonPrimitive?.content ?: continue
            val day = runCatching { Instant.parse(createdAt).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull() ?: continue
            if (day < from || day > to) continue

            val repo = event["repo"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: continue
            val commits = event["payload"]?.jsonObject?.get("commits")?.jsonArray ?: continue
            for (commitElement in commits) {
                val commit = commitElement.jsonObject
                val message = commit["message"]?.jsonPrimitive?.content?.trim().orEmpty()
                val sha = commit["sha"]?.jsonPrimitive?.content.orEmpty()
                if (message.isEmpty() || sha.isEmpty()) continue
                byDay.getOrPut(day) { mutableListOf() }.add(GithubCommit(repo, message, sha))
            }
        }
        // Один и тот же коммит может прийти в нескольких событиях — оставляем уникальные.
        return byDay.mapValues { (_, list) -> list.distinctBy { it.sha } }
    }

    /** Текст записи из коммитов за день — это и есть сырьё, которое дальше разберёт ИИ. */
    fun buildEntryText(day: LocalDate, commits: List<GithubCommit>): String {
        val header = "Коммиты за $day (импорт из GitHub, ${commits.size} шт.):"
        val lines = commits.joinToString("\n") { commit ->
            // Первая строка сообщения — суть коммита; остальное (тело) обычно менее важно.
            val title = commit.message.lineSequence().first()
            "- ${commit.repo}: $title (${commit.sha.take(7)})"
        }
        return (header + "\n" + lines).take(MAX_RAW_TEXT_LENGTH)
    }

    /**
     * Один и тот же день должен давать одну и ту же запись, сколько раз ни импортируй.
     * Поэтому id записи вычисляем из пользователя и даты, а не берём случайный:
     * повторный импорт наткнётся на существующую запись и ничего не продублирует.
     */
    fun entryIdFor(userId: UUID, day: LocalDate): UUID =
        UUID.nameUUIDFromBytes("devlog-github:$userId:$day".toByteArray())

    /**
     * Импортировать коммиты за период в записи дневника.
     * Уже существующие дни не трогаем — иначе затёрли бы правки, сделанные руками.
     */
    suspend fun import(userId: UUID, login: String, from: LocalDate, to: LocalDate, projectId: UUID?): ImportResult {
        val byDay = parseCommitsByDay(fetchEvents(login), from, to)
        var created = 0
        var skipped = 0
        var commits = 0

        for ((day, dayCommits) in byDay) {
            commits += dayCommits.size
            val entryId = entryIdFor(userId, day)
            if (EntryRepository.getById(userId, entryId) != null) {
                skipped++
                continue
            }
            EntryRepository.create(
                userId,
                ValidEntryInput(
                    occurredOn = day,
                    rawText = buildEntryText(day, dayCommits),
                    projectId = projectId,
                    sourceType = "github",
                    timeSpentMin = null,
                    id = entryId,
                ),
            )
            created++
        }
        return ImportResult(days = byDay.size, commits = commits, created = created, skipped = skipped)
    }
}
