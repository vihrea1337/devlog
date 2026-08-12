package io.github.vihrea1337.devlog.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vihrea1337.devlog.android.data.ApiClient
import io.github.vihrea1337.devlog.android.data.EntryDto
import io.github.vihrea1337.devlog.android.data.NewEntry
import io.github.vihrea1337.devlog.android.data.ProjectDto
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * ViewModel ленты: хранит состояние и ходит в сеть, переживает поворот экрана.
 * Экран (Compose) только рисует состояние и зовёт эти функции по нажатиям.
 */
class FeedViewModel : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    init {
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

    /** Показать только записи выбранного проекта (null — снова все). */
    fun selectProject(projectId: String?) {
        _state.update { it.copy(selectedProjectId = projectId) }
        refresh()
    }

    /** Перезагрузить ленту с сервера. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val list = ApiClient.api.getEntries(_state.value.selectedProjectId)
                // Свежие дни сверху; внутри дня — свежие записи сверху.
                val sorted = list.sortedWith(
                    compareByDescending<EntryDto> { it.occurredOn }.thenByDescending { it.createdAt },
                )
                _state.update { it.copy(entries = sorted, isLoading = false) }
                // Пока что-то обрабатывается ИИ — обновимся сами через несколько секунд.
                if (sorted.any { it.status == "queued" || it.status == "processing" }) refreshSoon()
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
