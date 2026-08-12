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
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Поиск по ленте и фильтр по тегам. Поиск обязан заглядывать и в структуру от ИИ:
 * человек ищет слово, а оно может быть только в сути или тегах — иначе поиск
 * выглядел бы сломанным.
 */
class SearchTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_search;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, Projects, Entries, EntryStructured) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(EntryStructured, Entries, Projects, Users) }
    }

    private suspend fun token(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun addEntry(client: HttpClient, token: String, text: String): EntryDto {
        val r = client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-08-12","rawText":"$text"}""")
        }
        return json.decodeFromString<EntryDto>(r.bodyAsText())
    }

    private suspend fun search(client: HttpClient, token: String, query: String): List<EntryDto> {
        val r = client.get("/api/entries?$query") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        return json.decodeFromString(r.bodyAsText())
    }

    @Test
    fun `поиск по сырому тексту, без учёта регистра`() = testApplication {
        application { module() }
        val t = token(client, "s1@b.co")
        addEntry(client, t, "чинил баг с Виджетом")
        addEntry(client, t, "писал тесты на репозиторий")

        val found = search(client, t, "q=виджет")
        assertEquals(1, found.size)
        assertTrue(found[0].rawText.contains("Виджетом"))
    }

    @Test
    fun `поиск находит слово из структуры ИИ, даже если в сыром тексте его нет`() = testApplication {
        application { module() }
        val t = token(client, "s2@b.co")
        val entry = addEntry(client, t, "весь день возился с этой штукой")
        EntryStructuredRepository.save(
            UUID.fromString(entry.id),
            StructuredDto(summary = "Настроил деплой через Caddy", tags = listOf("devops", "caddy")),
            "test-model",
        )

        assertEquals(1, search(client, t, "q=caddy").size)
        assertEquals(1, search(client, t, "q=деплой").size)
    }

    @Test
    fun `фильтр по тегу совпадает целиком, а не по куску слова`() = testApplication {
        application { module() }
        val t = token(client, "s3@b.co")
        val a = addEntry(client, t, "первая")
        val b = addEntry(client, t, "вторая")
        EntryStructuredRepository.save(UUID.fromString(a.id), StructuredDto(tags = listOf("kotlin")), "m")
        EntryStructuredRepository.save(UUID.fromString(b.id), StructuredDto(tags = listOf("kotlin-dsl")), "m")

        val found = search(client, t, "tag=kotlin")
        assertEquals(1, found.size, "«kotlin» не должен совпасть с «kotlin-dsl»")
        assertEquals(a.id, found[0].id)
    }

    @Test
    fun `поиск не показывает чужие записи`() = testApplication {
        application { module() }
        val mine = token(client, "s4@b.co")
        val other = token(client, "s5@b.co")
        addEntry(client, other, "секретная чужая запись про виджет")
        addEntry(client, mine, "моя запись про виджет")

        val found = search(client, mine, "q=виджет")
        assertEquals(1, found.size)
        assertTrue(found[0].rawText.startsWith("моя"))
    }

    @Test
    fun `символ процента ищется как обычный текст`() = testApplication {
        application { module() }
        val t = token(client, "s6@b.co")
        addEntry(client, t, "покрытие тестами 80% — норм")
        addEntry(client, t, "совсем другая запись")

        // Без экранирования '%' в LIKE означает «что угодно» и нашлись бы обе записи.
        val found = search(client, t, "q=80%25")
        assertEquals(1, found.size)
    }

    @Test
    fun `поиск сочетается с фильтром по дню`() = testApplication {
        application { module() }
        val t = token(client, "s7@b.co")
        addEntry(client, t, "запись про виджет")

        assertEquals(1, search(client, t, "q=виджет&from=2026-08-12&to=2026-08-12").size)
        assertEquals(0, search(client, t, "q=виджет&from=2026-08-13&to=2026-08-13").size)
    }

    @Test
    fun `пустой поиск отдаёт всю ленту`() = testApplication {
        application { module() }
        val t = token(client, "s8@b.co")
        addEntry(client, t, "первая")
        addEntry(client, t, "вторая")

        assertEquals(2, search(client, t, "q=").size)
        assertEquals(2, search(client, t, "q=%20").size)
    }
}
