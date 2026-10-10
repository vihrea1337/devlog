package io.github.vihrea1337.devlog

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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Демо-вход для показа работодателю: смотреть можно, менять — нет.
 * Главное, что проверяем: посетитель с демо-кнопки не может испортить демо-данные.
 */
class DemoAccessTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_demo;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, GithubIntegrations, GitlabIntegrations, Projects, Entries, EntryStructured) }
    }

    @AfterTest
    fun teardown() {
        DemoAccess.email = null
        transaction { SchemaUtils.drop(EntryStructured, Entries, Projects, GitlabIntegrations, GithubIntegrations, Users) }
    }

    @Test
    fun `демо выключен — кнопки нет, вход отклоняется`() = testApplication {
        application { module() }
        DemoAccess.email = null
        val available = json.decodeFromString<DemoAvailabilityDto>(client.get("/api/auth/demo").bodyAsText())
        assertFalse(available.enabled)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/auth/demo").status)
    }

    @Test
    fun `демо-вход видит данные, но ничего не может изменить`() = testApplication {
        application { module() }
        // Владелец демо-аккаунта заводит запись обычным входом.
        val owner = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"demo@x.co","password":"secret1","displayName":"Демо"}""")
        }
        val ownerToken = json.decodeFromString<AuthResponse>(owner.bodyAsText()).token
        client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-10-10","rawText":"запись владельца"}""")
        }

        DemoAccess.email = "demo@x.co"
        assertTrue(json.decodeFromString<DemoAvailabilityDto>(client.get("/api/auth/demo").bodyAsText()).enabled)
        val demo = json.decodeFromString<AuthResponse>(client.post("/api/auth/demo").bodyAsText())
        assertTrue(demo.user.readOnly)
        val bearer = "Bearer ${demo.token}"

        // Смотреть — можно.
        val feed = client.get("/api/entries") { header(HttpHeaders.Authorization, bearer) }
        assertEquals(HttpStatusCode.OK, feed.status)
        assertTrue(feed.bodyAsText().contains("запись владельца"))
        val me = json.decodeFromString<UserDto>(client.get("/api/me") { header(HttpHeaders.Authorization, bearer) }.bodyAsText())
        assertTrue(me.readOnly)

        // Менять — нельзя: ни создать, ни собрать отчёт (он тратит лимит ИИ).
        val create = client.post("/api/entries") {
            header(HttpHeaders.Authorization, bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-10-10","rawText":"чужая правка"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, create.status)
        val report = client.post("/api/reports") {
            header(HttpHeaders.Authorization, bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"2026-10-01","periodEnd":"2026-10-10"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, report.status)

        // Обычный токен владельца при этом работает как раньше.
        val ownerCreate = client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-10-10","rawText":"ещё одна"}""")
        }
        assertEquals(HttpStatusCode.OK, ownerCreate.status)
    }
}
