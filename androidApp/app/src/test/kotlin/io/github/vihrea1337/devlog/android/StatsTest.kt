package io.github.vihrea1337.devlog.android

import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.StructuredDto
import io.github.vihrea1337.devlog.android.ui.feed.WeekSummary
import io.github.vihrea1337.devlog.android.ui.profile.ProjectShare
import io.github.vihrea1337.devlog.android.ui.profile.TimeBreakdown
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты расчётов для экранов: сводка недели в ленте и «Куда уходит время» в профиле.
 *
 * Это чистые функции (на входе — список записей, на выходе — цифры), поэтому тесты
 * простые: ни корутин, ни фейков репозиториев. Дату «сегодня» передаём явно,
 * иначе тест зависел бы от дня, в который его запустили.
 */
class StatsTest {

    private val today = LocalDate.parse("2026-10-10")

    /** Запись с нужными полями; остальное заполнено «чем-нибудь». */
    private fun entry(
        day: String,
        minutes: Int? = null,
        projectId: String? = null,
        tags: List<String> = emptyList(),
    ) = EntryDto(
        id = "$day-$minutes-$projectId-${tags.joinToString()}",
        projectId = projectId,
        occurredOn = day,
        rawText = "заметка",
        status = "structured",
        timeSpentMin = minutes,
        createdAt = "",
        updatedAt = "",
        structured = StructuredDto(tags = tags),
    )

    // --- Сводка недели ---

    @Test
    fun `неделя — ровно 7 дней, заканчивается сегодня`() {
        val week = WeekSummary.from(emptyList(), today)

        assertEquals(7, week.days.size)
        assertEquals(LocalDate.parse("2026-10-04"), week.days.first().first)
        assertEquals(today, week.days.last().first)
    }

    @Test
    fun `записи вне последних 7 дней не считаются`() {
        val week = WeekSummary.from(
            listOf(
                entry("2026-10-03", minutes = 120), // 8 дней назад — уже не неделя
                entry("2026-10-11", minutes = 120), // завтра (например, сбитые часы) — тоже нет
                entry("2026-10-04", minutes = 30),  // первый день недели — да
            ),
            today,
        )

        assertEquals(1, week.entries)
        assertEquals(30, week.minutes)
        assertEquals(1, week.activeDays)
    }

    @Test
    fun `день без указанного времени — столбик по числу записей, но в часы не идёт`() {
        val week = WeekSummary.from(
            listOf(entry("2026-10-09"), entry("2026-10-09"), entry("2026-10-10", minutes = 45)),
            today,
        )

        // Две записи без времени — по часу на столбик, чтобы день не выглядел пустым.
        assertEquals(120, week.days.single { it.first == LocalDate.parse("2026-10-09") }.second)
        assertEquals(45, week.days.last().second)
        // А «учтено» — только реально указанное время, без придуманных часов.
        assertEquals(45, week.minutes)
        assertEquals(2, week.activeDays)
        assertEquals(3, week.entries)
    }

    @Test
    fun `запись с испорченной датой пропускается, а не роняет экран`() {
        val week = WeekSummary.from(listOf(entry("вчера", minutes = 60), entry("2026-10-10", minutes = 60)), today)

        assertEquals(1, week.entries)
    }

    @Test
    fun `пустая неделя — сколько дней прошло с последней записи`() {
        val week = WeekSummary.from(listOf(entry("2026-09-30"), entry("2026-10-01"), entry("2026-12-31")), today)

        assertEquals(0, week.entries)
        // Запись «из будущего» (сбитые часы) не считается последней.
        assertEquals(9, week.daysSinceLast)
        assertEquals(null, WeekSummary.from(emptyList(), today).daysSinceLast)
    }

    // --- Куда уходит время ---

    private val projects = listOf(
        ProjectDto(id = "p1", name = "DevLog", color = "#3B6EF5"),
        ProjectDto(id = "p2", name = "Solmu", color = "#7C3AED"),
    )

    @Test
    fun `минуты по проектам — от большего к меньшему`() {
        val time = TimeBreakdown.from(
            listOf(
                entry("2026-10-01", minutes = 60, projectId = "p1"),
                entry("2026-10-02", minutes = 30, projectId = "p1"),
                entry("2026-10-03", minutes = 240, projectId = "p2"),
            ),
            projects,
        )

        assertEquals(
            listOf(ProjectShare("Solmu", "#7C3AED", 240), ProjectShare("DevLog", "#3B6EF5", 90)),
            time.shares,
        )
    }

    @Test
    fun `записи без времени не рисуют дугу, без проекта — отдельная доля`() {
        val time = TimeBreakdown.from(
            listOf(
                entry("2026-10-01", minutes = null, projectId = "p1"),
                entry("2026-10-02", minutes = 0, projectId = "p2"),
                entry("2026-10-03", minutes = 50, projectId = null),
                entry("2026-10-04", minutes = 10, projectId = "удалённый-проект"),
            ),
            projects,
        )

        // Запись удалённого проекта попадает в ту же долю «Без проекта» — одна строка, а не две.
        assertEquals(listOf(ProjectShare("Без проекта", null, 60)), time.shares)
    }

    @Test
    fun `частые темы — без учёта регистра и пробелов, не больше заданного числа`() {
        val time = TimeBreakdown.from(
            listOf(
                entry("2026-10-01", tags = listOf("Android", " ktor")),
                entry("2026-10-02", tags = listOf("android ", "docker", "")),
                entry("2026-10-03", tags = listOf("ANDROID", "Ktor")),
            ),
            projects,
            maxTags = 2,
        )

        assertEquals(listOf("android" to 3, "ktor" to 2), time.topTags)
    }
}
