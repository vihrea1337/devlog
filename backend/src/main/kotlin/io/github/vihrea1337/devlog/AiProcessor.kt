package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Properties
import java.util.UUID

// --- Модели запроса/ответа Groq (OpenAI-совместимый chat completions) ---
@Serializable
private data class GroqRequest(
    val model: String,
    val messages: List<GroqMessage>,
    val temperature: Double = 0.2,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)

@Serializable
private data class GroqMessage(val role: String, val content: String)

@Serializable
private data class ResponseFormat(val type: String)

@Serializable
private data class GroqResponse(val choices: List<GroqChoice> = emptyList())

@Serializable
private data class GroqChoice(val message: GroqMessage)

/**
 * Фоновая обработка записей ИИ (Groq): из сырого текста делает структуру
 * (суть/шаги/решения/проблемы/итог/теги) и кладёт в entry_structured.
 *
 * ЗАГОТОВКА: без ключа GROQ_API_KEY обработка не запускается — запись остаётся в статусе
 * "queued". Ключ вставляется позже: переменная окружения GROQ_API_KEY (прод) или
 * backend/secrets.properties (ключ groq.api.key, файл уже в .gitignore). В чат/репозиторий
 * ключ не попадает.
 */
object AiProcessor {
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

    private val apiKey: String =
        (System.getenv("GROQ_API_KEY")?.trim()?.ifEmpty { null } ?: readSecret("groq.api.key")).orEmpty()
    private val model: String =
        System.getenv("GROQ_MODEL")?.trim()?.ifEmpty { null } ?: readSecret("groq.model") ?: "openai/gpt-oss-20b"

    /** Включён ли ИИ (есть ли ключ). Нет ключа → записи остаются "queued". */
    val enabled: Boolean = apiKey.isNotEmpty()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parseJson = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

    private val systemPrompt = """
        Ты помощник, который превращает сырую заметку разработчика о проделанной за день
        работе в структуру. Верни СТРОГО JSON-объект (без markdown и пояснений) с полями:
        "summary" (строка, суть одной фразой), "steps" (массив строк — что сделано),
        "decisions" (массив строк — принятые решения и почему),
        "problems" (массив строк — проблемы и как решены), "outcome" (строка — итог),
        "tags" (массив строк — короткие теги: технологии, темы).
        Пиши по-русски и кратко. Чего нет — пустой массив или пустая строка.
    """.trimIndent()

    init {
        println(
            if (enabled) "ИИ-обработка включена (Groq, модель $model)."
            else "ИИ-обработка выключена: нет GROQ_API_KEY — записи остаются в статусе 'queued'.",
        )
    }

    /** Запланировать обработку записи в фоне (результат не ждём). */
    fun schedule(userId: UUID, entryId: UUID, rawText: String) {
        if (!enabled || rawText.isBlank()) return
        scope.launch {
            EntryRepository.setStatus(userId, entryId, "processing")
            val structured = runCatching { requestStructured(rawText) }.getOrNull()
            if (structured != null) {
                EntryStructuredRepository.save(entryId, structured, model)
                EntryRepository.setStatus(userId, entryId, "structured")
            } else {
                EntryRepository.setStatus(userId, entryId, "failed")
            }
        }
    }

    private suspend fun requestStructured(rawText: String): StructuredDto? {
        val response: GroqResponse = client.post(GROQ_URL) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(
                GroqRequest(
                    model = model,
                    messages = listOf(
                        GroqMessage("system", systemPrompt),
                        GroqMessage("user", rawText),
                    ),
                    responseFormat = ResponseFormat("json_object"),
                ),
            )
        }.body()
        val content = response.choices.firstOrNull()?.message?.content ?: return null
        return parseStructured(content)
    }

    /**
     * Достать StructuredDto из ответа модели: в тексте — JSON-объект (иногда обёрнутый
     * пояснениями или ```-блоком), берём от первой '{' до последней '}'.
     * Функция чистая (без сети) — удобно юнит-тестировать.
     */
    fun parseStructured(content: String): StructuredDto? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val jsonText = content.substring(start, end + 1)
        return runCatching { parseJson.decodeFromString<StructuredDto>(jsonText) }.getOrNull()
    }

    private fun readSecret(key: String): String? {
        val file = File("secrets.properties")
        if (!file.exists()) return null
        return Properties().apply { file.inputStream().use { load(it) } }
            .getProperty(key)?.trim()?.ifEmpty { null }
    }
}
