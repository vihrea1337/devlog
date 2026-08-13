package io.github.vihrea1337.devlog.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vihrea1337.devlog.ReportDto

/**
 * Экран отчётов. Два состояния: список собранных отчётов с формой «собрать за период»
 * и просмотр одного отчёта (готовый документ + публичная ссылка для работодателя).
 */
@Composable
fun ReportsScreen(vm: ReportsViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    // Системная кнопка «назад» из просмотра возвращает к списку, а не закрывает приложение.
    BackHandler(enabled = state.opened != null) { vm.closeOpened() }

    val opened = state.opened
    if (opened == null) {
        ReportsList(state, vm)
    } else {
        ReportDetail(opened, state.shareUrl, state.error, vm)
    }
}

// --- Список отчётов + форма сборки ---

@Composable
private fun ReportsList(state: ReportsUiState, vm: ReportsViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text("Собрать отчёт", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        // Готовые периоды: на телефоне набирать даты руками неудобно.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeriodPreset.entries.forEach { preset ->
                FilterChip(
                    selected = state.preset == preset,
                    onClick = { vm.selectPreset(preset) },
                    label = { Text(preset.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.periodStart,
                onValueChange = vm::setPeriodStart,
                label = { Text("С") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.periodEnd,
                onValueChange = vm::setPeriodEnd,
                label = { Text("По") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        // Проект: отчёт можно собрать по одному проекту — работодателю ни к чему
        // видеть записи из соседних.
        if (state.projects.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
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
                    label = { Text("Все проекты") },
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

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.generate() },
            enabled = !state.isGenerating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isGenerating) {
                // Сборка с ИИ идёт секунды: без индикатора кажется, что приложение зависло.
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Собираю отчёт…")
            } else {
                Text("Собрать отчёт")
            }
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Готовые отчёты", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.refresh() }) { Text("Обновить") }
        }

        if (state.reports.isEmpty()) {
            Text(
                if (state.isLoading) "Загружаю…" else "Отчётов пока нет. Соберите первый за период выше.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.reports, key = { it.id }) { report ->
                    ReportRow(report, projectName = state.projectOf(report.projectId)?.name) { vm.open(report) }
                }
            }
        }
    }
}

/** Строка списка: название, период, дата сборки и метка открытой публичной ссылки. */
@Composable
private fun ReportRow(report: ReportDto, projectName: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(report.title, style = MaterialTheme.typography.titleSmall)
            Text(
                "${humanDate(report.periodStart)} — ${humanDate(report.periodEnd)}" +
                    (projectName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B7382),
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "собран ${humanDate(report.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF6B7382),
                    modifier = Modifier.weight(1f),
                )
                if (report.shareToken != null) {
                    Text(
                        "ссылка открыта",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF1F9D55),
                    )
                }
            }
        }
    }
}

// --- Просмотр одного отчёта ---

@Composable
private fun ReportDetail(report: ReportDto, shareUrl: String?, error: String?, vm: ReportsViewModel) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    /** Положить текст отчёта в буфер обмена — вставить в письмо или чат вручную. */
    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Отчёт DevLog", text))
        // Android 13 и новее показывает своё уведомление о копировании — своё не нужно.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Текст отчёта скопирован", Toast.LENGTH_SHORT).show()
        }
    }

    /** Отдать текст в системное «Поделиться» — оттуда он уйдёт в любой мессенджер. */
    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить отчёт"))
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.closeOpened() }) { Text("← К списку") }
        }
        Text(report.title, style = MaterialTheme.typography.titleMedium)
        Text(
            "${humanDate(report.periodStart)} — ${humanDate(report.periodEnd)}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF6B7382),
        )
        shareUrl?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        // Сам отчёт: сервер уже прислал его в HTML (тот же, что на публичной странице),
        // поэтому показываем документ как есть, а не разбираем Markdown ещё раз.
        ReportHtml(
            html = report.contentHtml.ifBlank { "<pre>${report.contentMd}</pre>" },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (shareUrl != null) {
                    shareText("${report.title}\n$shareUrl")
                } else {
                    // Ссылки ещё нет — сервер выдаст её и вернёт адрес, тогда и делимся.
                    vm.share { url -> shareText("${report.title}\n$url") }
                }
            }) {
                Text(if (shareUrl != null) "Поделиться ссылкой" else "Открыть ссылку и отправить")
            }
            OutlinedButton(onClick = { copyToClipboard(report.contentMd) }) {
                Text("Скопировать текст")
            }
            if (shareUrl != null) {
                TextButton(onClick = { vm.unshare() }) { Text("Отозвать ссылку") }
            }
            TextButton(onClick = { confirmDelete = true }) { Text("Удалить") }
        }
        Spacer(Modifier.height(8.dp))
    }

    // Удаление отчёта необратимо — переспрашиваем.
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить отчёт?") },
            text = { Text("«${report.title}» исчезнет вместе с публичной ссылкой.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(report.id)
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

/**
 * Показ готового HTML-отчёта. WebView — тот же движок, что и в браузере: заголовки,
 * списки и таблицы выглядят как в веб-версии. JavaScript намеренно НЕ включаем:
 * отчёт — это текст, а выключенный JS убирает целый класс проблем безопасности.
 */
@Composable
private fun ReportHtml(html: String, modifier: Modifier = Modifier) {
    // Цвета берём из темы приложения, иначе в тёмной теме получится белый лист.
    val background = MaterialTheme.colorScheme.surface
    val text = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant

    val document = remember(html, background, text, muted, line) {
        """
        <!doctype html><html lang="ru"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{font-family:system-ui,-apple-system,Roboto,sans-serif;margin:0;padding:4px 2px 24px;
               color:${text.css()};background:${background.css()};line-height:1.55;font-size:15px}
          h1{font-size:20px} h2{font-size:17px;margin-top:20px;border-top:1px solid ${line.css()};padding-top:12px}
          h3{font-size:15px} ul{padding-left:20px} em{color:${muted.css()}}
          table{border-collapse:collapse;width:100%;margin:10px 0;font-size:13px;display:block;overflow-x:auto}
          th,td{border:1px solid ${line.css()};padding:5px 8px;text-align:left}
        </style></head><body>$html</body></html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = true
            }
        },
        update = { webView ->
            // baseUrl = null: страница ниоткуда не грузится, весь HTML передаём строкой.
            webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
        },
    )
}

/** «2026-08-12» или «2026-08-12T15:18:52Z» → «12.08.2026». */
private fun humanDate(iso: String): String {
    val parts = iso.take(10).split("-")
    return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else iso
}

/** Цвет Compose → «#RRGGBB» для CSS внутри WebView. */
private fun Color.css(): String = String.format("#%06X", 0xFFFFFF and toArgb())
