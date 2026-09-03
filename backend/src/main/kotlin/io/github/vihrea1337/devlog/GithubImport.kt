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
 * Без токена берём **публичную активность** (`/users/{login}/events/public`) — открытый
 * API, секрет не нужен. Ограничения честные и описаны в доках: только публичные репозитории,
 * примерно последние 90 дней и не больше 300 последних событий.
 *
 * С привязанным личным токеном (см. GithubTokenRepository) ходим на `/users/{login}/events`
 * (БЕЗ "/public") с заголовком Authorization — тот же endpoint, но GitHub отдаёт по нему
 * ещё и приватные события, если токен принадлежит именно этому пользователю.
 *
 * ВАЖНО (обнаружено 2026-09-02 при ручной проверке с реальным API): `PushEvent.payload`
 * в реальном ответе GitHub давно НЕ содержит списка коммитов — только `before`/`head`
 * (SHA до и после пуша). Поэтому для каждого пуша делаем ОДИН дополнительный запрос —
 * GitHub Compare API (`/repos/{repo}/compare/{before}...{head}`), который и возвращает
 * настоящие сообщения коммитов. Выбран Compare, а не `/commits?sha=&since=&until=`:
 * он даёт ТОЧНО те коммиты, что вошли именно в этот пуш (без риска зацепить чужие
 * коммиты из других веток тем же временем, как было бы с since/until), и это тот же
 * один запрос на пуш, а не запрос на коммит.
 *
 * Это удваивает число запросов к API (1 на пуш) — с учётом лимита GitHub (5000/час
 * с токеном, 60/час без) без токена легко упереться в лимит уже на паре десятков
 * пушей, поэтому число догрузок ограничено (см. MAX_COMPARE_CALLS_*) и rate-limit
 * от GitHub (403 с `X-RateLimit-Remaining: 0`) останавливает догрузку сразу, а не
 * долбит API дальше — то, что уже собрано, всё равно уходит в записи дневника.
 *
 * Что делаем с данными: коммиты за один день собираем в ОДНУ запись дневника — она уходит
 * тем же путём, что и заметка руками, и её так же разбирает ИИ.
 */

/** Один коммит из события GitHub. */
data class GithubCommit(val repo: String, val message: String, val sha: String) {
    /** Ссылка на коммит — кладём в запись, чтобы можно было вернуться к исходнику. */
    val url: String get() = "https://github.com/$repo/commit/$sha"
}

/** Один пуш из ленты событий — ещё без коммитов, только ссылка "что сравнивать". */
data class GithubPushRef(val repo: String, val before: String, val head: String)

/** SHA "с самого начала" — GitHub присылает такой `before` при создании новой ветки. */
private const val ZERO_SHA = "0000000000000000000000000000000000000000"

/** Без токена — 60 запросов/час на весь аккаунт, поэтому лимит заметно строже. internal — тест сверяет число. */
internal const val MAX_COMPARE_CALLS_ANONYMOUS = 15
internal const val MAX_COMPARE_CALLS_AUTHENTICATED = 150

/** Результат импорта для ответа клиенту. */
data class ImportResult(
    val days: Int,
    val commits: Int,
    val created: Int,
    val skipped: Int,
    /** Догрузка коммитов остановилась раньше конца — упёрлись в лимит запросов (свой или GitHub). */
    val truncated: Boolean,
)

/**
 * GitHub ответил 403 именно из-за исчерпанного лимита запросов (не "нет доступа").
 * internal, а не private: тесты подставляют свой fetchCompare и должны уметь
 * бросить эту же ошибку, чтобы проверить, что импорт останавливается, а не долбит API дальше.
 */
internal class GithubRateLimitException : RuntimeException("GitHub rate limit exceeded")

