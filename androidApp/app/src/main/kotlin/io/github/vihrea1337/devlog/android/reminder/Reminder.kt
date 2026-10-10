package io.github.vihrea1337.devlog.android.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.vihrea1337.devlog.android.MainActivity
import io.github.vihrea1337.devlog.android.R
import io.github.vihrea1337.devlog.android.data.SessionStore
import io.github.vihrea1337.devlog.sync.EntryRepository
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Настройки напоминания: включено ли и в котором часу. */
data class ReminderSettings(val enabled: Boolean = false, val hour: Int = 20)

// DataStore — современная замена SharedPreferences для настроек: читается асинхронно
// через Flow (экран сам перерисуется при изменении) и не блокирует главный поток.
private val Context.settingsStore by preferencesDataStore(name = "settings")

/**
 * Вечернее напоминание «Что сделал сегодня?». Дневник ценен, только если его вести
 * каждый день, а к вечеру детали дня ещё свежие.
 */
@Singleton
class ReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val enabledKey = booleanPreferencesKey("reminder_enabled")
    private val hourKey = intPreferencesKey("reminder_hour")

    val settings: Flow<ReminderSettings> = context.settingsStore.data.map { prefs ->
        ReminderSettings(enabled = prefs[enabledKey] ?: false, hour = prefs[hourKey] ?: 20)
    }

    suspend fun update(enabled: Boolean, hour: Int) {
        context.settingsStore.edit {
            it[enabledKey] = enabled
            it[hourKey] = hour
        }
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        // Периодическая задача раз в сутки; первый запуск — в ближайшие [hour]:00.
        // UPDATE: смена часа переставляет существующую задачу, а не плодит вторую.
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilHour(hour).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val WORK_NAME = "devlog-evening-reminder"
    }
}

/**
 * Сколько ждать до ближайших hour:00 — сегодня, если ещё не наступило, иначе завтра.
 * Чистая функция (время передаётся параметром) — поэтому легко проверяется тестом.
 */
fun delayUntilHour(hour: Int, now: LocalDateTime = LocalDateTime.now()): Duration {
    var next = now.toLocalDate().atTime(LocalTime.of(hour, 0))
    if (!next.isAfter(now)) next = next.plusDays(1)
    return Duration.between(now, next)
}

/**
 * Показывает уведомление, только если за сегодня ещё нет ни одной записи: напоминать
 * тому, кто уже всё записал, — лишний шум, из-за которого уведомления отключают.
 * Проверка идёт по локальной базе — работает и без сети.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val entries: EntryRepository,
    private val session: SessionStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (session.token == null) return Result.success() // вышли из аккаунта — не напоминаем
        val today = LocalDate.now().toString()
        val hasToday = entries.entries().first().any { it.occurredOn == today }
        if (!hasToday) showNotification(applicationContext)
        return Result.success()
    }
}

const val EXTRA_OPEN_NEW_ENTRY = "open_new_entry"
private const val CHANNEL_ID = "reminders"

private fun showNotification(context: Context) {
    // Android 13+: без разрешения POST_NOTIFICATIONS уведомление молча не покажется.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    // Канал уведомлений (Android 8+): пользователь может выключить именно напоминания,
    // не трогая остальное. Повторное создание канала безопасно — ничего не меняет.
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
    )

    // Нажатие открывает приложение сразу с окном новой записи.
    val intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_OPEN_NEW_ENTRY, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.reminder_title))
        .setContentText(context.getString(R.string.reminder_text))
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(1, notification)
}
