package io.github.vihrea1337.devlog.android.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.vihrea1337.devlog.android.data.SessionStore
import io.github.vihrea1337.devlog.sync.EntryRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Фоновая синхронизация ленты: отправить офлайн-очередь и забрать изменения с сервера.
 *
 * Раньше синхронизация запускалась слушателем сети ВНУТРИ экрана ленты — то есть только
 * пока экран открыт. Запись, сделанная в метро, уезжала, лишь когда пользователь снова
 * открывал приложение. WorkManager — системный планировщик Android: задача с условием
 * «есть сеть» выполнится сама, как только сеть появится, даже если приложение закрыто
 * или телефон перезагрузили.
 *
 * @HiltWorker + @AssistedInject: Context и WorkerParameters даёт WorkManager, а остальное
 * (репозиторий) — Hilt. Фабрику подключает DevLogApp (Configuration.Provider).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val entries: EntryRepository,
    private val session: SessionStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (session.token == null) return Result.success() // вышли из аккаунта — синхронизировать нечего
        return try {
            entries.sync()
            Result.success()
        } catch (e: Exception) {
            Log.w("DevLogSync", "Синхронизация не удалась (попытка $runAttemptCount)", e)
            // Сеть «есть», но сервер недоступен — WorkManager повторит позже сам,
            // с растущей паузой (backoff). После 5 попыток сдаёмся до следующего запуска.
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

/** Постановка задач синхронизации в WorkManager — в одном месте, чтобы не дублировать условия. */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    private val needsNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Синхронизировать «как только будет сеть». Вызывается после каждого изменения
     * записи. REPLACE с уникальным именем: десять правок подряд — одна задача, а не десять.
     */
    fun syncSoon() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Периодическая синхронизация раз в 6 часов: подтянуть то, что ИИ разобрал на сервере,
     * и записи с других устройств. KEEP — не перезапускать отсчёт при каждом входе.
     */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(needsNetwork)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** При выходе из аккаунта — ничего не синхронизируем от чужого имени. */
    fun cancelAll() {
        workManager.cancelUniqueWork(ONE_TIME)
        workManager.cancelUniqueWork(PERIODIC)
    }

    private companion object {
        const val ONE_TIME = "devlog-sync-now"
        const val PERIODIC = "devlog-sync-periodic"
    }
}
