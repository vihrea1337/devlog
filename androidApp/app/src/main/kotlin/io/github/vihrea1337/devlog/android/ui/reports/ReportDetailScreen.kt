package io.github.vihrea1337.devlog.android.ui.reports

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vihrea1337.devlog.android.ui.common.humanDate

/**
 * Просмотр одного отчёта: готовый документ + публичная ссылка для работодателя,
 * правка текста, копирование, удаление.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(onBack: () -> Unit, vm: ReportDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }
    // «Назад» из правки сначала выходит в просмотр — иначе один промах стирал бы черновик.
    BackHandler(enabled = state.isEditing) { vm.cancelEdit() }

    val report = state.report

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.isEditing) "Правка отчёта" else report?.title.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.isEditing) vm.cancelEdit() else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (report != null && !state.isEditing && !state.readOnly) {
                        IconButton(onClick = vm::startEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Править") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Скопировать текст") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = { menuOpen = false; copyToClipboard(context, report.contentMd) },
                                )
                                if (state.shareUrl != null) {
                                    DropdownMenuItem(
                                        text = { Text("Отозвать ссылку") },
                                        leadingIcon = { Icon(Icons.Outlined.LinkOff, null) },
                                        onClick = { menuOpen = false; vm.unshare() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Удалить") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            // В демо-режиме ссылку выдать нельзя, а уже выданной — поделиться можно.
            if (report != null && !state.isEditing && (!state.readOnly || state.shareUrl != null)) {
                BottomAppBar {
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("${humanDate(report.periodStart)} — ${humanDate(report.periodEnd)}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            state.shareUrl ?: "Ссылка ещё не выдана",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = { vm.share { url -> shareText(context, "${report.title}\n$url") } },
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Отправить")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                report == null && state.isLoading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                report == null ->
                    TextButton(onClick = vm::load, modifier = Modifier.align(Alignment.Center)) { Text("Не удалось загрузить — повторить") }
                state.isEditing -> ReportEditor(state, vm)
                else -> ReportHtml(
                    // Сервер уже прислал отчёт в HTML (тот же, что на публичной странице),
                    // поэтому показываем документ как есть, а не разбираем Markdown ещё раз.
                    html = report.contentHtml.ifBlank { "<pre>${report.contentMd}</pre>" },
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                )
            }
        }
    }

    // Удаление отчёта необратимо — переспрашиваем.
    if (confirmDelete && report != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить отчёт?") },
            text = { Text("«${report.title}» исчезнет вместе с публичной ссылкой.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

/**
 * Редактор отчёта. ИИ пишет черновик, но формулировки под конкретного работодателя
 * человек доводит сам. Правим Markdown: HTML сервер пересоберёт из текста сам.
 */
@Composable
private fun ReportEditor(state: ReportDetailUiState, vm: ReportDetailViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .imePadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.draftTitle,
            onValueChange = vm::setDraftTitle,
            label = { Text("Заголовок") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.draftMd,
            onValueChange = vm::setDraftMd,
            label = { Text("Текст отчёта (Markdown)") },
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(
                "${state.draftMd.length} символов",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = vm::cancelEdit, enabled = !state.isSaving) { Text("Отмена") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = vm::saveEdit, enabled = !state.isSaving) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Сохранить")
                }
            }
        }
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
    val accent = MaterialTheme.colorScheme.primary

    val document = remember(html, background, text, muted, line, accent) {
        """
        <!doctype html><html lang="ru"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{font-family:system-ui,-apple-system,Roboto,sans-serif;margin:0;padding:8px 2px 32px;
               color:${text.css()};background:${background.css()};line-height:1.6;font-size:15px}
          h1{font-size:21px;line-height:1.3} h2{font-size:17px;margin-top:22px;border-top:1px solid ${line.css()};padding-top:14px;color:${accent.css()}}
          h3{font-size:15px} ul{padding-left:20px} li{margin:4px 0} em{color:${muted.css()}}
          code{background:${line.css()};padding:1px 5px;border-radius:4px;font-size:13px}
          table{border-collapse:collapse;width:100%;margin:10px 0;font-size:13px;display:block;overflow-x:auto}
          th,td{border:1px solid ${line.css()};padding:6px 8px;text-align:left}
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
        // baseUrl = null: страница ниоткуда не грузится, весь HTML передаём строкой.
        update = { webView -> webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null) },
    )
}

/** Положить текст отчёта в буфер обмена — вставить в письмо или чат вручную. */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Отчёт DevLog", text))
    // Android 13 и новее показывает своё уведомление о копировании — своё не нужно.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Текст отчёта скопирован", Toast.LENGTH_SHORT).show()
    }
}

/** Отдать текст в системное «Поделиться» — оттуда он уйдёт в любой мессенджер. */
private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Отправить отчёт"))
}

/** Цвет Compose → «#RRGGBB» для CSS внутри WebView. */
private fun Color.css(): String = String.format("#%06X", 0xFFFFFF and toArgb())
