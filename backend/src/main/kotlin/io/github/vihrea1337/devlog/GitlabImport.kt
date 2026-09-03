package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Импорт коммитов с GitLab — второй провайдер той же киллер-фичи, что и GithubImport.kt
 * (см. его докстринг за общей идеей). Устроен ПО-ДРУГОМУ внутри, потому что реальный API
 * GitLab устроен иначе, чем у GitHub, — не копия с заменой имён, а отдельная реализация
 * под реальную форму ответа (проверено по официальной документации GitLab, не по догадке
 * "наверняка так же, как GitHub"):
 *
 * 1. **Токен обязателен, анонимного режима нет.** У GitHub есть публичный
 *    `/users/{login}/events/public` без токена. У GitLab `GET /events` (события ТЕКУЩЕГО
 *    пользователя) требует токен с правами `read_user` или `api` — анонимного эквивалента
 *    не существует. Поэтому здесь нет отдельного "публичного" режима и нет логина пользователя
 *    в настройках: раз токен обязателен, `GET /events` сам возвращает события ЕГО владельца,
 *    указывать логин отдельно незачем (в отличие от GitHub, где логин нужен даже для публичного
 *    импорта, а токен — опционален и лишь расширяет видимость).
 * 2. **Событие пуша УЖЕ несёт кое-что о коммитах**, в отличие от нынешнего GitHub (у которого
 *    `payload.commits` пропал совсем, см. докстринг GithubImport.kt). У GitLab `push_data`
 *    содержит `commit_count`, `commit_from`, `commit_to` И `commit_title` — заголовок ОДНОГО
 *    (обычно последнего) коммита. Поэтому для пуша **ровно с одним коммитом** запрос на
 *    Compare API вообще не нужен — сообщение уже есть в самом событии. Догрузка через Compare
 *    (`GET /projects/:id/repository/compare?from=&to=`) нужна только пушам с НЕСКОЛЬКИМИ
 *    коммитами (`commit_count > 1`) — экономия запросов, которой у GitHub-импортёра нет.
 * 3. **Событие даёт только числовой `project_id`**, не готовое "owner/repo" — в отличие от
 *    GitHub, где `repo.name` уже в событии. Название репозитория приходится ДОГРУЖАТЬ отдельным
 *    запросом (`GET /projects/:id` → `path_with_namespace`), с кэшем на один прогон импорта
 *    (один и тот же проект в пуше встречается многократно — незачем спрашивать его имя дважды).
 * 4. **Rate-limit GitLab — HTTP 429**, а не 403 с заголовком `X-RateLimit-Remaining: 0`, как
 *    у GitHub (см. официальную документацию по лимитам GitLab). Догрузка (Compare/имя проекта)
 *    останавливается по этому статусу и не долбит API дальше — то, что уже собрано, всё равно
 *    уходит в записи, ровно как у GitHub-импортёра (см. [ImportResult.truncated]).
 *
 * Поддерживается только gitlab.com (без self-hosted инстансов) — то же упрощение MVP,
 * что у GithubImport.kt (только github.com).
 *
 * ⚠️ Всё вышеперечисленное — по документации GitLab, БЕЗ живой проверки настоящим токеном
 * (в отличие от GitHub-импорта, который сверялся вживую через `gh auth token`): токена
 * GitLab для ручной проверки не было. GitHub уже один раз разошёлся с документацией на
 * практике (см. GithubImport.kt) — не исключено, что и здесь реальность окажется другой.
 * Первая живая проверка — за пользователем, см. docs/roadmap.md.
 */

/** Один коммит из GitLab (из push_data события или из ответа Compare API). */
data class GitlabCommit(val repo: String, val message: String, val sha: String) {
    /** Ссылка на коммит — кладём в запись, чтобы можно было вернуться к исходнику. */
    val url: String get() = "https://gitlab.com/$repo/-/commit/$sha"
}

/** Один пуш из ленты событий: либо уже с сообщением (commit_count == 1), либо только с диапазоном для Compare. */
data class GitlabPushRef(
    val projectId: Long,
    val commitFrom: String,
    val commitTo: String,
    val commitCount: Int,
    /** Заголовок коммита, если он один — тогда Compare не нужен вовсе. */
    val singleCommitTitle: String?,
)

/** Токен всегда есть (см. докстринг класса) — единый лимит без анонимного/приватного различия. */
internal const val MAX_GITLAB_LOOKUP_CALLS = 100

/** Результат импорта — та же форма, что у GitHub (переиспользуем ImportResultDto на клиенте). */
data class GitlabImportResult(
    val days: Int,
    val commits: Int,
    val created: Int,
    val skipped: Int,
    val truncated: Boolean,
)

/** GitLab ответил 429 — это именно исчерпанный лимит запросов, не "нет доступа". */
internal class GitlabRateLimitException : RuntimeException("GitLab rate limit exceeded")

