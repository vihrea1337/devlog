package io.github.vihrea1337.devlog.android.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.android.ui.common.DateField
import java.time.LocalDate

/** Что открыто в окне: новая запись или правка существующей. */
sealed interface EditorTarget {
    data object New : EditorTarget
    data class Edit(val entry: EntryDto) : EditorTarget
}

/** Быстрый выбор потраченного времени — набирать минуты на телефоне неудобно. */
private val DURATIONS = listOf(30 to "30 мин", 60 to "1 ч", 120 to "2 ч", 240 to "4 ч", 480 to "8 ч")

/**
 * Окно снизу (bottom sheet) для записи: дата календарём, проект, время и сам текст.
 *
 * При правке меняется только текст: офлайн-очередь умеет отправлять именно правку
 * текста (дата и проект у записи редко бывают ошибочными, а их смена потребовала бы
 * отдельного протокола конфликтов).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorSheet(
    target: EditorTarget,
    projects: List<ProjectDto>,
    defaultProjectId: String?,
    onDismiss: () -> Unit,
    onSave: (date: String, text: String, projectId: String?, minutes: Int?) -> Unit,
) {
    val editing = target as? EditorTarget.Edit
    // rememberSaveable — черновик переживает поворот экрана.
    var text by rememberSaveable { mutableStateOf(editing?.entry?.rawText.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(editing?.entry?.occurredOn ?: LocalDate.now().toString()) }
    var projectId by rememberSaveable { mutableStateOf(editing?.entry?.projectId ?: defaultProjectId) }
    var minutes by rememberSaveable { mutableStateOf(editing?.entry?.timeSpentMin) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }
    // Сразу ставим курсор в поле текста — открыл окно и пишешь.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (editing == null) "Новая запись" else "Правка записи", style = MaterialTheme.typography.titleLarge)

            if (editing == null) {
                DateField(label = "День", isoDate = date, onPick = { date = it }, leadingIcon = Icons.Outlined.CalendarMonth, modifier = Modifier.fillMaxWidth())

                if (projects.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = projectId == null, onClick = { projectId = null }, label = { Text("Без проекта") }) }
                        items(projects, key = { it.id }) { p ->
                            FilterChip(
                                selected = projectId == p.id,
                                onClick = { projectId = p.id },
                                label = { Text(if (p.aiEnabled) p.name else "${p.name} · без ИИ") },
                                leadingIcon = { ProjectDot(p) },
                            )
                        }
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DURATIONS) { (value, label) ->
                        FilterChip(
                            selected = minutes == value,
                            onClick = { minutes = if (minutes == value) null else value },
                            label = { Text(label) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Что сделал?") },
                placeholder = { Text("Как попало, с сокращениями — ИИ разберёт: что сделал, какие решения принял, что сломалось и как починил.") },
                minLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .focusRequester(focus),
            )

            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Отмена") }
                Button(onClick = { onSave(date, text, projectId, minutes) }, enabled = text.isNotBlank()) {
                    Text(if (editing == null) "Сохранить" else "Сохранить правку")
                }
            }
        }
    }
}

