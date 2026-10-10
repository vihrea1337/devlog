package io.github.vihrea1337.devlog.android.ui.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.ReportDto
import io.github.vihrea1337.devlog.android.data.DevLogApi
import io.github.vihrea1337.devlog.android.data.ProjectsRepository
import io.github.vihrea1337.devlog.android.data.SessionInfo
import io.github.vihrea1337.devlog.android.data.ReportsRepository
import io.github.vihrea1337.devlog.android.ui.common.humanMessage
import io.github.vihrea1337.devlog.android.ui.navigation.ReportRoute
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Готовые варианты периода — чтобы не набирать даты руками на телефоне. */
enum class PeriodPreset(val label: String) {
    THIS_MONTH("Этот месяц"),
    LAST_MONTH("Прошлый месяц"),
    LAST_7("7 дней"),
    LAST_30("30 дней"),
    CUSTOM("Свой период"),
    ;

    /** Пара «начало — конец» для этого варианта. Для CUSTOM даты выбирает человек. */
    fun range(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> = when (this) {
        THIS_MONTH -> today.withDayOfMonth(1) to today
        LAST_MONTH -> {
            val start = today.minusMonths(1).withDayOfMonth(1)
            start to start.withDayOfMonth(start.lengthOfMonth())
        }
        LAST_7 -> today.minusDays(6) to today
        LAST_30 -> today.minusDays(29) to today
        CUSTOM -> today.withDayOfMonth(1) to today
    }
}

/**
 * Состояние списка отчётов. Два разных «идёт загрузка» специально: список приезжает
 * мгновенно, а сборка нового отчёта с ИИ занимает секунды — на неё нужен отдельный
 * индикатор и заблокированная кнопка, иначе человек нажмёт её десять раз.
 */
data class ReportsUiState(
    val reports: List<ReportDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    val isLoading: Boolean = true,
    val isGenerating: Boolean = false,
    // Форма «собрать отчёт»:
    val preset: PeriodPreset = PeriodPreset.THIS_MONTH,
    val periodStart: String = PeriodPreset.THIS_MONTH.range().first.toString(),
    val periodEnd: String = PeriodPreset.THIS_MONTH.range().second.toString(),
    val selectedProjectId: String? = null,
    val error: String? = null,
    /** Демо-вход: собирать и менять отчёты нельзя, только смотреть. */
    val readOnly: Boolean = false,
) {
    fun projectOf(id: String?): ProjectDto? = id?.let { projectId -> projects.find { it.id == projectId } }
}

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reportsRepo: ReportsRepository,
    private val projectsRepo: ProjectsRepository,
    session: SessionInfo,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportsUiState(readOnly = session.readOnly))
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    /** Отчёт собран — экран откроет его просмотр (разовое событие, не состояние). */
    private val _opened = Channel<String>(Channel.BUFFERED)
    val opened = _opened.receiveAsFlow()

    init {
        viewModelScope.launch {
            runCatching { projectsRepo.projects() }.onSuccess { list -> _state.update { it.copy(projects = list) } }
        }
        // Сам список грузит экран при каждом показе (refresh() в LifecycleResumeEffect).
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val list = reportsRepo.reports()
                _state.update { it.copy(reports = list, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.humanMessage()) }
            }
        }
    }

    /** Выбрать готовый период; даты подставятся сами. */
    fun selectPreset(preset: PeriodPreset) {
        if (preset == PeriodPreset.CUSTOM) {
            _state.update { it.copy(preset = preset) } // даты оставляем те, что были
            return
        }
        val (from, to) = preset.range()
        _state.update { it.copy(preset = preset, periodStart = from.toString(), periodEnd = to.toString()) }
    }

    fun setPeriodStart(value: String) = _state.update { it.copy(periodStart = value, preset = PeriodPreset.CUSTOM) }
    fun setPeriodEnd(value: String) = _state.update { it.copy(periodEnd = value, preset = PeriodPreset.CUSTOM) }
    fun selectProject(projectId: String?) = _state.update { it.copy(selectedProjectId = projectId) }
    fun clearError() = _state.update { it.copy(error = null) }

    /** Собрать отчёт за выбранный период и сразу открыть его. */
    fun generate() {
        val s = _state.value
        val from = runCatching { LocalDate.parse(s.periodStart) }.getOrNull()
        val to = runCatching { LocalDate.parse(s.periodEnd) }.getOrNull()
        if (from == null || to == null) {
            _state.update { it.copy(error = "Выберите даты периода") }
            return
        }
        if (to.isBefore(from)) {
            _state.update { it.copy(error = "Конец периода раньше начала") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isGenerating = true, error = null) }
            try {
                val report = reportsRepo.create(from.toString(), to.toString(), s.selectedProjectId)
                _state.update { it.copy(isGenerating = false, reports = listOf(report) + it.reports) }
                _opened.send(report.id)
            } catch (e: Exception) {
                _state.update { it.copy(isGenerating = false, error = e.humanMessage()) }
            }
        }
    }
}

