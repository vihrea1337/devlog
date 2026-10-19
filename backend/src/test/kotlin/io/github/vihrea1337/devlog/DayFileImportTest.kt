package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Импорт файлов дня (DayFileImport.kt): разбор разделов кодом, дата из имени файла,
 * обновление записи при повторной загрузке и защита ручных правок. В сеть не ходим —
 * ИИ здесь вообще не участвует (в тестах нет ключа Groq).
 *
 * Примеры файлов — выдуманные, по формату настоящих записей дня.
 */
class DayFileImportTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val dayFile = """
        # 2026-10-19 — Погодное приложение, Android

        ## Сделано
        - **Виджет на главный экран**: показывает температуру и осадки,
          обновляется раз в час.
        - Экран настроек: единицы измерения (°C/°F) и язык.
        - Замечания тестеров, пункты 2–3:
          - прогноз на неделю больше не обрезается справа;
          - иконка дождя видна в тёмной теме.

        ## Проверено
        - Блочные тесты и выпускная сборка — зелёные.
        - На эмуляторе: виджет в светлой и тёмной теме.

        ## Нехватки API и макета
        - API не отдаёт почасовой прогноз дальше 48 часов — передано Ане (№12).

        ## Не делалось и почему
        - Уведомления о грозе — ждут ключа сервиса пушей.

        ## Цифры
        - Тестов к концу дня: 120 — утром было 104.
        - Коммитов за день: 5.
        - Сборок для тестеров: 1 (26.10.19).
        - Передано Ане: нехватка №12.
    """.trimIndent()

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:h2:mem:devlog_dayfiles;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(Users, GithubIntegrations, GitlabIntegrations, Projects, Entries, EntryStructured) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(EntryStructured, Entries, Projects, GitlabIntegrations, GithubIntegrations, Users) }
    }

    // --- Разбор (чистые функции) ---

    @Test
    fun `разделы разбираются, перенесённые строки и вложенный список — часть пункта`() {
        val p = DayFileImporter.parse(dayFile)

        assertEquals("2026-10-19 — Погодное приложение, Android", p.title)
        val done = p.items(DayFileImporter.Kind.DONE)
        assertEquals(3, done.size, "три пункта «Сделано»: $done")
        assertTrue(done[0].endsWith("обновляется раз в час."), "перенос строки склеен: ${done[0]}")
        assertTrue(done[2].contains("прогноз на неделю") && done[2].contains("иконка дождя"), "вложенный список — в пункте: ${done[2]}")
        assertEquals(2, p.items(DayFileImporter.Kind.CHECKED).size)
        assertEquals(1, p.items(DayFileImporter.Kind.GAPS).size)
        assertEquals(1, p.items(DayFileImporter.Kind.NOT_DONE).size)
        assertEquals(4, p.items(DayFileImporter.Kind.NUMBERS).size)
    }

    @Test
    fun `перенос, похожий на нумерованный пункт, остаётся продолжением`() {
        val text = """
            ## Сделано
            - Сверка с макетом на двух ширинах (412 и
              360) в светлой теме.
        """.trimIndent()
        val done = DayFileImporter.parse(text).items(DayFileImporter.Kind.DONE)
        assertEquals(listOf("Сверка с макетом на двух ширинах (412 и 360) в светлой теме."), done)
    }

    @Test
    fun `второй заголовок посреди файла — граница частей, а не пункт`() {
        val text = """
            # 16.10.2026 — день
            ## Сделано
            - утром: план
            ## Цифры
            - Тестов: 10.

            # 16.10.2026 (вечер) — второй заход
            ## Сделано
            - вечером: экран
            **Подпись группы**
            - ещё экран
            ## Цифры (вечер)
            - Тестов: 10 → 14.
            - **Сборка для тестеров** выложена, список проверки — 18 разделов.
        """.trimIndent()
        val p = DayFileImporter.parse(text)

        assertEquals("16.10.2026 — день", p.title)
        val done = p.items(DayFileImporter.Kind.DONE)
        assertEquals(listOf("утром: план", "вечером: экран", "ещё экран", "**Сборка для тестеров** выложена, список проверки — 18 разделов."), done,
            "жирная подпись группы — не пункт; рассказ из «Цифр» переехал в «Сделано»")
        assertEquals(listOf("Тестов: 10.", "Тестов: 10 → 14."), p.items(DayFileImporter.Kind.NUMBERS))
    }

    @Test
    fun `дата — из имени файла, иначе из заголовка в любом из двух форматов`() {
        assertEquals(LocalDate.parse("2026-10-19"), DayFileImporter.dateFor("2026-10-19.md", "# 01.01.2020 — чужая дата"))
        assertEquals(LocalDate.parse("2026-10-14"), DayFileImporter.dateFor("заметки.md", "# 14.10.2026 (вечер) — день"))
        assertEquals(LocalDate.parse("2026-10-15"), DayFileImporter.dateFor("заметки.md", "# 2026-10-15 — день"))
        assertNull(DayFileImporter.dateFor("заметки.md", "без заголовка\nсборка 26.10.19"), "даты из глубины текста не берём")
        assertNull(DayFileImporter.dateFor("2026-02-30.md", "# текст"), "несуществующая дата не принимается")
    }

    @Test
    fun `суть — начала первых пунктов, без обрезки посреди скобок`() {
        assertEquals("Виджет на главный экран", DayFileImporter.shortHead("**Виджет на главный экран**: показывает температуру"))
        assertEquals(
            "Забраны 6 коммитов коллеги (сайт, API; ядро)",
            DayFileImporter.shortHead("Забраны 6 коммитов коллеги (сайт, API; ядро); дальше подробности"),
        )
        val s = DayFileImporter.toStructured(DayFileImporter.parse(dayFile))!!
        assertTrue(s.summary.startsWith("Виджет на главный экран; Экран настроек"), s.summary)
        assertEquals(3, s.steps.size)
        assertEquals(1, s.problems.size, "нехватки — в проблемах")
        assertTrue(s.outcome.contains("Коммитов за день: 5"), "цифры — в итоге: ${s.outcome}")
    }

    @Test
    fun `без раздела «Сделано» разбирать нечего — пусть разберёт ИИ`() {
        assertNull(DayFileImporter.toStructured(DayFileImporter.parse("просто заметка без разделов")))
    }

    // --- Импорт через HTTP ---

    private suspend fun token(client: HttpClient, email: String): String {
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"secret1","displayName":"U"}""")
        }
        return json.decodeFromString<AuthResponse>(r.bodyAsText()).token
    }

    private suspend fun importFiles(
        client: HttpClient,
        token: String,
        files: List<DayFileDto>,
        projectId: String? = null,
        overwrite: Boolean = false,
    ): HttpResponse = client.post("/api/import/day-files") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(DayFilesImportRequest.serializer(), DayFilesImportRequest(files, projectId, overwrite)))
    }

    private suspend fun importOk(client: HttpClient, token: String, files: List<DayFileDto>, projectId: String? = null, overwrite: Boolean = false) =
        importFiles(client, token, files, projectId, overwrite).let {
            assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText())
            json.decodeFromString<DayFilesImportResultDto>(it.bodyAsText())
        }

    private suspend fun feed(client: HttpClient, token: String): List<EntryDto> = json.decodeFromString(
        client.get("/api/entries") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText(),
    )

    private suspend fun newProject(client: HttpClient, token: String, aiEnabled: Boolean): String {
        val r = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Работа","aiEnabled":$aiEnabled}""")
        }
        return json.decodeFromString<ProjectDto>(r.bodyAsText()).id
    }

    @Test
    fun `файл дня становится записью, разобранной кодом без ИИ`() = testApplication {
        application { module() }
        val t = token(client, "day1@b.co")

        val result = importOk(client, t, listOf(DayFileDto("2026-10-19.md", dayFile)))

        assertEquals(1, result.created)
        assertTrue(result.files.single().parsedByCode)
        val entry = feed(client, t).single()
        assertEquals("2026-10-19", entry.occurredOn)
        assertEquals("day-file", entry.source)
        assertEquals("structured", entry.status, "разобрано кодом — в очередь ИИ не ставим")
        assertEquals(3, entry.structured!!.steps.size)
        assertEquals(dayFile, entry.rawText, "исходный файл целиком — для сверки")
    }

    @Test
    fun `повторная загрузка того же файла ничего не меняет, дописанного — обновляет запись`() = testApplication {
        application { module() }
        val t = token(client, "day2@b.co")
        importOk(client, t, listOf(DayFileDto("2026-10-19.md", dayFile)))

        val same = importOk(client, t, listOf(DayFileDto("2026-10-19.md", dayFile)))
        assertEquals("unchanged", same.files.single().status)

        val extended = dayFile.replace("## Проверено", "- Тёмная тема для графика осадков.\n\n## Проверено")
        val upd = importOk(client, t, listOf(DayFileDto("2026-10-19.md", extended)))
        assertEquals("updated", upd.files.single().status)

        val entries = feed(client, t)
        assertEquals(1, entries.size, "один день — одна запись, без дублей")
        assertEquals(4, entries.single().structured!!.steps.size, "разбор пересчитан по новому тексту")
    }

    @Test
    fun `запись, поправленную руками, импорт не затирает — только по флагу «Перезаписать»`() = testApplication {
        application { module() }
        val t = token(client, "day3@b.co")
        importOk(client, t, listOf(DayFileDto("2026-10-19.md", dayFile)))
        val id = feed(client, t).single().id
        client.put("/api/entries/$id") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"rawText":"поправил руками"}""")
        }

        val newer = dayFile + "\n- Ещё одна цифра: 1."
        val r = importOk(client, t, listOf(DayFileDto("2026-10-19.md", newer)))
        assertEquals("edited", r.files.single().status)
        assertEquals("поправил руками", feed(client, t).single().rawText)

        val forced = importOk(client, t, listOf(DayFileDto("2026-10-19.md", newer)), overwrite = true)
        assertEquals("updated", forced.files.single().status)
        assertEquals(newer, feed(client, t).single().rawText)
    }

    @Test
    fun `правка и «Переобработать» у файла дня — разбор кодом заново, без очереди ИИ`() = testApplication {
        application { module() }
        val t = token(client, "day9@b.co")
        importOk(client, t, listOf(DayFileDto("2026-10-19.md", dayFile)))
        val id = feed(client, t).single().id

        val edited = dayFile.replace("- Экран настроек", "- Экран настроек поправлен\n- Новый пункт")
        client.put("/api/entries/$id") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UpdateEntry.serializer(), UpdateEntry(rawText = edited)))
        }
        val afterEdit = feed(client, t).single()
        assertEquals("structured", afterEdit.status, "в очередь ИИ не ушла")
        assertEquals(4, afterEdit.structured!!.steps.size, "разбор — по новому тексту")

        val r = client.post("/api/entries/$id/reprocess") { header(HttpHeaders.Authorization, "Bearer $t") }
        assertEquals(HttpStatusCode.Accepted, r.status)
        assertEquals("structured", feed(client, t).single().status)
    }

    @Test
    fun `удалённую запись импорт не воскрешает без флага`() = testApplication {
        application { module() }
        val t = token(client, "day4@b.co")
        importOk(client, t, listOf(DayFileDto("2026-10-19.md", dayFile)))
        val id = feed(client, t).single().id
        client.delete("/api/entries/$id") { header(HttpHeaders.Authorization, "Bearer $t") }

        assertEquals("deleted", importOk(client, t, listOf(DayFileDto("2026-10-19.md", dayFile))).files.single().status)
        assertTrue(feed(client, t).isEmpty())

        assertEquals("updated", importOk(client, t, listOf(DayFileDto("2026-10-19.md", dayFile)), overwrite = true).files.single().status)
        assertEquals(1, feed(client, t).size, "по флагу — восстановлена")
    }

    @Test
    fun `ошибки по отдельным файлам не мешают остальным`() = testApplication {
        application { module() }
        val t = token(client, "day5@b.co")

        val r = importOk(
            client, t,
            listOf(
                DayFileDto("2026-10-18.md", dayFile.replace("2026-10-19", "2026-10-18")),
                DayFileDto("заметки.md", "без даты"),
                DayFileDto("2026-10-18 копия.md", dayFile),
                DayFileDto("2026-10-17.md", "   "),
                DayFileDto("2026-10-16.md", "x".repeat(MAX_RAW_TEXT_LENGTH + 1)),
            ),
        )

        assertEquals(1, r.created)
        assertEquals(4, r.failed)
        assertEquals(listOf("created", "error", "error", "error", "error"), r.files.map { it.status })
        assertTrue(r.files[2].message!!.contains("Второй файл"), "дубль даты объяснён")
    }

    @Test
    fun `файл без разделов уходит обычным путём — в черновик у проекта без ИИ`() = testApplication {
        application { module() }
        val t = token(client, "day6@b.co")
        val secret = newProject(client, t, aiEnabled = false)
        val open = newProject(client, t, aiEnabled = true)

        importOk(client, t, listOf(DayFileDto("2026-10-01.md", "просто текст дня")), projectId = secret)
        importOk(client, t, listOf(DayFileDto("2026-10-01.md", "просто текст дня")), projectId = open)

        val entries = feed(client, t)
        assertEquals(2, entries.size, "проект — часть ключа: один день в двух проектах — две записи")
        assertEquals("draft", entries.single { it.projectId == secret }.status, "ИИ выключен — в Groq не отправляем")
        assertEquals("queued", entries.single { it.projectId == open }.status)
        assertFalse(entries.any { it.structured != null })
    }

    @Test
    fun `чужой проект и пустой список не принимаются`() = testApplication {
        application { module() }
        val owner = token(client, "day7@b.co")
        val stranger = token(client, "day8@b.co")
        val project = newProject(client, owner, aiEnabled = true)

        assertEquals(HttpStatusCode.BadRequest, importFiles(client, stranger, listOf(DayFileDto("2026-10-19.md", dayFile)), project).status)
        assertEquals(HttpStatusCode.BadRequest, importFiles(client, owner, emptyList()).status)
    }
}