object GithubImporter {
    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 20_000 } }
    }

    /**
     * Загрузка страницы событий. Вынесена отдельно, чтобы тесты подставляли свой ответ.
     * token = null → публичный endpoint без авторизации; token != null → тот же ресурс,
     * но без "/public" и с заголовком Authorization — GitHub отдаёт приватные события тоже.
     */
    var fetchEvents: suspend (login: String, token: String?) -> String = { login, token ->
        val path = if (token != null) "events" else "events/public"
        val response = client.get("https://api.github.com/users/$login/$path?per_page=100") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "DevLog")
            // НЕ логировать этот заголовок целиком — токен не должен попасть в логи сервера.
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            error(
                when (response.status.value) {
                    404 -> "Пользователь GitHub не найден"
                    401 -> "Токен GitHub недействителен или истёк — привяжите новый в настройках"
                    403 -> "GitHub временно отказывает в запросах (превышен лимит). Попробуйте позже"
                    else -> "GitHub ответил ${response.status.value}"
                },
            )
        }
        response.bodyAsText()
    }

    /**
     * Догрузка настоящих коммитов одного пуша через Compare API. Вынесена отдельно
     * (как и fetchEvents), чтобы тесты подставляли свой ответ без реальной сети.
     * Бросает [GithubRateLimitException], если это именно исчерпанный лимit запросов —
     * вызывающий код (см. [import]) должен остановить догрузку остальных пушей, а не
     * долбить GitHub дальше и получать один и тот же отказ на каждый следующий запрос.
     */
    var fetchCompare: suspend (repo: String, before: String, head: String, token: String?) -> String =
        { repo, before, head, token ->
            val response = client.get("https://api.github.com/repos/$repo/compare/$before...$head") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "DevLog")
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (!response.status.isSuccess()) {
                val rateLimited = response.status.value == 403 &&
                    response.headers["X-RateLimit-Remaining"] == "0"
                if (rateLimited) throw GithubRateLimitException()
                // Отдельный пуш не удалось сравнить (например, ветку успели удалить,
                // force-push унёс before) — не роняем весь импорт из-за одного пуша.
                error("GitHub ответил ${response.status.value} на compare $repo $before...$head")
            }
            response.bodyAsText()
        }

    /**
     * Разобрать ответ ленты событий в пуши по дням — БЕЗ коммитов, только ссылки
     * "что с чем сравнивать" (см. [GithubPushRef]). Функция чистая (без сети и БД) —
     * удобно тестировать на сохранённом куске настоящего ответа.
     */
    fun parsePushesByDay(body: String, from: LocalDate, to: LocalDate): Map<LocalDate, List<GithubPushRef>> {
        val events = runCatching { json.parseToJsonElement(body).jsonArray }.getOrElse { return emptyMap() }
        val byDay = sortedMapOf<LocalDate, MutableList<GithubPushRef>>()

        for (element in events) {
            val event = element.jsonObject
            // Нас интересуют только пуши — остальные типы событий коммитов не несут.
            if (event["type"]?.jsonPrimitive?.content != "PushEvent") continue
            val createdAt = event["created_at"]?.jsonPrimitive?.content ?: continue
            val day = runCatching { Instant.parse(createdAt).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull() ?: continue
            if (day < from || day > to) continue

            val repo = event["repo"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: continue
            val payload = event["payload"]?.jsonObject ?: continue
            val before = payload["before"]?.jsonPrimitive?.content ?: continue
            val head = payload["head"]?.jsonPrimitive?.content ?: continue
            if (before.isEmpty() || head.isEmpty() || before == head) continue
            // before = сплошные нули — пуш создал ветку с нуля, сравнивать не с чем.
            if (before == ZERO_SHA) continue

            byDay.getOrPut(day) { mutableListOf() }.add(GithubPushRef(repo, before, head))
        }
        return byDay
    }

    /** Разобрать ответ Compare API в список коммитов (от старого к новому). */
    fun parseCommitsFromCompare(body: String, repo: String): List<GithubCommit> {
        val commitsArray = runCatching { json.parseToJsonElement(body).jsonObject["commits"]?.jsonArray }
            .getOrNull() ?: return emptyList()
        return commitsArray.mapNotNull { element ->
            val commitObj = element.jsonObject
            val sha = commitObj["sha"]?.jsonPrimitive?.content.orEmpty()
            val message = commitObj["commit"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.trim().orEmpty()
            if (sha.isEmpty() || message.isEmpty()) null else GithubCommit(repo, message, sha)
        }
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
     * Импортировать коммиты за период в записи дневника. token = null → только публичная
     * активность; token != null (привязан в настройках) → видно и приватные репозитории.
     * Уже существующие дни не трогаем — иначе затёрли бы правки, сделанные руками.
     */
    suspend fun import(
        userId: UUID,
        login: String,
        from: LocalDate,
        to: LocalDate,
        projectId: UUID?,
        token: String? = null,
    ): ImportResult {
        val pushesByDay = parsePushesByDay(fetchEvents(login, token), from, to)

        val maxCompareCalls = if (token != null) MAX_COMPARE_CALLS_AUTHENTICATED else MAX_COMPARE_CALLS_ANONYMOUS
        var compareCallsUsed = 0
        var truncated = false
        val commitsByDay = sortedMapOf<LocalDate, MutableList<GithubCommit>>()

        outer@ for ((day, pushes) in pushesByDay) {
            for (push in pushes) {
                if (compareCallsUsed >= maxCompareCalls) {
                    truncated = true
                    break@outer
                }
                compareCallsUsed++
                val commits = try {
                    parseCommitsFromCompare(fetchCompare(push.repo, push.before, push.head, token), push.repo)
                } catch (e: GithubRateLimitException) {
                    truncated = true
                    break@outer
                } catch (e: Exception) {
                    emptyList() // этот конкретный пуш не сравнился — пропускаем, остальные всё равно важны
                }
                if (commits.isNotEmpty()) {
                    commitsByDay.getOrPut(day) { mutableListOf() }.addAll(commits)
                }
            }
        }
        // Один и тот же коммит может прийти из нескольких пушей (например, повторный push той же ветки).
        val byDay = commitsByDay.mapValues { (_, list) -> list.distinctBy { it.sha } }

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
        return ImportResult(days = byDay.size, commits = commits, created = created, skipped = skipped, truncated = truncated)
    }
}
