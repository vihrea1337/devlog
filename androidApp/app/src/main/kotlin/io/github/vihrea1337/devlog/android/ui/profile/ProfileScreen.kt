package io.github.vihrea1337.devlog.android.ui.profile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vihrea1337.devlog.ActivityStatsDto
import io.github.vihrea1337.devlog.android.ui.feed.ProjectDot
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Вкладка «Профиль»: календарь активности и серии дней (мотивация вести дневник),
 * импорт коммитов с GitHub, вечернее напоминание и выход.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val reminder by vm.reminder.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmLogout by remember { mutableStateOf(false) }

    // Каждый раз при возвращении на вкладку — свежий календарь (записи могли добавиться).
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Профиль") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            UserHeader(state)
            ActivityCard(state.stats, state.isLoading)
            GithubImportCard(state, vm)
            ReminderCard(enabled = reminder.enabled, hour = reminder.hour, onChange = vm::setReminder)
            OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Выйти из аккаунта")
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти?") },
            text = { Text("Записи, которые ещё не успели отправиться на сервер, пропадут с телефона.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun UserHeader(state: ProfileUiState) {
    val user = state.user
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Аватар-инициал: фото пользователя у сервиса нет, а пустой кружок выглядит сломанным.
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    (user?.displayName?.ifBlank { user.email } ?: "?").take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(user?.displayName?.ifBlank { null } ?: "…", style = MaterialTheme.typography.titleLarge)
            Text(user?.email.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Карточка активности: серия дней, цифры и календарь последних недель. */
@Composable
private fun ActivityCard(stats: ActivityStatsDto?, isLoading: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Активность", style = MaterialTheme.typography.titleMedium)
            if (stats == null) {
                if (isLoading) CircularProgressIndicator(Modifier.size(24.dp))
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${stats.currentStreak}", "дней подряд", Modifier.weight(1f), highlight = stats.currentStreak > 0)
                StatTile("${stats.longestStreak}", "рекорд серии", Modifier.weight(1f))
                StatTile("${stats.activeDays}", "дней с записями", Modifier.weight(1f))
            }
            Heatmap(stats.days)
            Text(
                "${stats.totalEntries} записей за год",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.headlineSmall)
                if (highlight) {
                    Icon(Icons.Outlined.LocalFireDepartment, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Календарь активности «как на GitHub»: столбец — неделя, клетка — день, цвет — сколько
 * записей. Нарисован на Canvas (одна View вместо сотен), поэтому дёшев при прокрутке.
 * На телефон влезает ~20 недель — показываем последние, а не весь год мелкими точками.
 */
@Composable
private fun Heatmap(days: Map<String, Int>, weeks: Int = 20) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    val accent = MaterialTheme.colorScheme.primary
    val today = remember { LocalDate.now() }
    // Начало сетки — понедельник (weeks-1) недель назад: столбцы выровнены по неделям.
    val start = remember(today) { today.minusWeeks((weeks - 1).toLong()).with(DayOfWeek.MONDAY) }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(16.dp * 7),
    ) {
        val gap = 3.dp.toPx()
        val cell = minOf((size.width - gap * (weeks - 1)) / weeks, (size.height - gap * 6) / 7)
        for (w in 0 until weeks) {
            for (d in 0 until 7) {
                val date = start.plusDays((w * 7 + d).toLong())
                if (date.isAfter(today)) continue
                val count = days[date.toString()] ?: 0
                // 0 записей — серая клетка, дальше — синий всё насыщеннее (1, 2, 3+).
                val color = when (count) {
                    0 -> empty
                    1 -> accent.copy(alpha = 0.35f)
                    2 -> accent.copy(alpha = 0.65f)
                    else -> accent
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * (cell + gap), d * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
    }
}

/** Импорт коммитов с GitHub: логин, период, проект. */
@Composable
private fun GithubImportCard(state: ProfileUiState, vm: ProfileViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Импорт коммитов с GitHub", style = MaterialTheme.typography.titleMedium)
            Text(
                "Коммиты из публичной активности превратятся в записи по дням — ИИ разберёт их как обычные заметки. Повторный импорт дублей не создаёт.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.githubLogin,
                onValueChange = vm::setGithubLogin,
                label = { Text("Логин GitHub") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImportPeriod.entries.forEach { p ->
                    FilterChip(selected = state.importPeriod == p, onClick = { vm.setImportPeriod(p) }, label = { Text(p.label) })
                }
            }
            if (state.projects.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.importProjectId == null, onClick = { vm.setImportProject(null) }, label = { Text("Без проекта") })
                    state.projects.filter { it.aiEnabled }.forEach { p ->
                        FilterChip(
                            selected = state.importProjectId == p.id,
                            onClick = { vm.setImportProject(p.id) },
                            label = { Text(p.name) },
                            leadingIcon = { ProjectDot(p) },
                        )
                    }
                }
            }
            Button(onClick = vm::importGithub, enabled = !state.isImporting, modifier = Modifier.fillMaxWidth()) {
                if (state.isImporting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Забираю коммиты…")
                } else {
                    Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Импортировать")
                }
            }
            state.importResult?.let { r ->
                Text(
                    buildString {
                        append("Коммитов: ${r.commits}, дней: ${r.days}. Новых записей: ${r.created}")
                        if (r.skipped > 0) append(", уже были: ${r.skipped}")
                        append(".")
                        if (r.truncated) append(" GitHub ограничил число запросов — часть коммитов не догрузилась, повторите позже.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Вечернее напоминание. На Android 13+ уведомлениям нужно разрешение — спрашиваем его
 * в момент включения, когда человеку понятно, зачем оно (а не при запуске приложения).
 */
@Composable
private fun ReminderCard(enabled: Boolean, hour: Int, onChange: (Boolean, Int) -> Unit) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onChange(true, hour)
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Напоминание вечером", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Только если за день ещё нет записей",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        val needsPermission = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else onChange(on, hour)
                    },
                )
            }
            if (enabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(18, 19, 20, 21, 22).forEach { h ->
                        FilterChip(selected = hour == h, onClick = { onChange(true, h) }, label = { Text("$h:00") })
                    }
                }
            }
        }
    }
}
