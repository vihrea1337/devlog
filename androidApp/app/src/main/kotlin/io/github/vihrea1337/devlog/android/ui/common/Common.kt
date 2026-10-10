package io.github.vihrea1337.devlog.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.vihrea1337.devlog.ErrorResponse
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.Json

// --- Тексты ошибок ---

/**
 * Сообщение об ошибке человеческим языком. Retrofit на ответ 4xx/5xx бросает
 * HttpException с текстом вида «HTTP 400 Bad Request» — сам по себе он ничего
 * не объясняет. Но сервер кладёт причину в тело ответа (`{"error": "…"}`),
 * поэтому сначала пробуем достать её оттуда.
 */
fun Throwable.humanMessage(): String = when (this) {
    is java.net.SocketTimeoutException -> "Сервер долго не отвечает — попробуйте ещё раз"
    is java.io.IOException -> "Нет связи с сервером"
    is retrofit2.HttpException -> when (code()) {
        401 -> "Сессия истекла — войдите заново"
        else -> serverError() ?: "Ошибка сервера (${code()})"
    }
    else -> message ?: "Неизвестная ошибка"
}

/**
 * Текст ошибки из тела ответа. `errorBody()` читается один раз и может быть чем
 * угодно (например, HTML от прокси), поэтому всё в runCatching: не смогли
 * разобрать — вернём null, и наверху останется код HTTP.
 */
private fun retrofit2.HttpException.serverError(): String? = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    errorJson.decodeFromString(ErrorResponse.serializer(), body).error.ifBlank { null }
}.getOrNull()

/** Один разборщик на всё приложение: создавать Json на каждую ошибку дорого. */
private val errorJson = Json { ignoreUnknownKeys = true }

// --- Даты ---

private val RU = Locale.forLanguageTag("ru")

/** «2026-08-12» или «2026-08-12T15:18:52Z» → «12 авг. 2026». */
fun humanDate(iso: String): String = runCatching {
    LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", RU))
}.getOrDefault(iso)

/** Заголовок дня в ленте: «Сегодня», «Вчера» или «Пт, 10 октября». */
fun dayHeader(iso: String, today: LocalDate = LocalDate.now()): String {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    return when (date) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> {
            val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).replaceFirstChar { it.uppercase() }
            val pattern = if (date.year == today.year) "d MMMM" else "d MMMM yyyy"
            "$weekday, ${date.format(DateTimeFormatter.ofPattern(pattern, RU))}"
        }
    }
}

/** 90 → «1 ч 30 мин», 480 → «8 ч», 45 → «45 мин» (как в отчётах на сервере). */
fun humanDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m мин"
        m == 0 -> "$h ч"
        else -> "$h ч $m мин"
    }
}

/**
 * Русское склонение после числа: plural(1, "день", "дня", "дней") = «день»,
 * 3 → «дня», 5, 11, 12 → «дней», 21 → «день».
 */
fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

// --- Общие элементы интерфейса ---

/** Пустое состояние: значок, заголовок и подсказка, что делать. Лучше, чем голый экран. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Поле даты: показывает выбранное человеческим форматом, по нажатию открывает календарь.
 *
 * Календарь Material3 работает в миллисекундах UTC-полуночи. Переводить их в местную
 * зону нельзя: восточнее Лондона дата съедет на день назад — ровно та ловушка, на
 * которой мы уже обожглись в вебе с `toISOString()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    isoDate: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    var showPicker by remember { mutableStateOf(false) }
    val parsed = remember(isoDate) { runCatching { LocalDate.parse(isoDate.trim()) }.getOrNull() }

    Box(modifier) {
        OutlinedTextField(
            value = parsed?.let { dayHeader(it.toString()) } ?: isoDate,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null) } },
            modifier = Modifier.fillMaxWidth(),
        )
        // Поле с readOnly само по себе нажатие не ловит, а клавиатура нам не нужна:
        // прозрачная «крышка» поверх поля открывает календарь.
        Box(
            Modifier
                .matchParentSize()
                .clickable { showPicker = true },
        )
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (parsed ?: LocalDate.now()).toEpochDay() * MILLIS_IN_DAY,
            selectableDates = NotInFuture,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onPick(isoFromUtcMillis(it)) }
                    showPicker = false
                }) { Text("Выбрать") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Отмена") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private const val MILLIS_IN_DAY = 86_400_000L

/** Дневник о прошлой работе: будущие даты бессмысленны, календарь их не даёт. */
@OptIn(ExperimentalMaterial3Api::class)
private object NotInFuture : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        utcTimeMillis <= LocalDate.now().toEpochDay() * MILLIS_IN_DAY

    override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now().year
}

/** Миллисекунды UTC-полуночи из календаря → «ГГГГ-ММ-ДД» для сервера. */
private fun isoFromUtcMillis(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
