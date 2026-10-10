package io.github.vihrea1337.devlog.android.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.NewEntry
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.android.data.DevLogApi
import io.github.vihrea1337.devlog.android.data.ProjectsRepository
import io.github.vihrea1337.devlog.android.data.SessionInfo
import io.github.vihrea1337.devlog.android.sync.SyncScheduler
import io.github.vihrea1337.devlog.android.ui.common.humanMessage
import io.github.vihrea1337.devlog.sync.EntryRepository
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Сводка за последние 7 дней: по дням — минуты (или 60 за запись без времени), всего часов и записей. */
data class WeekSummary(
    val days: List<Pair<LocalDate, Int>> = emptyList(),
    val activeDays: Int = 0,
    val minutes: Int = 0,
    val entries: Int = 0,
    /** Сколько дней прошло с последней записи (0 — сегодня); null — записей нет совсем. */
    val daysSinceLast: Int? = null,
) {
    companion object {
        fun from(all: List<EntryDto>, today: LocalDate = LocalDate.now()): WeekSummary {
            val start = today.minusDays(6)
            // Последняя запись не из будущего — на случай сбитых часов на телефоне.
            val last = all.mapNotNull { runCatching { LocalDate.parse(it.occurredOn) }.getOrNull() }
                .filter { !it.isAfter(today) }
                .maxOrNull()
            val daysSinceLast = last?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today).toInt() }
            val week = all.filter { runCatching { LocalDate.parse(it.occurredOn) }.getOrNull()?.let { d -> !d.isBefore(start) && !d.isAfter(today) } == true }
            val byDay = week.groupBy { LocalDate.parse(it.occurredOn) }
            val days = (0..6).map { i ->
                val d = start.plusDays(i.toLong())
                val list = byDay[d].orEmpty()
                d to (list.mapNotNull { it.timeSpentMin }.sum().takeIf { it > 0 } ?: (list.size * 60))
            }
            return WeekSummary(days, byDay.size, week.mapNotNull { it.timeSpentMin }.sum(), week.size, daysSinceLast)
        }
    }
}

/** Что показывает лента. Собирается из нескольких источников в [FeedViewModel.state]. */
data class FeedUiState(
    val entries: List<EntryDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    /** Чем сужена лента и к какому проекту по умолчанию привязываются новые записи; null — все. */
    val selectedProjectId: String? = null,
    val isRefreshing: Boolean = false,
    /** Пока не пришёл первый ответ локальной базы — показываем индикатор, а не «пусто». */
    val isLoaded: Boolean = false,
    val week: WeekSummary = WeekSummary(),
    /** Демо-вход: только просмотр, кнопки изменений скрыты. */
    val readOnly: Boolean = false,
) {
    /** Проект по id — чтобы карточка показала имя, а не UUID. */
    fun projectOf(id: String?): ProjectDto? = id?.let { projectId -> projects.find { it.id == projectId } }
}

/** Разовые события для экрана: показать snackbar. Не состояние — после показа забываются. */
sealed interface FeedEvent {
    data class Message(val text: String) : FeedEvent
    /** Запись скрыта и будет удалена через несколько секунд, если не нажать «Отменить». */
    data class Deleted(val entryId: String) : FeedEvent
}

