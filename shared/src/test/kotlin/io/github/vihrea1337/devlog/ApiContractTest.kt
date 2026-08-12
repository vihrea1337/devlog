package io.github.vihrea1337.devlog

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Страховка контракта: имена полей в JSON — часть договора с уже установленными
 * клиентами. Переименование поля в Kotlin молча ломает старое приложение,
 * поэтому оно должно ронять этот тест.
 */
class ApiContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    // encodeDefaults = true — чтобы увидеть в JSON и те поля, что равны значениям
    // по умолчанию: проверяем именно НАЗВАНИЯ полей. Сам сервер их не шлёт
    // (экономит трафик), а клиенты подставляют значения по умолчанию сами.
    private val jsonWithDefaults = Json { encodeDefaults = true }

    @Test
    fun `имена полей записи не менялись`() {
        val entry = EntryDto(
            id = "e1",
            projectId = "p1",
            occurredOn = "2026-08-12",
            rawText = "чинил сборку",
            source = "manual",
            status = "structured",
            timeSpentMin = 45,
            createdAt = "2026-08-12T10:00:00Z",
            updatedAt = "2026-08-12T10:05:00Z",
            structured = StructuredDto(summary = "починил"),
            aiError = null,
            deleted = false,
        )

        val text = jsonWithDefaults.encodeToString(EntryDto.serializer(), entry)

        for (field in listOf(
            "\"id\"", "\"projectId\"", "\"occurredOn\"", "\"rawText\"", "\"source\"",
            "\"status\"", "\"timeSpentMin\"", "\"createdAt\"", "\"updatedAt\"",
            "\"structured\"", "\"deleted\"",
        )) {
            assertTrue(text.contains(field), "поле $field пропало из JSON: $text")
        }
    }

    @Test
    fun `старый ответ сервера разбирается новым клиентом`() {
        // Так выглядел ответ до появления aiError и deleted: новые поля должны
        // подставиться по умолчанию, а не уронить разбор.
        val old = """
            {"id":"e1","occurredOn":"2026-08-12","rawText":"текст","source":"manual",
             "status":"queued","createdAt":"2026-08-12T10:00:00Z","updatedAt":"2026-08-12T10:00:00Z"}
        """.trimIndent()

        val entry = json.decodeFromString(EntryDto.serializer(), old)

        assertEquals("e1", entry.id)
        assertNull(entry.aiError)
        assertEquals(false, entry.deleted)
        assertNull(entry.projectId)
    }

    @Test
    fun `лишние поля от нового сервера не ломают старого клиента`() {
        val fromFutureServer = """
            {"id":"p1","name":"SkyCast","color":"#3b6ef5","aiEnabled":true,"archived":false,
             "createdAt":"2026-08-01T00:00:00Z","совершенноНовоеПоле":42}
        """.trimIndent()

        val project = json.decodeFromString(ProjectDto.serializer(), fromFutureServer)

        assertEquals("SkyCast", project.name)
        assertTrue(project.aiEnabled)
    }

    @Test
    fun `событие живых обновлений сериализуется как ждёт браузер`() {
        val text = jsonWithDefaults.encodeToString(EntryEvent.serializer(), EntryEvent("e1", "structured"))
        assertEquals("""{"entryId":"e1","status":"structured"}""", text)
    }
}
