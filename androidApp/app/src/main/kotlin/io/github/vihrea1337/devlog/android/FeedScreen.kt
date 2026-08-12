package io.github.vihrea1337.devlog.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vihrea1337.devlog.android.data.EntryDto
import io.github.vihrea1337.devlog.android.data.ProjectDto
import java.time.LocalDate

/**
 * Главный экран: форма добавления записи + лента по дням со статусом обработки ИИ
 * и разобранной структурой (если ИИ уже отработал).
 */
@Composable
fun FeedScreen(onLogout: () -> Unit, vm: FeedViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var rawText by remember { mutableStateOf("") }
    var occurredOn by remember { mutableStateOf(LocalDate.now().toString()) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
    ) {
        // --- Шапка ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DevLog", style = MaterialTheme.typography.headlineSmall)
                TokenStore.userName?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onLogout) { Text("Выйти") }
        }
        Spacer(Modifier.height(12.dp))

        // --- Форма новой записи ---
        OutlinedTextField(
            value = occurredOn,
            onValueChange = { occurredOn = it },
            label = { Text("Дата (ГГГГ-ММ-ДД)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = rawText,
            onValueChange = { rawText = it },
            label = { Text("Что сделал сегодня?") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.addEntry(occurredOn, rawText) { rawText = "" } },
            enabled = !state.isLoading && rawText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Добавить запись")
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        // --- Контролы ленты ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Лента", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.refresh() }) { Text("Обновить") }
        }

        // Фильтр по проектам. Выбранный проект заодно подставляется новым записям —
        // так же, как на вебе.
        if (state.projects.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.selectedProjectId == null,
                    onClick = { vm.selectProject(null) },
                    label = { Text("Все") },
                )
                state.projects.forEach { project ->
                    FilterChip(
                        selected = state.selectedProjectId == project.id,
                        onClick = {
                            vm.selectProject(if (state.selectedProjectId == project.id) null else project.id)
                        },
                        label = { Text(project.name) },
                    )
                }
            }
        }

        // --- Лента по дням ---
        if (state.entries.isEmpty()) {
            Text(
                "Пока пусто. Добавьте первую запись выше.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.entries) { index, e ->
                    val showDay = index == 0 || state.entries[index - 1].occurredOn != e.occurredOn
                    if (showDay) {
                        Text(
                            e.occurredOn,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    EntryCard(
                        entry = e,
                        project = state.projectOf(e.projectId),
                        onDelete = { vm.deleteEntry(e.id) },
                        onReprocess = { vm.reprocess(e.id) },
                    )
                }
            }
        }
    }
}

/** Одна карточка записи: текст, статус обработки ИИ и (если готова) структура. */
@Composable
private fun EntryCard(
    entry: EntryDto,
    project: ProjectDto?,
    onDelete: () -> Unit,
    onReprocess: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(entry.rawText, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(0.dp))
                StatusBadge(entry.status)
            }
            // Причина неудачи ИИ: «ошибка ИИ» без объяснения ничем не помогает.
            entry.aiError?.let { reason ->
                Text(
                    reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            project?.let {
                Text(
                    if (it.aiEnabled) it.name else "${it.name} · ИИ выкл.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF6B7382),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            entry.structured?.let { s ->
                Spacer(Modifier.height(8.dp))
                if (s.summary.isNotBlank()) {
                    Text(s.summary, style = MaterialTheme.typography.titleSmall)
                }
                s.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (s.outcome.isNotBlank()) {
                    Text("Итог: ${s.outcome}", style = MaterialTheme.typography.bodySmall)
                }
                if (s.tags.isNotEmpty()) {
                    Text(
                        s.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF3B6EF5),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = onReprocess) { Text("Переобработать") }
                TextButton(onClick = onDelete) { Text("Удалить") }
            }
        }
    }
}

/** Цветной ярлык статуса обработки ИИ. */
@Composable
private fun StatusBadge(status: String) {
    val (label, color) = when (status) {
        "queued" -> "в очереди" to Color(0xFFB7791F)
        "processing" -> "обработка…" to Color(0xFFB7791F)
        "structured" -> "готово" to Color(0xFF1F9D55)
        "failed" -> "ошибка ИИ" to Color(0xFFD64545)
        else -> "черновик" to Color(0xFF6B7382)
    }
    Text(label, color = color, style = MaterialTheme.typography.labelSmall)
}
