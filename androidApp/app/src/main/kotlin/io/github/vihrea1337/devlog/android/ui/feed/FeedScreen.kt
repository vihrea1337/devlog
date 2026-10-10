package io.github.vihrea1337.devlog.android.ui.feed

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.github.vihrea1337.devlog.android.ui.common.plural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.android.ui.common.EmptyState
import io.github.vihrea1337.devlog.android.ui.common.dayHeader
import io.github.vihrea1337.devlog.android.ui.common.humanDuration
import io.github.vihrea1337.devlog.android.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/**
 * Вкладка «Лента»: записи по дням со статусом обработки ИИ и разобранной структурой.
 * Новая запись — кнопкой «+» (окно снизу), обновление — жестом «потянуть вниз».
 *
 * [openNewEntry] — экран открыт из уведомления-напоминания: сразу показываем окно записи.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    openNewEntry: Boolean = false,
    onNewEntryShown: () -> Unit = {},
    vm: FeedViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Тактильный отклик: короткая вибрация подтверждает сохранение и удаление.
    val haptic = LocalHapticFeedback.current

    // Что открыто в окне снизу: null — ничего, иначе новая запись или правка существующей.
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    LaunchedEffect(openNewEntry) {
        if (openNewEntry) {
            editor = EditorTarget.New
            onNewEntryShown()
        }
    }

    // Разовые события из ViewModel → snackbar. LaunchedEffect(Unit) — подписка живёт,
    // пока экран на виду.
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is FeedEvent.Message -> snackbar.showSnackbar(event.text)
                is FeedEvent.Deleted -> scope.launch {
                    val result = snackbar.showSnackbar("Запись удалена", actionLabel = "Отменить", duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) vm.undoDelete(event.entryId)
                }
            }
        }
    }

    // Шапка сжимается при прокрутке — больше места под ленту.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Лента") },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (!state.readOnly) {
                ExtendedFloatingActionButton(
                    onClick = { editor = EditorTarget.New },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("Запись") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // Нижние отступы под системную панель даёт внешний Scaffold (с нижней навигацией).
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !state.isLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> FeedList(
                    state = state,
                    onSelectProject = vm::selectProject,
                    onEdit = { editor = EditorTarget.Edit(it) },
                    onDelete = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.delete(it.id)
                    },
                    onReprocess = { vm.reprocess(it.id) },
                )
            }
        }
    }

    editor?.let { target ->
        EntryEditorSheet(
            target = target,
            projects = state.projects,
            defaultProjectId = state.selectedProjectId,
            onDismiss = { editor = null },
            onSave = { date, text, projectId, minutes ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                when (target) {
                    EditorTarget.New -> vm.addEntry(date, text, projectId, minutes)
                    is EditorTarget.Edit -> vm.editEntry(target.entry.id, text)
                }
                editor = null
            },
        )
    }
}

@Composable
private fun FeedList(
    state: FeedUiState,
    onSelectProject: (String?) -> Unit,
    onEdit: (EntryDto) -> Unit,
    onDelete: (EntryDto) -> Unit,
    onReprocess: (EntryDto) -> Unit,
) {
    // Группировка по дням: заголовок дня + карточки. groupBy сохраняет порядок (свежие сверху).
    val days = remember(state.entries) { state.entries.groupBy { it.occurredOn } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Снизу место под плавающую кнопку, чтобы она не закрывала последнюю карточку.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.readOnly) {
            item(key = "ro") { ReadOnlyBanner() }
        }
        item(key = "week") { WeekCard(state.week) }
        if (state.projects.isNotEmpty()) {
            item(key = "filters") {
                ProjectFilter(state.projects, state.selectedProjectId, onSelectProject)
            }
        }
        if (state.entries.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Notes,
                    title = "Пока пусто",
                    hint = "Нажмите «Запись» и напишите пару строк о том, что сделали сегодня. ИИ разложит их на шаги, решения и итог.",
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
        }
        days.forEach { (day, dayEntries) ->
            item(key = "day-$day") { DayHeader(day, dayEntries) }
            // key — стабильный id записи: Compose понимает, какая карточка какая, и не
            // перерисовывает весь список при изменении одной (и анимирует корректно).
            items(dayEntries, key = { it.id }) { entry ->
                EntryCard(
                    entry = entry,
                    readOnly = state.readOnly,
                    project = state.projectOf(entry.projectId),
                    onEdit = { onEdit(entry) },
                    onDelete = { onDelete(entry) },
                    onReprocess = { onReprocess(entry) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun ProjectFilter(projects: List<ProjectDto>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Все") }) }
        items(projects, key = { it.id }) { project ->
            FilterChip(
                selected = selected == project.id,
                onClick = { onSelect(if (selected == project.id) null else project.id) },
                label = { Text(project.name) },
                leadingIcon = { ProjectDot(project) },
            )
        }
    }
}

/** Заголовок дня: «Вчера» + сколько времени учтено за день. */
@Composable
private fun DayHeader(day: String, entries: List<EntryDto>) {
    val minutes = entries.mapNotNull { it.timeSpentMin }.sum()
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(dayHeader(day), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (minutes > 0) {
            Text(humanDuration(minutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Цветная точка проекта (цвет задаётся на сервере, как на вебе). */
@Composable
fun ProjectDot(project: ProjectDto, size: Int = 8) {
    val color = project.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: MaterialTheme.colorScheme.primary
    Surface(shape = CircleShape, color = color, modifier = Modifier.size(size.dp)) {}
}

/**
 * Карточка записи. Если ИИ уже разобрал запись — сверху его краткая суть, ниже шаги,
 * решения и проблемы (свёрнуты, раскрываются нажатием). Исходный текст автора всегда
 * доступен: разбор — лишь пересказ.
 */
@Composable
private fun EntryCard(
    entry: EntryDto,
    readOnly: Boolean,
    project: ProjectDto?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReprocess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // Не влезает ли сырой текст в 6 строк — тогда нужна кнопка «Показать полностью».
    var canExpandRaw by remember(entry.id) { mutableStateOf(false) }
    val s = entry.structured

    // Запись только что разобрана ИИ (была «в очереди» — стала «готово»): коротко
    // подсвечиваем рамку, чтобы глаз заметил, где появился результат.
    var lastStatus by remember(entry.id) { mutableStateOf(entry.status) }
    val glow = remember(entry.id) { Animatable(0f) }
    LaunchedEffect(entry.status) {
        if ((lastStatus == "queued" || lastStatus == "processing") && entry.status == "structured") {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(durationMillis = 2200))
        }
        lastStatus = entry.status
    }
    val glowColor = LocalStatusColors.current.done

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, glowColor.copy(alpha = glow.value), CardDefaults.elevatedShape),
    ) {
        Column(
            Modifier
                .padding(14.dp)
                .animateContentSize(), // плавное раскрытие деталей
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                project?.let {
                    ProjectDot(it)
                    Spacer(Modifier.width(6.dp))
                    Text(it.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!it.aiEnabled) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.Lock, contentDescription = "ИИ выключен", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                entry.timeSpentMin?.let {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(2.dp))
                    Text(humanDuration(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                StatusBadge(entry.status)
                if (!readOnly) Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Действия")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Править") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menuOpen = false; onEdit() })
                        if (project?.aiEnabled != false) {
                            DropdownMenuItem(text = { Text("Разобрать заново") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { menuOpen = false; onReprocess() })
                        }
                        DropdownMenuItem(text = { Text("Удалить") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }

            Spacer(Modifier.size(6.dp))
            if (s != null && s.summary.isNotBlank()) {
                Text(s.summary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(4.dp))
                // Исходный текст — свёрнут до трёх строк, полностью — в раскрытой карточке.
                Text(
                    entry.rawText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                // Запись ещё не разобрана (или проект без ИИ) — показываем сам текст,
                // но длинный не растягиваем на весь экран.
                Text(
                    entry.rawText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (it.hasVisualOverflow) canExpandRaw = true },
                )
                if (canExpandRaw) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Свернуть" else "Показать полностью") }
                }
            }

            // Пока ИИ разбирает запись — мерцающая заглушка на месте будущей сути.
            if (s == null && (entry.status == "queued" || entry.status == "processing") && project?.aiEnabled != false) {
                AiPendingShimmer()
            }

            // У записи в очереди это не ошибка, а пояснение («ждём лимит ИИ-сервиса») — серым.
            entry.aiError?.let { reason ->
                Text(
                    reason,
                    color = if (entry.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (s != null && expanded) {
                Section("Сделано", s.steps)
                Section("Решения", s.decisions)
                Section("Проблемы", s.problems)
                if (s.outcome.isNotBlank()) {
                    Text("Итог", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                    Text(s.outcome, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (s != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.tags.take(if (expanded) 10 else 3).forEach { tag ->
                            Text("#$tag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Свернуть" else "Подробнее")
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
    // Точка отдельно от текста: перенесённая строка выравнивается по тексту, а не под точку.
    items.forEach {
        Row(Modifier.padding(top = 2.dp)) {
            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(14.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** Цветной ярлык статуса обработки ИИ. */
@Composable
private fun StatusBadge(status: String) {
    val colors = LocalStatusColors.current
    val (label, color) = when (status) {
        "queued" -> "в очереди" to colors.pending
        "processing" -> "разбор…" to colors.pending
        "structured" -> "готово" to colors.done
        "failed" -> "ошибка ИИ" to colors.failed
        else -> "без ИИ" to colors.muted
    }
    Surface(color = color.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Плашка демо-режима: всё можно смотреть, ничего нельзя менять. */
@Composable
private fun ReadOnlyBanner() {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Visibility, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                "Демо-режим: это пример дневника разработчика — смотреть можно всё, менять нельзя.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * Сводка за последние 7 дней: дни с записями, учтённые часы, число записей и столбики
 * по дням (высота — часы; если время не указывали — число записей).
 */
@Composable
private fun WeekCard(week: WeekSummary) {
    val maxValue = (week.days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Последние 7 дней", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeekStat(week.activeDays.toString(), plural(week.activeDays, "день", "дня", "дней") + " с записями", Modifier.weight(1f))
                WeekStat(if (week.minutes > 0) "${week.minutes / 60} ч" else "0", "учтено", Modifier.weight(1f))
                WeekStat(week.entries.toString(), plural(week.entries, "запись", "записи", "записей"), Modifier.weight(1f))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                week.days.forEach { (day, value) ->
                    // Столбики «вырастают» при появлении — экран выглядит живым.
                    val target = if (value == 0) 0.05f else 0.15f + 0.85f * value / maxValue
                    val fraction by animateFloatAsState(target, tween(600), label = "bar")
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.7f)
                                .height(56.dp * fraction)
                                .background(
                                    if (value == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp),
                                ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.forLanguageTag("ru")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekStat(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** Мерцающие полоски «ИИ разбирает запись…» — как заглушки при загрузке в соцсетях. */
@Composable
private fun AiPendingShimmer() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shift",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val light = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val brush = Brush.linearGradient(
        colors = listOf(base, light, base),
        start = Offset(-400f + 1200f * shift, 0f),
        end = Offset(1200f * shift, 0f),
    )
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ИИ разбирает запись…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(0.9f, 0.75f, 0.5f).forEach { w ->
            Box(
                Modifier
                    .fillMaxWidth(w)
                    .height(10.dp)
                    .background(brush, RoundedCornerShape(5.dp)),
            )
        }
    }
}
