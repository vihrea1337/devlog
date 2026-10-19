package io.github.vihrea1337.devlog

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Автоматика отчётов — то, что сервер делает сам, без кнопок. Всё по поясу пользователя:
 *
 * 1. **Коммиты раз в сутки** (после [Automation.MORNING_HOUR]:00): импорт GitHub в режиме
 *    «один репозиторий» за последние [Automation.COMMIT_DAYS] дней — поздние пуши тоже
 *    подхватятся, а повтор ничего не дублирует (см. ImportedEntries). Нужен привязанный токен
 *    GitHub: для чужого приватного репозитория — classic с правом `repo`. Без токена коммиты
 *    присылает ПК (`tools/push-day-files.mjs --git …`, ручка `/api/import/commits`) — записи
 *    получаются те же самые, а этот пункт просто выключен.
 * 2. **«Отчёт работодателю» трёх видов** (каждый включается отдельно):
 *    - **за день** — утром за вчера: файл дня дописывается до ночи, поэтому не вечером;
 *    - **за неделю** — в понедельник утром за прошлую неделю (пн–вс);
 *    - **за месяц** — черновик в последний день месяца после [Automation.MONTH_HOUR]:00 (отчёт
 *      сдаётся в конце месяца — остаётся время прочитать). Собирается в два шага: недели,
 *      потом месяц; готовые недельные отчёты берутся как есть (их могли поправить руками).
 *    Пропущенное (сервер лежал) догоняется: неделя — в любой день следующей, месяц — в первые
 *    [Automation.CATCH_UP_DAYS] дня следующего. Пустой период отчёта не даёт.
 *
 * О новых отчётах скрипт на ПК узнаёт через `/api/import/notices` и показывает уведомление.
 * Все решения «пора / не пора» — чистые функции от настроек и времени (на них тесты);
 * цикл [AutomationWorker] лишь раз в несколько минут спрашивает их по всем пользователям.
 */
object Automation {
    /** С какого часа (местного) забирать коммиты и собирать отчёты за вчера/прошлую неделю. */
    const val MORNING_HOUR = 4

    /** С какого часа последнего дня месяца собирать черновик месяца. */
    const val MONTH_HOUR = 12

    /** За сколько последних дней забирать коммиты при ежедневном импорте. */
    const val COMMIT_DAYS = 7L

    /** Сколько первых дней месяца догонять пропущенный черновик прошлого месяца. */
    const val CATCH_UP_DAYS = 3

    /** Виды отчётов — они же значения `reports.auto_kind`. */
    const val DAY = "day"
    const val WEEK = "week"
    const val MONTH = "month"

    /** Настройки одного пользователя — то, что читает планировщик. */
    data class Settings(
        val userId: UUID,
        val projectId: UUID? = null,
        val githubRepo: String? = null,
        val daily: Boolean = false,
        val weekly: Boolean = false,
        val monthly: Boolean = false,
        val zone: ZoneId = ZoneId.of("Europe/Moscow"),
        val lastCommitImportOn: LocalDate? = null,
        val lastDailyOn: LocalDate? = null,
        val lastWeeklyOn: LocalDate? = null,
        val lastMonthly: String? = null,
    )

    /** Период отчёта и его вид. */
    data class Period(val kind: String, val from: LocalDate, val to: LocalDate)

    /** Пора ли забирать коммиты: репозиторий задан, сегодня ещё не забирали и утро наступило. */
    fun commitsDue(s: Settings, now: ZonedDateTime): Boolean =
        s.githubRepo != null && now.hour >= MORNING_HOUR && s.lastCommitImportOn != now.toLocalDate()

    /** Какие отчёты пора собрать прямо сейчас — в порядке день → неделя → месяц. */
    fun reportsDue(s: Settings, now: ZonedDateTime): List<Period> {
        val today = now.toLocalDate()
        val out = mutableListOf<Period>()
        if (now.hour >= MORNING_HOUR) {
            val yesterday = today.minusDays(1)
            if (s.daily && s.lastDailyOn != yesterday) out += Period(DAY, yesterday, yesterday)
            val lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
            if (s.weekly && (s.lastWeeklyOn == null || s.lastWeeklyOn < lastMonday)) {
                out += Period(WEEK, lastMonday, lastMonday.plusDays(6))
            }
        }
        monthDue(s, now)?.let { out += Period(MONTH, it.atDay(1), it.atEndOfMonth()) }
        return out
    }