/**
 * ViewModel ленты. Источник правды — ЛОКАЛЬНАЯ база (офлайн-кэш SQLDelight из shared/):
 * лента рисуется сразу и без сети, а синхронизация с сервером лишь пополняет базу.
 *
 * Все изменения (добавить, поправить, удалить) — офлайн-first: сразу пишутся в локальную
 * базу и в очередь, а на сервер уезжают через WorkManager ([SyncScheduler.syncSoon]),
 * как только появится сеть — даже если приложение к тому времени закрыто.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val entries: EntryRepository,
    private val projectsRepo: ProjectsRepository,
    private val api: DevLogApi,
    private val syncScheduler: SyncScheduler,
    private val session: SessionInfo,
) : ViewModel() {

    private val selectedProjectId = MutableStateFlow<String?>(null)
    private val projects = MutableStateFlow<List<ProjectDto>>(emptyList())
    private val isRefreshing = MutableStateFlow(false)

    /** Записи, удалённые «условно»: скрыты с экрана, но ещё можно нажать «Отменить». */
    private val hiddenIds = MutableStateFlow<Set<String>>(emptySet())
    private val pendingDeletes = mutableMapOf<String, Job>()

    // Channel — очередь разовых событий: snackbar покажется ровно один раз,
    // а не повторно после поворота экрана (как было бы со StateFlow).
    private val _events = Channel<FeedEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Записи из локальной базы с учётом фильтра по проекту. Перерисовываются сами при любом изменении БД. */
    private val localEntries = selectedProjectId
        .flatMapLatest { id -> if (id == null) entries.entries() else entries.entriesByProject(id) }
        .flowOn(Dispatchers.IO)

    /**
     * Итоговое состояние экрана: combine склеивает несколько потоков в один — когда
     * меняется любой из них, пересчитывается всё состояние. stateIn превращает это
     * в StateFlow, который живёт, пока на экран кто-то смотрит (+5 секунд на поворот).
     */
    /** Сводка недели — по ВСЕМ записям, а не только по выбранному проекту. */
    private val week = entries.entries().map { WeekSummary.from(it) }.flowOn(Dispatchers.Default)

    private val base = combine(localEntries, projects, selectedProjectId, isRefreshing, hiddenIds) { list, projectList, selected, refreshing, hidden ->
        FeedUiState(
            entries = list
                .filterNot { it.id in hidden }
                // Свежие дни сверху; внутри дня — свежие записи сверху.
                .sortedWith(compareByDescending<EntryDto> { it.occurredOn }.thenByDescending { it.createdAt }),
            projects = projectList,
            selectedProjectId = selected,
            isRefreshing = refreshing,
            isLoaded = true,
            readOnly = session.readOnly,
        )
    }

    val state: StateFlow<FeedUiState> = combine(base, week) { s, w -> s.copy(week = w) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    init {
        loadProjects()
        refresh()
    }

    private fun loadProjects() {
        viewModelScope.launch {
            // Молча: без проектов лента всё равно работает, ругаться на это незачем.
            runCatching { projectsRepo.projects() }.onSuccess { projects.value = it }
        }
    }

    fun selectProject(projectId: String?) {
        selectedProjectId.value = projectId
    }

    /**
     * Синхронизация «здесь и сейчас» — по жесту pull-to-refresh. В отличие от фоновой,
     * показывает индикатор и сообщает об ошибке. Данные на экране при ошибке не пропадают:
     * они из локальной базы.
     */
    fun refresh() {
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                withContext(Dispatchers.IO) { entries.sync() }
                if (projects.value.isEmpty()) loadProjects()
                // Пока что-то обрабатывается ИИ на сервере — подтянем результат ещё раз.
                if (state.value.entries.any { it.status == "queued" || it.status == "processing" }) pollSoon()
            } catch (e: CancellationException) {
                throw e // экран закрыли посреди обновления — это не ошибка, сообщать не о чем
            } catch (e: Exception) {
                Log.w("DevLogSync", "Обновление ленты не удалось", e)
                _events.send(FeedEvent.Message(e.humanMessage()))
            } finally {
                isRefreshing.value = false
            }
        }
    }

    private var pollJob: Job? = null

    /** Через несколько секунд обновить снова — без жеста, чтобы «обработка…» сменилась результатом. */
    private fun pollSoon() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            delay(5_000)
            refresh()
        }
    }

    /**
     * Добавить запись. Свой UUID: сервер идемпотентен по нему, поэтому повторная
     * отправка после обрыва сети не создаст дубль.
     */
    fun addEntry(occurredOn: String, rawText: String, projectId: String?, timeSpentMin: Int?) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                entries.enqueueCreate(
                    NewEntry(
                        occurredOn = occurredOn,
                        rawText = rawText.trim(),
                        projectId = projectId,
                        timeSpentMin = timeSpentMin,
                        id = UUID.randomUUID().toString(),
                    ),
                )
            }
            syncScheduler.syncSoon()
            pollSoon()
        }
    }

    /**
     * Поправить текст записи. Конфликт с сервером (запись параллельно поменяли с другого
     * устройства) разрешается в EntryRepository по updatedAt — побеждает более свежая правка.
     */
    fun editEntry(id: String, rawText: String) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { entries.enqueueUpdate(id, rawText.trim()) }
            syncScheduler.syncSoon()
            pollSoon()
        }
    }

    /** «Удалить» с возможностью отмены: сначала только прячем, удаляем через 5 секунд. */
    fun delete(id: String) {
        hiddenIds.update { it + id }
        pendingDeletes[id]?.cancel()
        pendingDeletes[id] = viewModelScope.launch {
            _events.send(FeedEvent.Deleted(id))
            delay(UNDO_WINDOW_MS)
            commitDelete(id)
        }
    }

    fun undoDelete(id: String) {
        pendingDeletes.remove(id)?.cancel()
        hiddenIds.update { it - id }
    }

    private fun commitDelete(id: String) {
        pendingDeletes.remove(id)
        entries.enqueueDelete(id)
        hiddenIds.update { it - id }
        syncScheduler.syncSoon()
    }

    /**
     * Заново отправить запись на обработку ИИ. Офлайн-очередь для этого не заводим: это
     * не изменение данных, а команда серверу «обработай ещё раз» — без сети смысла нет.
     */
    fun reprocess(id: String) {
        viewModelScope.launch {
            try {
                val r = api.reprocess(id)
                if (!r.isSuccessful) throw retrofit2.HttpException(r)
                refresh()
            } catch (e: Exception) {
                _events.send(FeedEvent.Message(e.humanMessage()))
            }
        }
    }

    /**
     * Экран закрыли, пока висел snackbar «Отменить», — удаление всё равно должно случиться
     * (пользователь его не отменял). Запись в локальную базу — быстрая и синхронная.
     */
    override fun onCleared() {
        pendingDeletes.keys.toList().forEach { id ->
            entries.enqueueDelete(id)
        }
        if (pendingDeletes.isNotEmpty()) syncScheduler.syncSoon()
        pendingDeletes.clear()
    }

    private companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}
