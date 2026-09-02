package io.github.vihrea1337.devlog.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vihrea1337.devlog.android.data.ApiClient
import io.github.vihrea1337.devlog.android.data.AppDatabase
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.NewEntry
import io.github.vihrea1337.devlog.ProjectDto
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Всё состояние ленты в одном объекте: список записей, идёт ли запрос, текст ошибки.
 */
data class FeedUiState(
    val entries: List<EntryDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    /** Чем сужена лента и к чему привязываются новые записи; null — все проекты. */
    val selectedProjectId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    /** Проект по id — чтобы карточка показала имя, а не UUID. */
    fun projectOf(id: String?): ProjectDto? = id?.let { projectId -> projects.find { it.id == projectId } }
}

/**
 * ViewModel ленты: показывает записи из локального офлайн-кэша (SQLDelight, в shared/)
 * и умеет подтягивать изменения с сервера (refresh() = синхронизация по since-протоколу).
 *
 * Экран больше не ждёт сеть, чтобы что-то показать: лента рисуется из кэша сразу,
 * а refresh() лишь пополняет его. Если сети нет — старые данные остаются на экране,
 * только всплывает error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModel : ViewModel() {

    private val repository = AppDatabase.entryRepository

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** Отдельный поток для фильтра проекта: переключает, какую Flow из кэша слушаем. */
    private val selectedProjectId = MutableStateFlow<String?>(null)

    init {
        // Подписка на локальный кэш — живёт всё время жизни ViewModel и сама
        // перерисовывает ленту при любом изменении БД (в том числе во время sync()).
        viewModelScope.launch {
            selectedProjectId
                .flatMapLatest { projectId ->
                    if (projectId == null) repository.entries() else repository.entriesByProject(projectId)
                }
                .collect { list ->
                    // Свежие дни сверху; внутри дня — свежие записи сверху.
                    val sorted = list.sortedWith(
                        compareByDescending<EntryDto> { it.occurredOn }.thenByDescending { it.createdAt },
                    )
                    _state.update { it.copy(entries = sorted) }
                }
        }
        loadProjects()
        refresh()
    }

    /** Список проектов: нужен и для фильтра, и чтобы подписать карточки. */
    fun loadProjects() {
        viewModelScope.launch {
            runCatching { ApiClient.api.getProjects() }
                .onSuccess { list -> _state.update { it.copy(projects = list) } }
            // Молча: без проектов лента всё равно работает, ругаться на это незачем.
        }
    }

    /** Показать только записи выбранного проекта (null — снова все). Переключает саму подписку. */
    fun selectProject(projectId: String?) {
        selectedProjectId.value = projectId
        _state.update { it.copy(selectedProjectId = projectId) }
    }

    /**
     * Синхронизировать локальный кэш с сервером (since-протокол). Лента при этом уже
     * видна из кэша — refresh() лишь дополняет её. Сетевая ошибка кэш не портит:
     * то, что уже скачано раньше, остаётся на экране.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                repository.sync()
                _state.update { it.copy(isLoading = false) }
                // Пока что-то обрабатывается ИИ — подтянем результат ещё раз через несколько секунд.
                if (_state.value.entries.any { it.status == "queued" || it.status == "processing" }) refreshSoon()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    /** Добавить запись за указанный день; onSuccess очистит поле ввода. */
    fun addEntry(occurredOn: String, rawText: String, onSuccess: () -> Unit) {
        if (rawText.isBlank()) {
            _state.update { it.copy(error = "Напишите, что сделали") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                ApiClient.api.addEntry(
                    NewEntry(
                        occurredOn = occurredOn,
                        rawText = rawText.trim(),
                        projectId = _state.value.selectedProjectId,
                        // Свой id: если сеть подведёт и отправка повторится, дубля не будет.
                        id = UUID.randomUUID().toString(),
                    ),
                )
                onSuccess()
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    /** Удалить запись по id и обновить ленту. */
    fun deleteEntry(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val r = ApiClient.api.deleteEntry(id)
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code()}")
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    /** Заново отправить запись на обработку ИИ. */
    fun reprocess(id: String) {
        viewModelScope.launch {
            try {
                ApiClient.api.reprocess(id)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Ошибка сети") }
            }
        }
    }

    /** Повторно обновить через несколько секунд — подтянуть результат фоновой обработки ИИ. */
    private fun refreshSoon() {
        viewModelScope.launch {
            delay(4000)
            refresh()
        }
    }
}
