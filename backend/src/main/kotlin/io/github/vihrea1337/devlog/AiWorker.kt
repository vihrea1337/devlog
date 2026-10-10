package io.github.vihrea1337.devlog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Что умеет «обработчик текста» с точки зрения воркера. Настоящая реализация ходит в Groq
 * ([AiProcessor]), а в тестах подставляется поддельная — так очередь проверяется без сети.
 */
interface AiStructurer {
    val modelName: String

    /** Разобрать сырой текст на структуру. Кидает исключение, если не вышло. */
    suspend fun structure(rawText: String): StructuredDto
}

/**
 * ИИ-сервис ответил «слишком часто» (HTTP 429): упёрлись в поминутный лимит токенов Groq.
 * Это НЕ ошибка записи — с ней всё в порядке, просто нужно подождать [retryAfter].
 *
 * Отдельный тип нужен воркеру, чтобы отличить такую паузу от настоящей поломки: раньше
 * 429 считался обычной неудачей, три быстрых повтора подряд тоже получали 429 — и запись
 * навсегда становилась failed. На бесплатном тарифе Groq (8000 токенов в минуту) так падала
 * треть записей при любом импорте пачкой.
 */
class AiRateLimitedException(val retryAfter: Duration, message: String) : RuntimeException(message)

/**
 * Воркер обработки записей ИИ. **Очередь живёт в базе, а не в памяти** — в этом весь смысл.
 *
 * Было: сервер запускал корутину «обработай запись» и забывал про неё. Перезапуск сервера
 * (редеплой, падение, перезагрузка) — и записи навсегда зависали в статусе queued/processing.
 *
 * Стало: запись просто сохраняется со статусом queued, а воркер в цикле спрашивает у базы
 * «что не обработано?» и берёт следующую. После перезапуска он подберёт и те записи,
 * что остались в очереди, и те, что оборвались на середине (висят в processing дольше
 * [stuckAfter]). Ошибки не теряются: текст ошибки пишется в запись, попытки считаются,
 * после [maxAttempts] неудач запись помечается failed — и пользователь видит причину.
 */
class AiWorker(
    private val ai: AiStructurer,
    private val pollDelay: Duration = Duration.ofSeconds(5),
    private val maxAttempts: Int = 3,
    private val stuckAfter: Duration = Duration.ofMinutes(5),
    private val limiter: (UUID) -> Boolean = { AiLimiter.tryConsume(it) },
) {
    private var job: Job? = null

    // До какого момента воркер «спит» после ответа 429 от Groq. Лимит общий на весь ключ
    // (а не на пользователя), поэтому и пауза общая: брать следующую запись бессмысленно —
    // она получит тот же 429. @Volatile — значение читается и пишется из корутин на разных потоках.
    @Volatile
    private var pausedUntil: Instant = Instant.EPOCH

    // Пользователи, у которых на сегодня кончился суточный лимит ИИ. Их записи воркер
    // пропускает до завтра — иначе он крутился бы вхолостую на одной и той же записи.
    private val limitedUntilTomorrow = ConcurrentHashMap<UUID, LocalDate>()

    /** Запустить фоновой цикл. Вызывается один раз при старте сервера. */
    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        if (job != null) return
        job = scope.launch {
            println("Воркер ИИ запущен (модель ${ai.modelName}, опрос раз в ${pollDelay.seconds} с).")
            while (isActive) {
                // Обрабатываем подряд, пока в очереди что-то есть; опустела — ждём и спрашиваем снова.
                val didWork = runCatching { runOnce() }.getOrElse { e ->
                    println("Воркер ИИ: неожиданная ошибка цикла — ${e.message}")
                    false
                }
                if (!didWork) delay(pollDelay.toMillis())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Обработать одну запись из очереди. Возвращает true, если работа нашлась.
     * Вынесено отдельным методом, чтобы тесты дёргали его напрямую — без таймеров и ожиданий.
     */
    suspend fun runOnce(): Boolean {
        // Пауза после 429 ещё не кончилась — ничего не берём (цикл подождёт pollDelay и спросит снова).
        if (Instant.now().isBefore(pausedUntil)) return false
        val stuckBefore = Instant.now().minus(stuckAfter)
        val job = EntryRepository.claimNextAiJob(stuckBefore, maxAttempts, skipUsers = limitedUsersToday()) ?: return false

        // Суточный лимит на пользователя: не даём одному аккаунту сжечь общий лимит Groq.
        if (!limiter(job.userId)) {
            limitedUntilTomorrow[job.userId] = LocalDate.now()
            EntryRepository.releaseAiJob(job.id, job.attempt, "Исчерпан суточный лимит обращений к ИИ — обработаем завтра")
            return true
        }

        return try {
            val structured = ai.structure(job.rawText)
            EntryStructuredRepository.save(job.id, structured, ai.modelName)
            EntryRepository.markAiDone(job.id)
            // Сказать открытым вкладкам, что запись готова — им не придётся опрашивать сервер.
            EntryEventBus.publish(job.userId, job.id, "structured")
            true
        } catch (e: AiRateLimitedException) {
            // Лимит Groq: запись возвращаем в очередь БЕЗ траты попытки и делаем общую паузу.
            // Пауза — сколько попросил Groq, но в разумных рамках: не меньше 2 с (чтобы не
            // долбить сервис), не больше 2 мин (если Groq прислал что-то странное).
            val wait = e.retryAfter.coerceIn(Duration.ofSeconds(2), Duration.ofMinutes(2))
            pausedUntil = Instant.now().plus(wait)
            EntryRepository.releaseAiJob(job.id, job.attempt, "Ждём лимит ИИ-сервиса, обработаем через ${wait.seconds.coerceAtLeast(1)} с")
            println("Воркер ИИ: лимит Groq, пауза ${wait.toMillis()} мс")
            false
        } catch (e: Exception) {
            // Попытки ещё есть → вернём в очередь; кончились → окончательный failed с причиной.
            val retry = job.attempt < maxAttempts
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
            EntryRepository.markAiFailed(job.id, "Попытка ${job.attempt}: $reason", retry)
            if (!retry) EntryEventBus.publish(job.userId, job.id, "failed")
            true
        }
    }

    private fun limitedUsersToday(): Set<UUID> {
        val today = LocalDate.now()
        limitedUntilTomorrow.entries.removeIf { it.value != today }
        return limitedUntilTomorrow.keys.toSet()
    }
}