    /**
     * За какой месяц пора собрать черновик; null — не пора. Последний день месяца после
     * [MONTH_HOUR] — текущий месяц; первые [CATCH_UP_DAYS] дня — прошлый, если его не собирали.
     */
    fun monthDue(s: Settings, now: ZonedDateTime): YearMonth? {
        if (!s.monthly) return null
        val today = now.toLocalDate()
        val month = YearMonth.from(today)
        val target = when {
            today == month.atEndOfMonth() && now.hour >= MONTH_HOUR -> month
            today.dayOfMonth <= CATCH_UP_DAYS -> month.minusMonths(1)
            else -> return null
        }
        return target.takeIf { it.toString() != s.lastMonthly }
    }

    /** Ежедневный импорт коммитов. Возвращает строку итога для веба. */
    suspend fun importCommits(s: Settings, today: LocalDate): String {
        val repo = s.githubRepo ?: return "Репозиторий не задан"
        val login = UserRepository.findById(s.userId)?.githubLogin
            ?: return "Не указан логин GitHub в профиле — неясно, чьи коммиты брать"
        val token = GithubTokenRepository.tokenFor(s.userId)
            ?: return "Не привязан токен GitHub. Для чужого приватного репозитория нужен classic-токен с правом repo " +
                "(или присылайте коммиты с ПК: push-day-files.mjs --git)"
        return runCatching {
            val r = GithubImporter.importRepo(s.userId, login, repo, today.minusDays(COMMIT_DAYS), today, s.projectId, token, s.zone)
            "Коммитов: ${r.commits}, дней: ${r.days} (новых ${r.created}, обновлено ${r.updated})" +
                if (r.truncated) " — не всё: лимит GitHub" else ""
        }.getOrElse { "Ошибка: ${it.message ?: "GitHub не ответил"}" }
    }

    /**
     * Собрать «Отчёт работодателю» за период и сохранить. null — в периоде нет записей
     * (пустой отчёт никому не нужен). Месяц — в два шага (по неделям), день и неделя — разом.
     */
    suspend fun build(userId: UUID, p: Period, projectId: UUID?, autoKind: String?): ReportDto? {
        if (EntryRepository.list(userId, p.from, p.to, projectId).isEmpty()) return null
        val projectName = projectId?.let { ProjectRepository.getById(userId, it)?.name }
        val title = ReportGenerator.defaultTitle(p.from, p.to, today = p.to, projectName = projectName)
        val md = if (p.kind == MONTH) ReportGenerator.generateByWeeks(userId, title, p.from, p.to, projectId)
        else ReportGenerator.generate(userId, title, p.from, p.to, projectId, ReportGenerator.TEMPLATE_EMPLOYER)
        return ReportRepository.create(userId, projectId, title, p.from, p.to, "markdown", md, autoKind)
    }

    private fun kindLabel(kind: String) = when (kind) {
        DAY -> "за день"
        WEEK -> "за неделю"
        else -> "за месяц"
    }

    /** Один проход по всем пользователям с автоматикой. Ошибка одного не мешает остальным. */
    suspend fun runOnce(now: Instant = Instant.now()) {
        var aiUsed = false
        for (s in AutomationRepository.active()) {
            val local = now.atZone(s.zone)
            runCatching {
                if (commitsDue(s, local)) {
                    AutomationRepository.markCommits(s.userId, local.toLocalDate(), importCommits(s, local.toLocalDate()))
                }
                for (p in reportsDue(s, local)) {
                    // Отмечаем период ДО сборки: упади она — не повторяем каждые 10 минут
                    // (и не жжём лимит ИИ); причина будет видна в вебе.
                    AutomationRepository.markDone(s.userId, p)
                    // Подряд несколько отчётов (понедельник: день + неделя) — пауза под лимит Groq.
                    if (aiUsed) ReportGenerator.aiPause()
                    val result = runCatching { build(s.userId, p, s.projectId, p.kind) }
                    aiUsed = true
                    val msg = result.fold(
                        { r -> r?.let { "Собран отчёт ${kindLabel(p.kind)}: «${it.title}»" } ?: "Отчёт ${kindLabel(p.kind)}: записей нет" },
                        { e -> "Ошибка отчёта ${kindLabel(p.kind)}: ${e.message}" },
                    )
                    AutomationRepository.markResult(s.userId, msg)
                }
            }.onFailure { println("Автоматика: пользователь ${s.userId} — ${it.message}") }
        }
    }

    /** Ручные запуски, которые ещё идут (месяц по неделям — несколько минут). */
    private val running = ConcurrentHashMap.newKeySet<UUID>()

