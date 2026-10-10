package io.github.vihrea1337.devlog.android.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.vihrea1337.devlog.ActivityStatsDto
import io.github.vihrea1337.devlog.ImportResultDto
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.UserDto
import io.github.vihrea1337.devlog.android.data.AuthRepository
import io.github.vihrea1337.devlog.android.data.ProfileRepository
import io.github.vihrea1337.devlog.android.data.ProjectsRepository
import io.github.vihrea1337.devlog.android.reminder.ReminderScheduler
import io.github.vihrea1337.devlog.android.reminder.ReminderSettings
import io.github.vihrea1337.devlog.android.sync.SyncScheduler
import io.github.vihrea1337.devlog.android.ui.common.humanMessage
import io.github.vihrea1337.devlog.android.data.SessionInfo
import io.github.vihrea1337.devlog.sync.EntryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Период импорта коммитов: за сколько дней назад забрать. */
enum class ImportPeriod(val label: String, val days: Long) {
    WEEK("Неделя", 7),
    MONTH("Месяц", 30),
    QUARTER("3 месяца", 90),
}

/** Доля проекта во времени: имя, цвет, минуты. */
data class ProjectShare(val name: String, val color: String?, val minutes: Int)

/**
 * «Куда уходит время»: минуты по проектам и частые темы (теги от ИИ).
 * Чистый расчёт без Android и корутин — поэтому покрыт обычными юнит-тестами.
 */
data class TimeBreakdown(
    val shares: List<ProjectShare> = emptyList(),
    val topTags: List<Pair<String, Int>> = emptyList(),
) {
    companion object {
        fun from(entries: List<EntryDto>, projects: List<ProjectDto>, maxTags: Int = 10): TimeBreakdown {
            val byProject = projects.associateBy { it.id }
            // Только записи с указанным временем: «0 минут» не должно рисовать дугу в кольце.
            val shares = entries.filter { (it.timeSpentMin ?: 0) > 0 }
                // Проект, которого уже нет (удалили), считаем как «без проекта» — иначе
                // в легенде было бы две одинаковые строки «Без проекта».
                .groupBy { e -> e.projectId?.let { byProject[it] } }
                .map { (p, list) -> ProjectShare(p?.name ?: "Без проекта", p?.color, list.sumOf { it.timeSpentMin ?: 0 }) }
                .sortedByDescending { it.minutes }
            // Теги нормализуем: «Android» и « android» — одна и та же тема.
            val topTags = entries.flatMap { it.structured?.tags.orEmpty() }
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(maxTags).map { it.key to it.value }
            return TimeBreakdown(shares, topTags)
        }
    }
}

data class ProfileUiState(
    val user: UserDto? = null,
    val stats: ActivityStatsDto? = null,
    val projects: List<ProjectDto> = emptyList(),
    val isLoading: Boolean = true,
    // Импорт с GitHub:
    val githubLogin: String = "",
    val importPeriod: ImportPeriod = ImportPeriod.MONTH,
    val importProjectId: String? = null,
    val isImporting: Boolean = false,
    val importResult: ImportResultDto? = null,
    val error: String? = null,
    /** Куда уходит время: часы по проектам (все записи с указанным временем). */
    val shares: List<ProjectShare> = emptyList(),
    /** Частые темы — теги от ИИ по всем записям. */
    val topTags: List<Pair<String, Int>> = emptyList(),
    val readOnly: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profile: ProfileRepository,
    private val projectsRepo: ProjectsRepository,
    private val auth: AuthRepository,
    private val syncScheduler: SyncScheduler,
    private val reminders: ReminderScheduler,
    private val entries: EntryRepository,
    session: SessionInfo,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState(readOnly = session.readOnly))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    /** Настройки напоминания прямо из DataStore: переключатель всегда показывает правду. */
    val reminder: StateFlow<ReminderSettings> =
        reminders.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderSettings())

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val user = profile.me()
                val stats = profile.activity()
                val projects = runCatching { projectsRepo.projects() }.getOrDefault(emptyList())
                // Разбивка по проектам и темам — по локальному кэшу записей (работает и без сети).
                val all = withContext(Dispatchers.IO) { entries.entries().first() }
                val time = TimeBreakdown.from(all, projects)
                _state.update {
                    it.copy(
                        user = user,
                        stats = stats,
                        projects = projects,
                        shares = time.shares,
                        topTags = time.topTags,
                        isLoading = false,
                        // Поле логина заполняем один раз, чтобы не затирать то, что человек уже печатает.
                        githubLogin = it.githubLogin.ifEmpty { user.githubLogin.orEmpty() },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.humanMessage()) }
            }
        }
    }

    fun setGithubLogin(v: String) = _state.update { it.copy(githubLogin = v, importResult = null) }
    fun setImportPeriod(p: ImportPeriod) = _state.update { it.copy(importPeriod = p, importResult = null) }
    fun setImportProject(id: String?) = _state.update { it.copy(importProjectId = id, importResult = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Импорт коммитов: сервер ходит в GitHub, группирует коммиты по дням и создаёт по
     * записи на день; дальше их разбирает тот же ИИ. Логин сохраняем в профиль, если
     * поменялся. После импорта — синхронизация, чтобы новые записи появились в ленте.
     */
    fun importGithub() {
        val s = _state.value
        val login = s.githubLogin.trim()
        if (login.isEmpty()) {
            _state.update { it.copy(error = "Укажите логин GitHub") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, error = null, importResult = null) }
            try {
                if (login != s.user?.githubLogin) {
                    val user = profile.setGithubLogin(login)
                    _state.update { it.copy(user = user) }
                }
                val today = LocalDate.now()
                val result = profile.importGithub(
                    periodStart = today.minusDays(s.importPeriod.days - 1).toString(),
                    periodEnd = today.toString(),
                    projectId = s.importProjectId,
                )
                _state.update { it.copy(isImporting = false, importResult = result) }
                syncScheduler.syncSoon()
                // Календарь активности тоже изменился.
                runCatching { profile.activity() }.onSuccess { stats -> _state.update { it.copy(stats = stats) } }
            } catch (e: Exception) {
                _state.update { it.copy(isImporting = false, error = e.humanMessage()) }
            }
        }
    }

    fun setReminder(enabled: Boolean, hour: Int = reminder.value.hour) {
        viewModelScope.launch { reminders.update(enabled, hour) }
    }

    fun logout() = auth.logout()
}
