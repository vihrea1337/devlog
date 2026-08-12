package io.github.vihrea1337.devlog

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.util.UUID

/**
 * Статистика активности — то, из чего рисуется «летопись»: календарь года
 * (как квадратики контрибуций на GitHub) и счётчик дней подряд.
 *
 * Считаем на сервере, а не в браузере: клиенту не нужно тянуть все записи за год,
 * чтобы посчитать количество по дням, а мобильному приложению это тем более пригодится.
 */
fun Route.statsRoutes() = authenticate("auth-jwt") {
    get("/api/stats/activity") {
        val to = call.request.queryParameters["to"]?.let {
            parseDateOrNull(it) ?: return@get call.badRequest("Некорректная дата to")
        } ?: LocalDate.now()
        val from = call.request.queryParameters["from"]?.let {
            parseDateOrNull(it) ?: return@get call.badRequest("Некорректная дата from")
        } ?: to.minusDays(364)
        if (from.isAfter(to)) return@get call.badRequest("Начало периода позже конца")

        call.respond(StatsRepository.activity(call.userId(), from, to))
    }
}

object StatsRepository {

    fun activity(userId: UUID, from: LocalDate, to: LocalDate): ActivityStatsDto {
        val counts = transaction {
            val countColumn = Entries.id.count()
            Entries
                .select(Entries.occurredOn, countColumn)
                .where {
                    (Entries.userId eq userId) and
                        (Entries.occurredOn greaterEq from) and
                        (Entries.occurredOn lessEq to)
                }
                .groupBy(Entries.occurredOn)
                .associate { it[Entries.occurredOn] to it[countColumn].toInt() }
        }

        return ActivityStatsDto(
            days = counts.mapKeys { it.key.toString() },
            currentStreak = currentStreak(counts.keys, to),
            longestStreak = longestStreak(counts.keys),
            totalEntries = counts.values.sum(),
            activeDays = counts.size,
            from = from.toString(),
            to = to.toString(),
        )
    }

    /**
     * Сколько дней подряд заканчивая сегодняшним. Если за сегодня записи ещё нет,
     * но была вчера — серия не считается прерванной: день ещё не закончился.
     */
    fun currentStreak(days: Set<LocalDate>, today: LocalDate): Int {
        if (days.isEmpty()) return 0
        var cursor = when {
            today in days -> today
            today.minusDays(1) in days -> today.minusDays(1)
            else -> return 0
        }
        var streak = 0
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** Самая длинная серия подряд идущих дней за весь период. */
    fun longestStreak(days: Set<LocalDate>): Int {
        if (days.isEmpty()) return 0
        val sorted = days.sorted()
        var best = 1
        var current = 1
        for (i in 1 until sorted.size) {
            current = if (sorted[i - 1].plusDays(1) == sorted[i]) current + 1 else 1
            if (current > best) best = current
        }
        return best
    }
}