    /** Фон для ручной сборки — ответ на кнопку не ждёт пауз между запросами к ИИ. */
    var backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Ручной запуск из веба. «commits» — сразу (секунды). «day» / «week» / «month» — отчёт за
     * сегодня / текущую неделю / текущий месяц ПО СЕГОДНЯ, в фоне: итог появится в «Отчётах»
     * и в строке статуса. Отметки «уже собрано» не трогает — расписание идёт своим чередом.
     */
    suspend fun runNow(userId: UUID, action: String): AutomationRunResult? {
        val s = AutomationRepository.settings(userId)
        val today = LocalDate.now(s.zone)
        val period = when (action) {
            "commits" -> {
                if (s.githubRepo == null) return AutomationRunResult("Сначала укажите репозиторий и сохраните настройки")
                val msg = importCommits(s, today)
                AutomationRepository.markCommits(userId, today, msg)
                return AutomationRunResult(msg)
            }
            DAY -> Period(DAY, today, today)
            WEEK -> Period(WEEK, today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today)
            MONTH -> Period(MONTH, today.withDayOfMonth(1), today)
            else -> return null
        }
        if (!running.add(userId)) return AutomationRunResult("Отчёт уже собирается — подождите пару минут")
        backgroundScope.launch {
            try {
                val r = build(userId, period, s.projectId, autoKind = null)
                AutomationRepository.markResult(userId, r?.let { "Собран вручную: «${it.title}»" } ?: "За этот период записей нет")
            } catch (e: Exception) {
                AutomationRepository.markResult(userId, "Ошибка ручной сборки: ${e.message}")
            } finally {
                running.remove(userId)
            }
        }
        val minutes = if (period.kind == MONTH) ReportGenerator.weekRanges(period.from, period.to).size + 1 else 1
        return AutomationRunResult("Собираю отчёт ${kindLabel(period.kind)} — до $minutes мин. Он появится в «Отчётах».")
    }
}

object AutomationRepository {
    private val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/Moscow")

    /** Сколько дней отчёт автоматики считается «свежим» для уведомления на ПК. */
    private val NOTICE_FOR: Duration = Duration.ofDays(3)

    private fun ResultRow.zone(): ZoneId = runCatching { ZoneId.of(this[ReportAutomation.timeZone]) }.getOrDefault(DEFAULT_ZONE)

    private fun row(userId: UUID): ResultRow? =
        ReportAutomation.selectAll().where { ReportAutomation.userId eq userId }.singleOrNull()

    private fun ResultRow.toSettings() = Automation.Settings(
        userId = this[ReportAutomation.userId],
        projectId = this[ReportAutomation.projectId],
        githubRepo = this[ReportAutomation.githubRepo],
        daily = this[ReportAutomation.dailyReport],
        weekly = this[ReportAutomation.weeklyReport],
        monthly = this[ReportAutomation.monthlyReport],
        zone = zone(),
        lastCommitImportOn = this[ReportAutomation.lastCommitImportOn],
        lastDailyOn = this[ReportAutomation.lastDailyOn],
        lastWeeklyOn = this[ReportAutomation.lastWeeklyOn],
        lastMonthly = this[ReportAutomation.lastMonthly],
    )

    /** Настройки пользователя (без строки — всё выключено, пояс по умолчанию). */
    fun settings(userId: UUID): Automation.Settings = transaction {
        row(userId)?.toSettings() ?: Automation.Settings(userId)
    }

    fun get(userId: UUID): AutomationDto = transaction {
        val user = UserRepository.findById(userId)
        val linked = GithubTokenRepository.tokenFor(userId) != null
        val r = row(userId) ?: return@transaction AutomationDto(githubTokenLinked = linked, githubLogin = user?.githubLogin)
        AutomationDto(
            projectId = r[ReportAutomation.projectId]?.toString(),
            githubRepo = r[ReportAutomation.githubRepo],
            dailyReport = r[ReportAutomation.dailyReport],
            weeklyReport = r[ReportAutomation.weeklyReport],
            monthlyReport = r[ReportAutomation.monthlyReport],
            timeZone = r[ReportAutomation.timeZone],
            lastCommitImportOn = r[ReportAutomation.lastCommitImportOn]?.toString(),
            lastCommitImportResult = r[ReportAutomation.lastCommitImportResult],
            lastDailyOn = r[ReportAutomation.lastDailyOn]?.toString(),
            lastWeeklyOn = r[ReportAutomation.lastWeeklyOn]?.toString(),
            lastMonthly = r[ReportAutomation.lastMonthly],
            lastReportResult = r[ReportAutomation.lastReportResult],
            githubTokenLinked = linked,
            githubLogin = user?.githubLogin,
        )
    }