object GitlabImporter {
    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 20_000 } }
    }

    /** Загрузка ленты пушей. Вынесена отдельно, чтобы тесты подставляли свой ответ. */
    var fetchEvents: suspend (token: String, from: LocalDate, to: LocalDate) -> String = { token, from, to ->
        // Расширяем окно на день с каждой стороны и всё равно фильтруем сами в parsePushesByDay —
        // не полагаемся на то, что GitLab включает/исключает границы after/before ровно так,
        // как мы ожидаем (то же осторожное допущение, что и в остальном этом файле).
        val response = client.get(
            "https://gitlab.com/api/v4/events" +
                "?action=pushed&after=${from.minusDays(1)}&before=${to.plusDays(1)}&per_page=100",
        ) {
            header("PRIVATE-TOKEN", token)
        }
        if (!response.status.isSuccess()) {
            error(
                when (response.status.value) {
                    401 -> "Токен GitLab недействителен или истёк — привяжите новый в настройках"
                    429 -> "GitLab временно отказывает в запросах (превышен лимит). Попробуйте позже"
                    else -> "GitLab ответил ${response.status.value}"
                },
            )
        }
        response.bodyAsText()
    }

    /** Догрузка коммитов пуша с НЕСКОЛЬКИМИ коммитами через Compare API. */
    var fetchCompare: suspend (projectId: Long, from: String, to: String, token: String) -> String =
        { projectId, from, to, token ->
            val response = client.get(
                "https://gitlab.com/api/v4/projects/$projectId/repository/compare?from=$from&to=$to",
            ) {
                header("PRIVATE-TOKEN", token)
            }
            if (!response.status.isSuccess()) {
                if (response.status.value == 429) throw GitlabRateLimitException()
                // Отдельный пуш не удалось сравнить (например, проект стал недоступен,
                // force-push унёс commit_from) — не роняем весь импорт из-за одного пуша.
                error("GitLab ответил ${response.status.value} на compare $projectId $from...$to")
            }
            response.bodyAsText()
        }

    /** Имя проекта (path_with_namespace) по числовому id — событие даёт только id. */
    var fetchProjectPath: suspend (projectId: Long, token: String) -> String = { projectId, token ->
        val response = client.get("https://gitlab.com/api/v4/projects/$projectId") {
            header("PRIVATE-TOKEN", token)
        }
        if (!response.status.isSuccess()) {
            if (response.status.value == 429) throw GitlabRateLimitException()
            error("GitLab ответил ${response.status.value} на projects/$projectId")
        }
        json.parseToJsonElement(response.bodyAsText()).jsonObject["path_with_namespace"]
            ?.jsonPrimitive?.content
            ?: error("В ответе GitLab нет path_with_namespace для проекта $projectId")
    }

    /**
     * Разобрать ленту событий в пуши по дням. Функция чистая (без сети и БД) — удобно
     * тестировать на сохранённом куске настоящего ответа.
     */
    fun parsePushesByDay(body: String, from: LocalDate, to: LocalDate): Map<LocalDate, List<GitlabPushRef>> {
        val events = runCatching { json.parseToJsonElement(body).jsonArray }.getOrElse { return emptyMap() }
        val byDay = sortedMapOf<LocalDate, MutableList<GitlabPushRef>>()

        for (element in events) {
            val event = element.jsonObject
            // Нас интересуют только пуши — остальные типы событий коммитов не несут.
            if (event["action_name"]?.jsonPrimitive?.content != "pushed") continue
            val createdAt = event["created_at"]?.jsonPrimitive?.content ?: continue
            val day = runCatching { Instant.parse(createdAt).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull() ?: continue
            if (day < from || day > to) continue

            val projectId = event["project_id"]?.jsonPrimitive?.longOrNull ?: continue
            val pushData = event["push_data"]?.jsonObject ?: continue
            // contentOrNull, а НЕ content: JsonNull — это тоже JsonPrimitive (content вернул бы
            // строку "null" из четырёх символов, а не Kotlin null). Массовые пуши (сверх лимита
            // событий GitLab) шлют commit_from/to/title именно JSON-null'ом по документации —
            // на content() здесь это выглядело бы как "непустая строка null" и не отфильтровалось бы.
            val commitFrom = pushData["commit_from"]?.jsonPrimitive?.contentOrNull
            val commitTo = pushData["commit_to"]?.jsonPrimitive?.contentOrNull
            // Массовые пуши (сверх лимита событий GitLab) приходят с commit_count: 0 и
            // пустыми commit_from/to/title — по документации GitLab. Нечего импортировать —
            // не наш баг, а честное ограничение источника, как "публичность" у GitHub.
            if (commitFrom.isNullOrBlank() || commitTo.isNullOrBlank() || commitFrom == commitTo) continue
            val commitCount = pushData["commit_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
            if (commitCount < 1) continue
            val title = pushData["commit_title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

            byDay.getOrPut(day) { mutableListOf() }
                .add(GitlabPushRef(projectId, commitFrom, commitTo, commitCount, title.takeIf { commitCount == 1 }))
        }
        return byDay
    }

    /**
     * Разобрать ответ Compare API в список коммитов. GitLab отдаёт коммиты как `id`
     * (полный sha) и `message` ИЛИ `title` — сообщение читаем в message, если оно есть
     * (полное, с телом), иначе title (только первая строка); не гадаем заранее, какое
     * поле реально придёт, чтобы не потерять коммит из-за неверного предположения.
     */
    fun parseCommitsFromCompare(body: String, repo: String): List<GitlabCommit> {
        val commitsArray = runCatching { json.parseToJsonElement(body).jsonObject["commits"]?.jsonArray }
            .getOrNull() ?: return emptyList()
        return commitsArray.mapNotNull { element ->
            val commitObj = element.jsonObject
            val sha = commitObj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            // contentOrNull, а не content, и ?: между полями, а не ?: между JsonElement —
            // если message присутствует, но JSON-null (не просто отсутствует), content()
            // на JsonNull дал бы строку "null" вместо честного перехода к title.
            val message = (commitObj["message"]?.jsonPrimitive?.contentOrNull
                ?: commitObj["title"]?.jsonPrimitive?.contentOrNull)?.trim().orEmpty()
            if (sha.isEmpty() || message.isEmpty()) null else GitlabCommit(repo, message, sha)
        }
    }

    /** Текст записи из коммитов за день — та же форма, что у GithubImporter.buildEntryText. */
    fun buildEntryText(day: LocalDate, commits: List<GitlabCommit>): String {
        val header = "Коммиты за $day (импорт из GitLab, ${commits.size} шт.):"
        val lines = commits.joinToString("\n") { commit ->
            val title = commit.message.lineSequence().first()
            "- ${commit.repo}: $title (${commit.sha.take(7)})"
        }
        return (header + "\n" + lines).take(MAX_RAW_TEXT_LENGTH)
    }

    /** Отдельное пространство id от GitHub-импорта — иначе один и тот же день из двух
     * провайдеров претендовал бы на одну и ту же запись и затирал бы друг друга. */
    fun entryIdFor(userId: UUID, day: LocalDate): UUID =
        UUID.nameUUIDFromBytes("devlog-gitlab:$userId:$day".toByteArray())

    /**
     * Импортировать коммиты за период в записи дневника. Токен обязателен (см. докстринг
     * класса) — вызывающий код (ImportRoutes.kt) обязан проверить привязку заранее.
     * Уже существующие дни не трогаем — иначе затёрли бы правки, сделанные руками.
     */
    suspend fun import(userId: UUID, from: LocalDate, to: LocalDate, projectId: UUID?, token: String): GitlabImportResult {
        val pushesByDay = parsePushesByDay(fetchEvents(token, from, to), from, to)

        var callsUsed = 0
        var truncated = false
        val projectPathCache = mutableMapOf<Long, String>()
        val commitsByDay = sortedMapOf<LocalDate, MutableList<GitlabCommit>>()

        outer@ for ((day, pushes) in pushesByDay) {
            for (push in pushes) {
                if (callsUsed >= MAX_GITLAB_LOOKUP_CALLS) {
                    truncated = true
                    break@outer
                }

                var repoLabel = projectPathCache[push.projectId]
                if (repoLabel == null) {
                    callsUsed++
                    repoLabel = try {
                        fetchProjectPath(push.projectId, token)
                    } catch (e: GitlabRateLimitException) {
                        truncated = true
                        break@outer
                    } catch (e: Exception) {
                        // Имя не достали (например, проект удалён/недоступен) — не критично,
                        // это только подпись в тексте записи, коммиты всё равно важны.
                        "gitlab-project-${push.projectId}"
                    }
                    projectPathCache[push.projectId] = repoLabel
                }

                val commits = if (push.singleCommitTitle != null) {
                    // Ровно один коммит — его сообщение уже пришло в самом событии,
                    // Compare-запрос не нужен (экономия, недоступная GitHub-импортёру).
                    listOf(GitlabCommit(repoLabel, push.singleCommitTitle, push.commitTo))
                } else {
                    if (callsUsed >= MAX_GITLAB_LOOKUP_CALLS) {
                        truncated = true
                        break@outer
                    }
                    callsUsed++
                    try {
                        parseCommitsFromCompare(fetchCompare(push.projectId, push.commitFrom, push.commitTo, token), repoLabel)
                    } catch (e: GitlabRateLimitException) {
                        truncated = true
                        break@outer
                    } catch (e: Exception) {
                        emptyList() // этот конкретный пуш не сравнился — пропускаем, остальные всё равно важны
                    }
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
                    sourceType = "gitlab",
                    timeSpentMin = null,
                    id = entryId,
                ),
            )
            created++
        }
        return GitlabImportResult(days = byDay.size, commits = commits, created = created, skipped = skipped, truncated = truncated)
    }
}
