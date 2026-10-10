package io.github.vihrea1337.devlog.android.ui.reports

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vihrea1337.devlog.ReportDto
import io.github.vihrea1337.devlog.android.ui.common.DateField
import io.github.vihrea1337.devlog.android.ui.common.EmptyState
import io.github.vihrea1337.devlog.android.ui.common.humanDate
import io.github.vihrea1337.devlog.android.ui.feed.ProjectDot
import io.github.vihrea1337.devlog.android.ui.theme.LocalStatusColors

/**
 * Вкладка «Отчёты»: собранные отчёты и кнопка «Собрать» (форма периода — в окне снизу).
 * Нажатие на отчёт открывает его просмотр отдельным экраном ([onOpen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(onOpen: (String) -> Unit, vm: ReportsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showForm by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Список перечитывается при каждом возвращении на экран: отчёт могли поправить
    // или удалить на экране просмотра (или в вебе).
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    // Отчёт собран — закрываем форму и открываем его.
    LaunchedEffect(Unit) {
        vm.opened.collect { id ->
            showForm = false
            onOpen(id)
        }
    }
    // Ошибка списка (не формы) — snackbar. Ошибка формы показывается в самой форме.
    LaunchedEffect(state.error, showForm) {
        val error = state.error
        if (error != null && !showForm) {
            snackbar.showSnackbar(error)
            vm.clearError()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Отчёты") }) },
        floatingActionButton = {
            if (!state.readOnly) {
                ExtendedFloatingActionButton(
                    onClick = { showForm = true },
                    icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                    text = { Text("Собрать отчёт") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading && state.reports.isNotEmpty(),
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading && state.reports.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.reports.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Description,
                    title = "Отчётов пока нет",
                    hint = "Нажмите «Собрать отчёт»: выберите период — ИИ напишет по записям текст по темам, а цифры и хронологию посчитает код.",
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.reports, key = { it.id }) { report ->
                        ReportRow(report, state) { onOpen(report.id) }
                    }
                }
            }
        }
    }

    if (showForm) {
        GenerateSheet(state, vm, onDismiss = { if (!state.isGenerating) showForm = false })
    }
}

@Composable
private fun ReportRow(report: ReportDto, state: ReportsUiState, onClick: () -> Unit) {
    val project = state.projectOf(report.projectId)
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(report.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                project?.let {
                    ProjectDot(it)
                    Spacer(Modifier.width(6.dp))
                    Text(it.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  ·  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${humanDate(report.periodStart)} — ${humanDate(report.periodEnd)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (report.shareToken != null) {
                    Icon(Icons.Outlined.Link, contentDescription = "Публичная ссылка открыта", modifier = Modifier.size(16.dp), tint = LocalStatusColors.current.done)
                }
            }
            // Первые строки «Кратко» — чтобы отчёты в списке различались не только датами.
            preview(report.contentMd)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

/** Текст под заголовком «## Кратко» (без разметки) — для превью в списке. */
private fun preview(md: String): String? =
    md.substringAfter("## Кратко", "").lineSequence()
        .map { it.trim() }
        .dropWhile { it.isEmpty() }
        .takeWhile { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString(" ")
        .replace("**", "").replace("`", "")
        .ifBlank { null }

/** Форма «Собрать отчёт»: период (готовый или календарём) и проект. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerateSheet(state: ReportsUiState, vm: ReportsViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Собрать отчёт", style = MaterialTheme.typography.titleLarge)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PeriodPreset.entries.forEach { preset ->
                    FilterChip(selected = state.preset == preset, onClick = { vm.selectPreset(preset) }, label = { Text(preset.label) })
                }
            }
            // Даты выбираются календарём: опечатку вроде «2026-13-01» он исключает по устройству.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("С", state.periodStart, vm::setPeriodStart, Modifier.weight(1f))
                DateField("По", state.periodEnd, vm::setPeriodEnd, Modifier.weight(1f))
            }
            // Отчёт по одному проекту — работодателю ни к чему записи из соседних.
            if (state.projects.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = state.selectedProjectId == null, onClick = { vm.selectProject(null) }, label = { Text("Все проекты") }) }
                    items(state.projects, key = { it.id }) { p ->
                        FilterChip(
                            selected = state.selectedProjectId == p.id,
                            onClick = { vm.selectProject(p.id) },
                            label = { Text(p.name) },
                            leadingIcon = { ProjectDot(p) },
                        )
                    }
                }
            }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            // Во время сборки кнопка не выключается (enabled = false сделал бы её серой и
            // подсказки стали бы нечитаемы) — просто повторное нажатие ничего не делает.
            Button(onClick = { if (!state.isGenerating) vm.generate() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                if (state.isGenerating) {
                    // Сборка с ИИ идёт секунды, иногда десятки секунд. Вместо безликого
                    // «подождите» — что сейчас происходит: так ожидание не кажется зависанием.
                    var step by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (step < GEN_STEPS.lastIndex) { delay(2500); step++ }
                    }
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    AnimatedContent(targetState = GEN_STEPS[step], label = "step") { Text(it) }
                } else {
                    Text("Собрать")
                }
            }
        }
    }
}

/** Что показываем, пока ИИ пишет отчёт. */
private val GEN_STEPS = listOf(
    "Читаю записи за период…",
    "Группирую работу по темам…",
    "Выделяю решения и проблемы…",
    "Считаю цифры периода…",
    "Дописываю текст…",
)
