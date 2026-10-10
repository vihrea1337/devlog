package io.github.vihrea1337.devlog.android.data

import io.github.vihrea1337.devlog.ActivityStatsDto
import io.github.vihrea1337.devlog.GithubImportRequest
import io.github.vihrea1337.devlog.ImportResultDto
import io.github.vihrea1337.devlog.LoginRequest
import io.github.vihrea1337.devlog.NewReport
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.RegisterRequest
import io.github.vihrea1337.devlog.ReportDto
import io.github.vihrea1337.devlog.UpdateProfile
import io.github.vihrea1337.devlog.UpdateReport
import io.github.vihrea1337.devlog.UserDto
import io.github.vihrea1337.devlog.android.sync.SyncScheduler
import io.github.vihrea1337.devlog.sync.EntryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Репозитории — слой между экранами (ViewModel) и источниками данных (сеть, база).
// ViewModel не знает про Retrofit и HTTP-коды: она просит «дай отчёты», «войди».
// Каждый репозиторий — ИНТЕРФЕЙС + реализация: в юнит-тестах ViewModel подставляем
// фейковую реализацию без сети (см. src/test). Связку интерфейс → реализация задаёт
// RepositoryModule (@Binds).

/** Вход, регистрация, выход. */
interface AuthRepository {
    suspend fun login(email: String, password: String)
    suspend fun register(email: String, password: String, displayName: String)
    fun logout()
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: DevLogApi,
    private val session: SessionStore,
    private val entries: EntryRepository,
    private val syncScheduler: SyncScheduler,
) : AuthRepository {

    override suspend fun login(email: String, password: String) {
        val response = api.login(LoginRequest(email.trim(), password))
        startSession(response.token, response.user.displayName)
    }

    override suspend fun register(email: String, password: String, displayName: String) {
        val response = api.register(RegisterRequest(email.trim(), password, displayName.trim()))
        startSession(response.token, response.user.displayName)
    }

    /**
     * Перед новой сессией стираем локальный кэш: если до этого на телефоне был другой
     * аккаунт (или прошлая сессия кончилась по 401), его записи не должны мелькнуть в ленте.
     */
    private suspend fun startSession(token: String, name: String) {
        withContext(Dispatchers.IO) { entries.clearLocalData() }
        session.save(token, name)
        syncScheduler.schedulePeriodic()
    }

    override fun logout() {
        syncScheduler.cancelAll()
        entries.clearLocalData()
        session.clear()
    }
}

/** Проекты — для фильтра ленты, привязки записей и отчётов по проекту. */
interface ProjectsRepository {
    suspend fun projects(): List<ProjectDto>
}

@Singleton
class ProjectsRepositoryImpl @Inject constructor(private val api: DevLogApi) : ProjectsRepository {
    override suspend fun projects(): List<ProjectDto> = api.getProjects().filterNot { it.archived }
}

/** Отчёты за период: список, сборка, правка, публичная ссылка. */
interface ReportsRepository {
    suspend fun reports(): List<ReportDto>
    suspend fun report(id: String): ReportDto
    suspend fun create(periodStart: String, periodEnd: String, projectId: String?): ReportDto
    suspend fun update(id: String, title: String, contentMd: String): ReportDto
    suspend fun delete(id: String)

    /** Включить публичную ссылку; возвращает ПОЛНЫЙ адрес для отправки. */
    suspend fun share(id: String): Pair<String, String>
    suspend fun unshare(id: String)
}

@Singleton
class ReportsRepositoryImpl @Inject constructor(private val api: DevLogApi) : ReportsRepository {
    override suspend fun reports() = api.getReports()
    override suspend fun report(id: String) = api.getReport(id)

    override suspend fun create(periodStart: String, periodEnd: String, projectId: String?) =
        api.createReport(NewReport(periodStart = periodStart, periodEnd = periodEnd, projectId = projectId))

    override suspend fun update(id: String, title: String, contentMd: String) =
        api.updateReport(id, UpdateReport(contentMd = contentMd, title = title))

    override suspend fun delete(id: String) {
        val r = api.deleteReport(id)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    override suspend fun share(id: String): Pair<String, String> {
        val r = api.shareReport(id)
        return r.shareToken to DevLogApi.absoluteUrl(r.url)
    }

    override suspend fun unshare(id: String) {
        val r = api.unshareReport(id)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }
}

/** Профиль: кто я, календарь активности, импорт коммитов GitHub. */
interface ProfileRepository {
    suspend fun me(): UserDto
    suspend fun activity(): ActivityStatsDto
    suspend fun setGithubLogin(login: String): UserDto
    suspend fun importGithub(periodStart: String, periodEnd: String, projectId: String?): ImportResultDto
}

@Singleton
class ProfileRepositoryImpl @Inject constructor(private val api: DevLogApi) : ProfileRepository {
    override suspend fun me() = api.me()
    override suspend fun activity() = api.activity()
    override suspend fun setGithubLogin(login: String) = api.updateMe(UpdateProfile(githubLogin = login.trim()))
    override suspend fun importGithub(periodStart: String, periodEnd: String, projectId: String?) =
        api.importGithub(GithubImportRequest(periodStart = periodStart, periodEnd = periodEnd, projectId = projectId))
}
