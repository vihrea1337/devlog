package io.github.vihrea1337.devlog.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.vihrea1337.devlog.android.data.SessionStore
import io.github.vihrea1337.devlog.android.sync.SyncScheduler
import javax.inject.Inject

/**
 * Класс приложения. @HiltAndroidApp — точка входа Hilt: отсюда он строит граф
 * зависимостей для всех экранов и воркеров.
 *
 * Configuration.Provider — WorkManager создаёт воркеры через фабрику Hilt, поэтому
 * SyncWorker/ReminderWorker могут получать репозитории в конструктор.
 */
@HiltAndroidApp
class DevLogApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var session: SessionStore
    @Inject lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Вошедшему пользователю — периодическая синхронизация (KEEP: не сбрасывает
        // уже запланированную) и разовая сразу: вдруг в очереди лежат офлайн-записи.
        if (session.token != null) {
            syncScheduler.schedulePeriodic()
            syncScheduler.syncSoon()
        }
    }
}
