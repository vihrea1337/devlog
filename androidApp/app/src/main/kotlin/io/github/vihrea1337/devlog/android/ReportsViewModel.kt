package io.github.vihrea1337.devlog.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vihrea1337.devlog.ErrorResponse
import io.github.vihrea1337.devlog.NewReport
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.ReportDto
import io.github.vihrea1337.devlog.UpdateReport
import io.github.vihrea1337.devlog.android.data.ApiClient
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Готовые варианты периода — чтобы не набирать даты руками на телефоне. */
enum class PeriodPreset(val label: String) {
    THIS_MONTH("Этот месяц"),
    LAST_MONTH("Прошлый месяц"),
    LAST_7("7 дней"),
    LAST_30("30 дней"),
    CUSTOM("Свой период"),
    ;

    /** Пара «начало — конец» для этого варианта. Для CUSTOM даты вводит человек. */
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
 * Состояние экрана отчётов.
 *
 * Два разных «идёт загрузка» специально: список отчётов приезжает мгновенно,
 * а сборка нового отчёта с ИИ занимает секунды — на неё нужен отдельный индикатор
 * и заблокированная кнопка, иначе человек нажмёт её десять раз.
 */
data class ReportsUiState(
    val reports: List<ReportDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    /** По какому проекту собирать отчёт; null — по всем. */
    val selectedProjectId: String? = null,
    val preset: PeriodPreset = PeriodPreset.THIS_MONTH,
    val periodStart: String = PeriodPreset.THIS_MONTH.range().first.toString(),
    val periodEnd: String = PeriodPreset.THIS_MONTH.range().second.toString(),
    val isLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val error: String? = null,
    /** Открытый отчёт: пока он не null — на экране просмотр, а не список. */
    val opened: ReportDto? = null,
    /** Полный адрес публичной ссылки открытого отчёта; null — ссылка не выдана. */
    val shareUrl: String? = null,
    /** Открытый отчёт правится: вместо готового документа показываем поля ввода. */
    val isEditing: Boolean = false,
    /** Черновик правки. Живёт в ViewModel, поэтому переживает поворот экрана. */
    val draftTitle: String = "",
    val draftMd: String = "",
    val isSaving: Boolean = false,
) {
    fun projectOf(id: String?): ProjectDto? = id?.let { projectId -> projects.find { it.id == projectId } }
}

/**
 * ViewModel экрана отчётов: список собранных отчётов, сборка нового за период
 * и публичная ссылка для работодателя.
 */
class ReportsViewModel : ViewModel() {

    private val _state = MutableStateFlow(ReportsUiState())
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    init {
        loadProjects()
        refresh()
    }

    private fun loadProjects() {
        viewModelScope.launch {
            runCatching { ApiClient.api.getProjects() }
                .onSuccess { list -> _state.update { it.copy(projects = list) } }
            // Молча: без проектов отчёты собираются по всем записям, ругаться незачем.
        }
    }

    /** Перечитать список отчётов с сервера. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val list = ApiClient.api.getReports()
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

    /** Ручной ввод дат (вариант «Свой период»). */
    fun setPeriodStart(value: String) = _state.update { it.copy(periodStart = value, preset = PeriodPreset.CUSTOM) }

    fun setPeriodEnd(value: String) = _state.update { it.copy(periodEnd = value, preset = PeriodPreset.CUSTOM) }

    fun selectProject(projectId: String?) = _state.update { it.copy(selectedProjectId = projectId) }

    /** Собрать отчёт за выбранный период и сразу открыть его. */
    fun generate() {
        val s = _state.value
        val from = parseDate(s.periodStart)
        val to = parseDate(s.periodEnd)
        if (from == null || to == null) {
            _state.update { it.copy(error = "Дата должна быть в формате ГГГГ-ММ-ДД") }
            return
        }
        if (to.isBefore(from)) {
            _state.update { it.copy(error = "Конец периода раньше начала") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isGenerating = true, error = null) }
            try {
                val report = ApiClient.api.createReport(
                    NewReport(
                        periodStart = from.toString(),
                        periodEnd = to.toString(),
                        projectId = s.selectedProjectId,
                    ),
                )
                _state.update {
                    it.copy(
                        isGenerating = false,
                        reports = listOf(report) + it.reports,
                        opened = report,
                        shareUrl = report.shareToken?.let { token -> ApiClient.absoluteUrl("/r/$token") },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isGenerating = false, error = e.humanMessage()) }
            }
        }
    }

    /** Открыть отчёт на просмотр. */
    fun open(report: ReportDto) {
        _state.update {
            it.copy(
                opened = report,
                shareUrl = report.shareToken?.let { token -> ApiClient.absoluteUrl("/r/$token") },
                error = null,
            )
        }
    }