// --- Просмотр одного отчёта ---

data class ReportDetailUiState(
    val report: ReportDto? = null,
    val isLoading: Boolean = true,
    /** Полный адрес публичной ссылки; null — ссылка не выдана. */
    val shareUrl: String? = null,
    val isEditing: Boolean = false,
    /** Черновик правки. Живёт в ViewModel, поэтому переживает поворот экрана. */
    val draftTitle: String = "",
    val draftMd: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    /** Отчёт удалён — экран вернётся к списку. */
    val deleted: Boolean = false,
    val readOnly: Boolean = false,
)

/**
 * ViewModel экрана отчёта. id отчёта берётся из аргументов навигации через
 * SavedStateHandle — так экран сам знает, что показывать, и переживает даже
 * пересоздание процесса системой.
 */
@HiltViewModel
class ReportDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val reportsRepo: ReportsRepository,
    session: SessionInfo,
) : ViewModel() {

    private val reportId: String = savedStateHandle.toRoute<ReportRoute>().id

    private val _state = MutableStateFlow(ReportDetailUiState(readOnly = session.readOnly))
    val state: StateFlow<ReportDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val report = reportsRepo.report(reportId)
                _state.update {
                    it.copy(
                        report = report,
                        isLoading = false,
                        shareUrl = report.shareToken?.let { token -> DevLogApi.absoluteUrl("/r/$token") },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.humanMessage()) }
            }
        }
    }

    /**
     * Включить режим правки: черновик заполняем текущим текстом отчёта. Правим Markdown,
     * а не HTML: HTML сервер соберёт заново сам — так же, как для публичной страницы.
     */
    fun startEdit() = _state.update { s ->
        val report = s.report ?: return@update s
        s.copy(isEditing = true, draftTitle = report.title, draftMd = report.contentMd, error = null)
    }

    fun setDraftTitle(value: String) = _state.update { it.copy(draftTitle = value) }
    fun setDraftMd(value: String) = _state.update { it.copy(draftMd = value) }
    fun cancelEdit() = _state.update { it.copy(isEditing = false, draftTitle = "", draftMd = "", error = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun saveEdit() {
        val s = _state.value
        val title = s.draftTitle.trim()
        val text = s.draftMd.trim()
        if (title.isEmpty() || text.isEmpty()) {
            _state.update { it.copy(error = if (title.isEmpty()) "Заголовок пуст" else "Текст отчёта пуст") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                val updated = reportsRepo.update(reportId, title, text)
                _state.update { it.copy(report = updated, isSaving = false, isEditing = false, draftTitle = "", draftMd = "") }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.humanMessage()) }
            }
        }
    }

    /**
     * Получить публичную ссылку (выдать, если её ещё нет) и отдать её [onReady] —
     * экран откроет системное «Поделиться».
     */
    fun share(onReady: (String) -> Unit) {
        _state.value.shareUrl?.let { return onReady(it) }
        viewModelScope.launch {
            try {
                val (token, url) = reportsRepo.share(reportId)
                _state.update { it.copy(shareUrl = url, report = it.report?.copy(shareToken = token)) }
                onReady(url)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.humanMessage()) }
            }
        }
    }

    /** Отозвать публичную ссылку: пересланная кому попало ссылка перестаёт открываться. */
    fun unshare() {
        viewModelScope.launch {
            try {
                reportsRepo.unshare(reportId)
                _state.update { it.copy(shareUrl = null, report = it.report?.copy(shareToken = null)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.humanMessage()) }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                reportsRepo.delete(reportId)
                _state.update { it.copy(deleted = true) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.humanMessage()) }
            }
        }
    }
}
