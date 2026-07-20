package io.github.vihrea1337.devlog

import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Лимит обращений к ИИ на пользователя в сутки — чтобы один аккаунт не «сжёг» бесплатный
 * лимит Groq и наш бюджет. Счётчики держим в памяти (для MVP достаточно; при рестарте
 * сервера сбрасываются). Порог настраивается переменной окружения AI_DAILY_LIMIT.
 */
object AiLimiter {
    val dailyLimit: Int = System.getenv("AI_DAILY_LIMIT")?.toIntOrNull() ?: 200

    private data class DayCount(val day: LocalDate, val count: Int)
    private val counts = ConcurrentHashMap<UUID, DayCount>()

    /** Занять одну ИИ-операцию: true — можно, false — суточный лимит исчерпан. */
    @Synchronized
    fun tryConsume(userId: UUID, limit: Int = dailyLimit): Boolean {
        val today = LocalDate.now()
        val current = counts[userId]
        val used = if (current == null || current.day != today) 0 else current.count
        if (used >= limit) return false
        counts[userId] = DayCount(today, used + 1)
        return true
    }

    /** Только для тестов: сбросить счётчики. */
    internal fun reset() = counts.clear()
}
