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
import java.time.ZoneId
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

/** Сколько символов описания коммита брать в запись: суть — да, простыня — нет. */
private const val MAX_COMMIT_BODY_CHARS = 600

/** Служебные подписи в конце сообщения коммита — для дневника это шум. */
private val TRAILER = Regex("""^(Co-Authored-By|Signed-off-by|Reviewed-by|Change-Id):.*""", RegexOption.IGNORE_CASE)

/** Режим «один репозиторий»: максимум страниц по 100 коммитов за один импорт. internal — для теста. */
internal const val MAX_REPO_PAGES = 10

/** "владелец/репозиторий" по правилам имён GitHub. */
private val REPO_NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}$""")

/** Результат импорта для ответа клиенту. */
data class ImportResult(
    val days: Int,
    val commits: Int,
    val created: Int,
    val skipped: Int,
    /** Догрузка коммитов остановилась раньше конца — упёрлись в лимит запросов (свой или GitHub). */
    val truncated: Boolean,
    /** Дни, запись которых обновлена: за день появились новые коммиты. */
    val updated: Int = 0,
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

    /**
     * Текст записи из коммитов за день — это и есть сырьё, которое дальше разберёт ИИ.
     * [repo] не null — режим «один репозиторий»: имя репозитория уже в заголовке, в строках не повторяем.
     */
    fun buildEntryText(day: LocalDate, commits: List<GithubCommit>, repo: String? = null): String {
        val where = if (repo != null) " в $repo" else ""
        val header = "Коммиты за $day$where (импорт из GitHub, ${commits.size} шт.):"
        val lines = commits.joinToString("\n") { commit ->
            val title = commit.message.lineSequence().first().trim()
            val prefix = if (repo != null) "" else "${commit.repo}: "
            "- $prefix$title (${commit.sha.take(7)})" + commitBody(commit.message).joinToString("") { "\n  $it" }
        }
        return (header + "\n" + lines).take(MAX_RAW_TEXT_LENGTH)
    }

    /**
     * Описание коммита (всё после первой строки) — без пустых строк и служебных подписей
     * (`Co-Authored-By:`, `Signed-off-by:`), не длиннее [MAX_COMMIT_BODY_CHARS] символов.
     *
     * Раньше брали только первую строку — «суть коммита». Но при соглашении Conventional
     * Commits первая строка бывает просто типом (`feat(android)`), а вся суть — в описании:
     * импорт видел бы десятки раз «feat(android)» и ничего больше.
     */
    fun commitBody(message: String): List<String> {
        val lines = message.lines().drop(1).map { it.trim() }
            .filter { it.isNotEmpty() && !TRAILER.matches(it) }
        val out = mutableListOf<String>()
        var used = 0
        for (line in lines) {
            if (used + line.length > MAX_COMMIT_BODY_CHARS) {
                val rest = MAX_COMMIT_BODY_CHARS - used
                out.add(if (rest > 40) line.take(rest).substringBeforeLast(' ') + "…" else "…")
                break
            }
            out.add(line)
            used += line.length
        }
        return out
    }

    /**
     * Режим «один репозиторий»: страница коммитов автора за период —
     * `GET /repos/{repo}/commits?author=&since=&until=&per_page=100&page=N`.
     * В отличие от ленты событий, здесь нет потолка «~90 дней и 300 событий» и не нужен
     * отдельный запрос на каждый пуш: GitHub сразу отдаёт сообщения коммитов.
     * Вынесено в переменную, чтобы тесты подставляли свой ответ.
     */
    var fetchRepoCommits: suspend (repo: String, author: String, from: LocalDate, to: LocalDate, page: Int, token: String?) -> String =
        { repo, author, from, to, page, token ->
            val response = client.get("https://api.github.com/repos/$repo/commits") {
                url.parameters.append("author", author)
                url.parameters.append("since", "${from}T00:00:00Z")
                // until — начало СЛЕДУЮЩЕГО дня: иначе коммиты последнего дня периода не попали бы.
                url.parameters.append("until", "${to.plusDays(1)}T00:00:00Z")
                url.parameters.append("per_page", "100")
                url.parameters.append("page", page.toString())
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "DevLog")
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
            when {
                response.status.isSuccess() -> response.bodyAsText()
                // 409 — в репозитории ещё нет ни одного коммита: это не ошибка, просто пусто.
                response.status.value == 409 -> "[]"
                response.status.value == 403 && response.headers["X-RateLimit-Remaining"] == "0" ->
                    throw GithubRateLimitException()
                else -> error(
                    when (response.status.value) {
                        404 -> "Репозиторий $repo не найден или нет доступа. Для приватного (в т.ч. чужого) " +
                            "репозитория нужен классический токен GitHub с правом repo"
                        401 -> "Токен GitHub недействителен или истёк — привяжите новый в настройках"
                        403 -> "GitHub отказал в доступе к $repo — токену не хватает прав"
                        else -> "GitHub ответил ${response.status.value}"
                    },
                )
            }
        }

    /**
     * Привести ввод пользователя к "владелец/репозиторий": принимает и ссылку
     * `https://github.com/owner/repo(.git)`. null — это не похоже на репозиторий GitHub.
     */
    fun normalizeRepo(raw: String): String? {
        val cleaned = raw.trim()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/")
            .removeSuffix("/").removeSuffix(".git")
        return cleaned.takeIf { REPO_NAME.matches(it) }
    }

    /**
     * Разобрать страницу `/repos/{repo}/commits` в коммиты по дням (по дате АВТОРА коммита
     * в поясе [zone]: GitHub отдаёт время в UTC, а работа идёт по местным часам — ночной
     * коммит в 02:00 по Москве иначе уехал бы во вчера). Merge-коммиты (больше одного
     * родителя) пропускаем: «Merge branch …» ничего не говорит о работе. Чистая функция.
     */
    fun parseRepoCommits(
        body: String,
        repo: String,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId = ZoneOffset.UTC,
    ): List<Pair<LocalDate, GithubCommit>> {
        val array = runCatching { json.parseToJsonElement(body).jsonArray }.getOrElse { return emptyList() }
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val sha = obj["sha"]?.jsonPrimitive?.content.orEmpty()
            val commit = obj["commit"]?.jsonObject ?: return@mapNotNull null
            val message = commit["message"]?.jsonPrimitive?.content?.trim().orEmpty()
            val date = commit["author"]?.jsonObject?.get("date")?.jsonPrimitive?.content
                ?: commit["committer"]?.jsonObject?.get("date")?.jsonPrimitive?.content
                ?: return@mapNotNull null
            val day = runCatching { Instant.parse(date).atZone(zone).toLocalDate() }.getOrNull()
                ?: return@mapNotNull null
            val parents = runCatching { obj["parents"]?.jsonArray?.size }.getOrNull() ?: 1
            if (sha.isEmpty() || message.isEmpty() || parents > 1 || day < from || day > to) null
            else day to GithubCommit(repo, message, sha)
        }
    }

    /** id записи в режиме «один репозиторий»: свой на каждый репозиторий и день. */
    fun repoEntryIdFor(userId: UUID, repo: String, day: LocalDate): UUID =
        UUID.nameUUIDFromBytes("devlog-github-repo:$userId:${repo.lowercase()}:$day".toByteArray())

    /**
     * Импорт коммитов автора [login] из одного репозитория [repo] за период. Повторный импорт
     * дописывает в запись дня появившиеся коммиты (если запись не правили руками) —
     * см. ImportedEntries.
     */
    suspend fun importRepo(
        userId: UUID,
        login: String,
        repo: String,
        from: LocalDate,
        to: LocalDate,
        projectId: UUID?,
        token: String? = null,
        zone: ZoneId = ZoneOffset.UTC,
    ): ImportResult {
        val byDay = sortedMapOf<LocalDate, MutableList<GithubCommit>>()
        var truncated = false
        // Запрашиваем на день шире с обеих сторон: since/until у GitHub — в UTC, а дни
        // считаем в поясе пользователя; лишнее отсечёт parseRepoCommits по [from]..[to].
        val fetchFrom = if (zone == ZoneOffset.UTC) from else from.minusDays(1)
        val fetchTo = if (zone == ZoneOffset.UTC) to else to.plusDays(1)
        for (page in 1..MAX_REPO_PAGES) {
            val body = try {
                fetchRepoCommits(repo, login, fetchFrom, fetchTo, page, token)
            } catch (e: GithubRateLimitException) {
                // Лимит GitHub посреди страниц — сохраняем то, что уже собрали.
                if (page == 1) throw IllegalStateException("GitHub временно отказывает в запросах (превышен лимит). Попробуйте позже")
                truncated = true
                break
            }
            val pageSize = runCatching { json.parseToJsonElement(body).jsonArray.size }.getOrDefault(0)
            parseRepoCommits(body, repo, from, to, zone).forEach { (day, c) -> byDay.getOrPut(day) { mutableListOf() }.add(c) }
            if (pageSize < 100) break
            if (page == MAX_REPO_PAGES) truncated = true
        }
        // GitHub отдаёт коммиты от новых к старым — в записи дня удобнее в порядке работы.
        val days = byDay.mapValues { (_, list) -> list.distinctBy { it.sha }.reversed() }
        return saveRepoDays(userId, repo, days, projectId, truncated)
    }

    /** Сколько коммитов принимаем за один запрос с ПК: год работы с запасом. */
    const val MAX_PUSHED_COMMITS = 3000

    /**
     * Коммиты, присланные с ПК (`git log` локальной копии, см. tools/push-day-files.mjs) —
     * способ без токена GitHub: чужой приватный репозиторий виден локальной копии и так.
     * Дата коммита уже посчитана на ПК по часам автора. Записи — ровно те же, что у
     * [importRepo] (тот же id на репозиторий и день, тот же текст), поэтому способы можно
     * чередовать: повтор с теми же коммитами ничего не меняет.
     *
     * Порядок коммитов внутри дня — как прислали (скрипт шлёт от старых к новым).
     * null — в списке есть коммит с неверной датой или без sha/сообщения.
     */
    fun importCommits(userId: UUID, repo: String, commits: List<CommitDto>, projectId: UUID?): ImportResult? {
        val byDay = sortedMapOf<LocalDate, MutableList<GithubCommit>>()
        for (c in commits) {
            val day = parseDateOrNull(c.date) ?: return null
            val sha = c.sha.trim().lowercase()
            val message = c.message.trim()
            if (!SHA.matches(sha) || message.isEmpty()) return null
            byDay.getOrPut(day) { mutableListOf() }.add(GithubCommit(repo, message, sha))
        }
        return saveRepoDays(userId, repo, byDay.mapValues { (_, list) -> list.distinctBy { it.sha } }, projectId, truncated = false)
    }

    private val SHA = Regex("""^[0-9a-f]{7,40}$""")

    private fun saveRepoDays(
        userId: UUID,
        repo: String,
        days: Map<LocalDate, List<GithubCommit>>,
        projectId: UUID?,
        truncated: Boolean,
    ) = saveDays(userId, days, truncated) { day, commits ->
        ImportedEntries.Prepared(
            id = repoEntryIdFor(userId, repo, day),
            input = ValidEntryInput(day, buildEntryText(day, commits, repo), projectId, "github", null),
            source = "github:$repo",
        )
    }

    /** Общая часть обоих режимов: записи по дням через ImportedEntries и подсчёт итогов. */
    private fun saveDays(
        userId: UUID,
        days: Map<LocalDate, List<GithubCommit>>,
        truncated: Boolean,
        prepare: (LocalDate, List<GithubCommit>) -> ImportedEntries.Prepared,
    ): ImportResult {
        var created = 0
        var updated = 0
        var skipped = 0
        for ((day, commits) in days) {
            when (ImportedEntries.upsert(userId, prepare(day, commits))) {
                ImportedEntries.Outcome.CREATED -> created++
                ImportedEntries.Outcome.UPDATED -> updated++
                else -> skipped++
            }
        }
        return ImportResult(
            days = days.size,
            commits = days.values.sumOf { it.size },
            created = created,
            skipped = skipped,
            truncated = truncated,
            updated = updated,
        )
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
     * Уже существующий день дополняется новыми коммитами, только если запись не правили
     * руками (см. ImportedEntries) — иначе затёрли бы правки пользователя.
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
        return saveDays(userId, byDay, truncated) { day, commits ->
            ImportedEntries.Prepared(
                id = entryIdFor(userId, day),
                input = ValidEntryInput(day, buildEntryText(day, commits), projectId, "github", null),
                source = "github:events",
            )
        }
    }
}
