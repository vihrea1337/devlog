package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
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
import kotlin.test.assertTrue

/**
 * `POST /api/reports/{id}/export-github` — обратная сторона импорта коммитов. В сеть не ходим:
 * подменяем GithubExporter.fetchFile/putFile, как GithubImportTest подменяет fetchEvents/fetchCompare.
 */
class ReportExportGithubTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_report_export;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, GithubIntegrations, GitlabIntegrations, Projects, Entries, EntryStructured, Reports) }
        // По умолчанию — файла ещё нет, запись успешна и ничего не делает с сетью.
        GithubExporter.fetchFile = { _, _, _ -> null }
        GithubExporter.putFile = { _, _, _, _, _, _ -> }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(Reports, EntryStructured, Entries, Projects, GitlabIntegrations, GithubIntegrations, Users) }
    }

    private suspend fun token(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun createProject(client: HttpClient, t: String, body: String): ProjectDto {
        val r = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return json.decodeFromString(r.bodyAsText())
    }

    private suspend fun bindToken(client: HttpClient, t: String) {
        client.post("/api/me/github-token") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"ghp_export_test"}""")
        }
    }

    private suspend fun makeReport(client: HttpClient, t: String, projectId: String?): ReportDto {
        val proj = if (projectId != null) ""","projectId":"$projectId"""" else ""
        val r = client.post("/api/reports") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"periodStart":"2026-08-01","periodEnd":"2026-08-31"$proj}""")
        }
        return json.decodeFromString(r.bodyAsText())
    }

    private suspend fun export(client: HttpClient, t: String, reportId: String) =
        client.post("/api/reports/$reportId/export-github") { header(HttpHeaders.Authorization, "Bearer $t") }

    @Test
    fun `без проекта или без настроенного репозитория — 400`() = testApplication {
        application { module() }
        val t = token(client, "exp1@b.co")
        val report = makeReport(client, t, projectId = null)

        val r = export(client, t, report.id)
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `проект без githubExportRepo — тоже 400, даже если у отчёта есть проект`() = testApplication {
        application { module() }
        val t = token(client, "exp2@b.co")
        val project = createProject(client, t, """{"name":"Без экспорта"}""")
        val report = makeReport(client, t, project.id)

        val r = export(client, t, report.id)
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `репозиторий настроен, но токен не привязан — 400`() = testApplication {
        application { module() }
        val t = token(client, "exp3@b.co")
        val project = createProject(client, t, """{"name":"С экспортом"}""")
        client.put("/api/projects/${project.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"githubExportRepo":"vihrea1337/devlog"}""")
        }
        val report = makeReport(client, t, project.id)

        val r = export(client, t, report.id)
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `успешный экспорт пишет в замоканный putFile нужный текст и путь по умолчанию`() = testApplication {
        application { module() }
        val t = token(client, "exp4@b.co")
        val project = createProject(client, t, """{"name":"С экспортом"}""")
        client.put("/api/projects/${project.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"githubExportRepo":"vihrea1337/devlog"}""")
        }
        bindToken(client, t)
        val report = makeReport(client, t, project.id)

        var seenRepo: String? = null
        var seenPath: String? = null
        var seenContent: String? = null
        var seenSha: String? = null
        var seenToken: String? = null
        GithubExporter.putFile = { repo, path, tok, content, sha, _ ->
            seenRepo = repo; seenPath = path; seenContent = content; seenSha = sha; seenToken = tok
        }

        val r = export(client, t, report.id)
        assertEquals(HttpStatusCode.OK, r.status)
        val result = json.decodeFromString<ExportGithubResultDto>(r.bodyAsText())
        assertEquals("vihrea1337/devlog", result.repo)
        assertEquals(GithubExporter.DEFAULT_EXPORT_PATH, result.path)

        assertEquals("vihrea1337/devlog", seenRepo)
        assertEquals(GithubExporter.DEFAULT_EXPORT_PATH, seenPath)
        assertEquals("ghp_export_test", seenToken)
        assertEquals(null, seenSha, "файла ещё не было — sha отсутствует, это создание, а не перезапись")
        assertTrue(seenContent!!.contains("<!-- devlog:report:${report.id} -->"))
        assertTrue(seenContent!!.contains(report.title))
    }

    @Test
    fun `свой путь до файла в настройках проекта уважается`() = testApplication {
        application { module() }
        val t = token(client, "exp5@b.co")
        val project = createProject(client, t, """{"name":"С экспортом"}""")
        client.put("/api/projects/${project.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"githubExportRepo":"vihrea1337/devlog","githubExportPath":"CHANGELOG.md"}""")
        }
        bindToken(client, t)
        val report = makeReport(client, t, project.id)

        var seenPath: String? = null
        GithubExporter.putFile = { _, path, _, _, _, _ -> seenPath = path }

        export(client, t, report.id)
        assertEquals("CHANGELOG.md", seenPath)
    }

    @Test
    fun `повторный экспорт передаёт sha уже существующего файла — обновление, а не перезапись вслепую`() = testApplication {
        application { module() }
        val t = token(client, "exp6@b.co")
        val project = createProject(client, t, """{"name":"С экспортом"}""")
        client.put("/api/projects/${project.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"githubExportRepo":"vihrea1337/devlog"}""")
        }
        bindToken(client, t)
        val report = makeReport(client, t, project.id)

        GithubExporter.fetchFile = { _, _, _ -> GithubExporter.RemoteFile("# Dev Log\n\nстарое содержимое", "sha-existing") }
        var seenSha: String? = null
        GithubExporter.putFile = { _, _, _, _, sha, _ -> seenSha = sha }

        val r = export(client, t, report.id)
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("sha-existing", seenSha)
    }

    @Test
    fun `сбой записи в GitHub возвращает понятную ошибку, а не 500`() = testApplication {
        application { module() }
        val t = token(client, "exp7@b.co")
        val project = createProject(client, t, """{"name":"С экспортом"}""")
        client.put("/api/projects/${project.id}") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"githubExportRepo":"vihrea1337/devlog"}""")
        }
        bindToken(client, t)
        val report = makeReport(client, t, project.id)

        GithubExporter.putFile = { _, _, _, _, _, _ ->
            throw GithubExporter.GithubExportException("GitHub отказал в записи — нет прав")
        }

        val r = export(client, t, report.id)
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertTrue(r.bodyAsText().contains("нет прав"))
    }

    @Test
    fun `чужой отчёт не экспортируется — 404`() = testApplication {
        application { module() }
        val tA = token(client, "exp8a@b.co")
        val tB = token(client, "exp8b@b.co")
        val project = createProject(client, tA, """{"name":"С экспортом"}""")
        client.put("/api/projects/${project.id}") {
            header(HttpHeaders.Authorization, "Bearer $tA")
            contentType(ContentType.Application.Json)
            setBody("""{"githubExportRepo":"vihrea1337/devlog"}""")
        }
        bindToken(client, tA)
        val report = makeReport(client, tA, project.id)

        val r = export(client, tB, report.id)
        assertEquals(HttpStatusCode.NotFound, r.status)
    }
}