    /** Сохранить настройки (значения уже проверены вызывающим). */
    fun update(userId: UUID, body: UpdateAutomation, projectId: UUID?, githubRepo: String?, zone: ZoneId) = transaction {
        val now = Instant.now()
        if (row(userId) == null) {
            ReportAutomation.insert {
                it[ReportAutomation.userId] = userId
                it[ReportAutomation.projectId] = projectId
                it[ReportAutomation.githubRepo] = githubRepo
                it[dailyReport] = body.dailyReport
                it[weeklyReport] = body.weeklyReport
                it[monthlyReport] = body.monthlyReport
                it[timeZone] = zone.id
                it[updatedAt] = now
            }
        } else {
            ReportAutomation.update({ ReportAutomation.userId eq userId }) {
                it[ReportAutomation.projectId] = projectId
                it[ReportAutomation.githubRepo] = githubRepo
                it[dailyReport] = body.dailyReport
                it[weeklyReport] = body.weeklyReport
                it[monthlyReport] = body.monthlyReport
                it[timeZone] = zone.id
                it[updatedAt] = now
            }
        }
    }

    /** Пояс для импорта GitHub по кнопке: из настроек, а без них — UTC, как было всегда. */
    fun zoneFor(userId: UUID): ZoneId = transaction { row(userId)?.zone() } ?: ZoneOffset.UTC

    /** У кого включено хоть что-то — их и обходит планировщик. */
    fun active(): List<Automation.Settings> = transaction {
        ReportAutomation.selectAll()
            .where {
                ReportAutomation.githubRepo.isNotNull() or (ReportAutomation.dailyReport eq true) or
                    (ReportAutomation.weeklyReport eq true) or (ReportAutomation.monthlyReport eq true)
            }
            .map { it.toSettings() }
    }

    fun markCommits(userId: UUID, day: LocalDate, result: String) = transaction {
        ReportAutomation.update({ ReportAutomation.userId eq userId }) {
            it[lastCommitImportOn] = day
            it[lastCommitImportResult] = result.take(300)
        }
    }

    /** Отметить, что отчёт за период собран (или начат) — повторно его не собираем. */
    fun markDone(userId: UUID, p: Automation.Period) = transaction {
        ReportAutomation.update({ ReportAutomation.userId eq userId }) {
            when (p.kind) {
                Automation.DAY -> it[lastDailyOn] = p.from
                Automation.WEEK -> it[lastWeeklyOn] = p.from
                else -> it[lastMonthly] = YearMonth.from(p.from).toString()
            }
        }
    }

    fun markResult(userId: UUID, result: String) = transaction {
        ReportAutomation.update({ ReportAutomation.userId eq userId }) { it[lastReportResult] = result.take(300) }
    }

    /** Отчёты автоматики за последние дни — для уведомления на ПК. */
    fun recentNotices(userId: UUID, now: Instant = Instant.now()): List<ImportNoticeDto> =
        ReportRepository.autoSince(userId, now.minus(NOTICE_FOR)).map {
            ImportNoticeDto(it.id, it.autoKind.orEmpty(), it.title, it.createdAt, "/?report=${it.id}")
        }
}

/** Фоновый цикл автоматики: раз в [period] спрашивает Automation.runOnce. */
class AutomationWorker(private val period: Duration = Duration.ofMinutes(10)) {
    private var job: Job? = null

    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        if (job != null) return
        job = scope.launch {
            println("Автоматика отчётов запущена (проверка раз в ${period.toMinutes()} мин).")
            while (isActive) {
                runCatching { Automation.runOnce() }.onFailure { println("Автоматика: ошибка цикла — ${it.message}") }
                delay(period.toMillis())
            }
        }
    }
}

/** Настройки и ручной запуск автоматики — под обычным входом. */
fun Route.automationRoutes() = authenticate("auth-jwt") {
    get("/api/automation") {
        call.respond(AutomationRepository.get(call.userId()))
    }

    put("/api/automation") {
        val body = call.receive<UpdateAutomation>()
        val projectId = body.projectId?.takeIf { it.isNotBlank() }?.let {
            parseUuidOrNull(it) ?: return@put call.badRequest("Некорректный id проекта")
        }
        if (projectId != null && ProjectRepository.getById(call.userId(), projectId) == null) {
            return@put call.badRequest("Проект не найден")
        }
        val repo = body.githubRepo?.takeIf { it.isNotBlank() }?.let {
            GithubImporter.normalizeRepo(it)
                ?: return@put call.badRequest("Репозиторий — в виде «владелец/имя» или ссылкой https://github.com/владелец/имя")
        }
        val zone = runCatching { ZoneId.of(body.timeZone.trim()) }.getOrNull()
            ?: return@put call.badRequest("Неизвестный часовой пояс — нужен вид «Europe/Moscow»")
        AutomationRepository.update(call.userId(), body, projectId, repo, zone)
        call.respond(AutomationRepository.get(call.userId()))
    }

    post("/api/automation/run") {
        val action = call.receive<RunAutomation>().action
        val result = Automation.runNow(call.userId(), action)
            ?: return@post call.badRequest("Неизвестное действие — commits, day, week или month")
        call.respond(result)
    }
}
