package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Интеграционные тесты проектов на H2: CRUD, изоляция пользователей, привязка записей к проекту,
 * обнуление project_id при удалении проекта (SET_NULL) и «выключатель ИИ по проекту».
 */
class ProjectTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_projects;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, Projects, Entries, EntryStructured) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(EntryStructured, Entries, Projects, Users) }
    }

    private suspend fun registerToken(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun createProject(client: HttpClient, token: String, body: String): ProjectDto {
        val r = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, r.status)
        return json.decodeFromString<ProjectDto>(r.bodyAsText())
    }

    private suspend fun createEntry(client: HttpClient, token: String, text: String, projectId: String?): EntryDto {
        val proj = if (projectId != null) ""","projectId":"$projectId"""" else ""
        val r = client.post("/api/entries") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"occurredOn":"2026-07-20","rawText":"$text"$proj}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        return json.decodeFromString<EntryDto>(r.bodyAsText())
    }

    private suspend fun getEntry(client: HttpClient, token: String, id: String): EntryDto {
        val r = client.get("/api/entries/$id") { header(HttpHeaders.Authorization, "Bearer $token") }
        return json.decodeFromString<EntryDto>(r.bodyAsText())
    }

    @Test
    fun `создание, список и чтение проекта`() = testApplication {
        application { module() }
        val token = registerToken(client, "p1@b.co")

        val created = createProject(client, token, """{"name":"Клиент Acme","color":"#3b6ef5"}""")
        assertEquals("Клиент Acme", created.name)
        assertTrue(created.aiEnabled)      // по умолчанию ИИ включён
        assertFalse(created.archived)

        val list = client.get("/api/projects") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("Acme"))
    }

    @Test
    fun `правка меняет имя и выключатель ИИ`() = testApplication {
        application { module() }
        val token = registerToken(client, "p2@b.co")
        val created = createProject(client, token, """{"name":"старое имя"}""")

        val upd = client.put("/api/projects/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"новое имя","aiEnabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, upd.status)
        val dto = json.decodeFromString<ProjectDto>(upd.bodyAsText())
        assertEquals("новое имя", dto.name)
        assertFalse(dto.aiEnabled)
    }

    @Test
    fun `удаление проекта обнуляет project_id у записи, но запись остаётся`() = testApplication {
        application { module() }
        val token = registerToken(client, "p3@b.co")
        val project = createProject(client, token, """{"name":"на удаление"}""")
        val entry = createEntry(client, token, "запись в проекте", project.id)
        assertEquals(project.id, entry.projectId)

        val del = client.delete("/api/projects/${project.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NoContent, del.status)

        // Запись цела, но уже без проекта (SET_NULL).
        val after = getEntry(client, token, entry.id)
        assertEquals("запись в проекте", after.rawText)
        assertNull(after.projectId)
    }

    @Test
    fun `фильтр ленты по проекту`() = testApplication {
        application { module() }
        val token = registerToken(client, "p4@b.co")
        val project = createProject(client, token, """{"name":"Проект X"}""")
        createEntry(client, token, "в проекте X", project.id)
        createEntry(client, token, "без проекта", null)

        val filtered = client.get("/api/entries?projectId=${project.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val body = filtered.bodyAsText()
        assertTrue(body.contains("в проекте X"))
        assertFalse(body.contains("без проекта"))
    }

    @Test
    fun `выключатель ИИ проекта помечает запись черновиком, а не очередью`() = testApplication {
        application { module() }
        val token = registerToken(client, "p5@b.co")
        val privateProject = createProject(client, token, """{"name":"Секретный клиент","aiEnabled":false}""")

        val entry = createEntry(client, token, "конфиденциальная работа", privateProject.id)
        // ИИ для проекта выключен → запись не уходит в обработку, статус draft (а не queued).
        assertEquals("draft", getEntry(client, token, entry.id).status)
    }

    @Test
    fun `чужой проект не виден и не меняется (изоляция)`() = testApplication {
        application { module() }
        val tokenA = registerToken(client, "pa@b.co")
        val tokenB = registerToken(client, "pb@b.co")
        val projectA = createProject(client, tokenA, """{"name":"проект A"}""")

        // B не видит проект A в списке.
        val listB = client.get("/api/projects") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals("[]", listB.bodyAsText())

        // B не может прочитать/удалить проект A по прямому id.
        val getB = client.get("/api/projects/${projectA.id}") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.NotFound, getB.status)
        val delB = client.delete("/api/projects/${projectA.id}") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.NotFound, delB.status)
    }

    @Test
    fun `без токена — 401`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/projects").status)
    }
}