    /** Вернуться из просмотра к списку (незаконченная правка при этом отбрасывается). */
    fun closeOpened() = _state.update {
        it.copy(opened = null, shareUrl = null, isEditing = false, draftTitle = "", draftMd = "")
    }

    // --- Правка отчёта перед отправкой работодателю ---

    /**
     * Включить режим правки: черновик заполняем текущим текстом отчёта.
     * Правим Markdown, а не HTML: HTML сервер соберёт заново сам — так же,
     * как для публичной страницы.
     */
    fun startEdit() = _state.update { s ->
        val report = s.opened ?: return@update s
        s.copy(isEditing = true, draftTitle = report.title, draftMd = report.contentMd, error = null)
    }

    fun setDraftTitle(value: String) = _state.update { it.copy(draftTitle = value) }

    fun setDraftMd(value: String) = _state.update { it.copy(draftMd = value) }

    /** Выйти из правки, ничего не сохраняя. */
    fun cancelEdit() = _state.update {
        it.copy(isEditing = false, draftTitle = "", draftMd = "", error = null)
    }

    /** Сохранить правку на сервере; в ответ приходит отчёт с уже пересобранным HTML. */
    fun saveEdit() {
        val s = _state.value
        val report = s.opened ?: return
        val text = s.draftMd.trim()
        val title = s.draftTitle.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(error = "Текст отчёта пуст") }
            return
        }
        if (title.isEmpty()) {
            _state.update { it.copy(error = "Заголовок пуст") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                val updated = ApiClient.api.updateReport(report.id, UpdateReport(contentMd = text, title = title))
                _state.update { state ->
                    state.copy(
                        isSaving = false,
                        isEditing = false,
                        draftTitle = "",
                        draftMd = "",
                        opened = updated,
                        reports = state.reports.map { if (it.id == updated.id) updated else it },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.humanMessage()) }
            }
        }
    }

    /**
     * Включить публичную ссылку. Сервер отдаёт относительный путь («/r/abc»),
     * адрес сервера знает только приложение — поэтому склеиваем здесь.
     */
    fun share(onReady: (String) -> Unit) {
        val report = _state.value.opened ?: return
        viewModelScope.launch {
            try {
                val response = ApiClient.api.shareReport(report.id)
                val url = ApiClient.absoluteUrl(response.url)
                val updated = report.copy(shareToken = response.shareToken)
                _state.update { s ->
                    s.copy(
                        opened = updated,
                        shareUrl = url,
                        reports = s.reports.map { if (it.id == updated.id) updated else it },
                    )
                }
                onReady(url)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.humanMessage()) }
            }
        }
    }

    /** Отозвать публичную ссылку: пересланная кому попало ссылка перестаёт открываться. */
    fun unshare() {
        val report = _state.value.opened ?: return
        viewModelScope.launch {
            try {
                val r = ApiClient.api.unshareReport(report.id)
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code()}")
                val updated = report.copy(shareToken = null)
                _state.update { s ->
                    s.copy(
                        opened = updated,
                        shareUrl = null,
                        reports = s.reports.map { if (it.id == updated.id) updated else it },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.humanMessage()) }
            }
        }
    }

    /** Удалить отчёт (из просмотра возвращаемся к списку). */
    fun delete(id: String) {
        viewModelScope.launch {
            try {
                val r = ApiClient.api.deleteReport(id)
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code()}")
                _state.update { s ->
                    s.copy(
                        reports = s.reports.filterNot { it.id == id },
                        opened = if (s.opened?.id == id) null else s.opened,
                        shareUrl = if (s.opened?.id == id) null else s.shareUrl,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.humanMessage()) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value.trim()) }.getOrNull()
}

/**
 * Сообщение об ошибке человеческим языком. Retrofit на ответ 4xx/5xx бросает
 * HttpException с текстом вида «HTTP 400 Bad Request» — сам по себе он ничего
 * не объясняет. Но сервер кладёт причину в тело ответа (`{"error": "…"}`),
 * поэтому сначала пробуем достать её оттуда.
 */
private fun Exception.humanMessage(): String = when (this) {
    is java.net.SocketTimeoutException -> "Сервер долго не отвечает — попробуйте ещё раз"
    is java.io.IOException -> "Нет связи с сервером"
    is retrofit2.HttpException -> serverError() ?: message ?: "Ошибка сервера"
    else -> message ?: "Неизвестная ошибка"
}

/**
 * Текст ошибки из тела ответа. `errorBody()` читается один раз и может быть чем
 * угодно (например, HTML от прокси), поэтому всё в runCatching: не смогли
 * разобрать — вернём null, и наверху останется код HTTP.
 */
private fun retrofit2.HttpException.serverError(): String? = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    errorJson.decodeFromString(ErrorResponse.serializer(), body).error.ifBlank { null }
}.getOrNull()

/** Один разборщик на всё приложение: создавать Json на каждую ошибку дорого. */
private val errorJson = Json { ignoreUnknownKeys = true }
