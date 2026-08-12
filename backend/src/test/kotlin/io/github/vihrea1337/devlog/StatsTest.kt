package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Статистика активности: календарь по дням и серии («сколько дней подряд»).
 * Серии считаются чистыми функциями — их проверяем без базы, отдельно от ручки.
 */
class StatsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_stats;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, Projects, Entries, EntryStructured) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(EntryStructured, Entries, Projects, Users) }
    }

    private fun day(s: String) = LocalDate.parse(s)

    @Test
    fun `серия считается назад от сегодняшнего дня`() {
        val days = setOf(day("2026-08-12"), day("2026-08-11"), day("2026-08-10"), day("2026-08-07"))
        assertEquals(3, StatsRepository.currentStreak(days, day("2026-08-12")))
    }

    @Test
    fun `вчерашняя запись не обрывает серию — сегодня ещё не кончилось`() {
        val days = setOf(day("2026-08-11"), day("2026-08-10"))
        assertEquals(2, StatsRepository.currentStreak(days, day("2026-08-12")))
    }

    @Test
    fun `пропуск двух дней обрывает серию`() {
        val days = setOf(day("2026-08-09"), day("2026-08-08"))
        assertEquals(0, StatsRepository.currentStreak(days, day("2026-08-12")))
        assertEquals(0, StatsRepository.currentStreak(emptySet(), day("2026-08-12")))
    }

    @Test
    fun `рекордная серия ищется по всему периоду`() {
        val days = setOf(
            day("2026-07-01"), day("2026-07-02"), day("2026-07-03"), day("2026-07-04"), // 4 подряд
            day("2026-07-20"), day("2026-07-21"), // 2 подряд
        )
        assertEquals(4, StatsRepository.longestStreak(days))
        assertEquals(0, StatsRepository.longestStreak(emptySet()))
    }

    private suspend fun token(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun addEntry(client: HttpClient, token: String, date: String, text: String) {
        client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"$date","rawText":"$text"}""")
        }
    }

    @Test
    fun `ручка считает записи по дням и не видит чужие`() = testApplication {
        application { module() }
        val mine = token(client, "stats@b.co")
        val other = token(client, "other@b.co")
        val today = LocalDate.now()

        addEntry(client, mine, today.toString(), "две записи за сегодня — раз")
        addEntry(client, mine, today.toString(), "две записи за сегодня — два")
        addEntry(client, mine, today.minusDays(1).toString(), "вчера")
        addEntry(client, other, today.toString(), "чужая запись")

        val res = client.get("/api/stats/activity") { header(HttpHeaders.Authorization, "Bearer $mine") }
        assertEquals(HttpStatusCode.OK, res.status)
        val stats = json.decodeFromString<ActivityStatsDto>(res.bodyAsText())

        assertEquals(2, stats.days[today.toString()], "за сегодня должно быть две записи")
        assertEquals(1, stats.days[today.minusDays(1).toString()])
        assertEquals(3, stats.totalEntries, "чужая запись не должна попасть в статистику")
        assertEquals(2, stats.activeDays)
        assertEquals(2, stats.currentStreak)
    }

    @Test
    fun `кривая дата в параметрах — 400`() = testApplication {
        application { module() }
        val t = token(client, "statsbad@b.co")

        val res = client.get("/api/stats/activity?from=когда-нибудь") {
            header(HttpHeaders.Authorization, "Bearer $t")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `без токена статистику не отдаём`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/stats/activity").status)
    }
}
