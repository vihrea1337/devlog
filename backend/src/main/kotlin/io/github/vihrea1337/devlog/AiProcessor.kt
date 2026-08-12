package io.github.vihrea1337.devlog

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
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
 * Обращения к ИИ (Groq): структурирование записи и причёсывание отчёта.
 *
 * Очередью и повторами занимается [AiWorker] — здесь только «сходить в модель и вернуть
 * результат». Если что-то пошло не так, метод [structure] кидает исключение с внятным
 * текстом: воркер сохранит его в запись, и пользователь увидит причину, а не просто «ошибка ИИ».
 *
 * Без ключа GROQ_API_KEY обработка выключена ([enabled] = false) — записи остаются в очереди.
 * Ключ берётся из переменной окружения GROQ_API_KEY (прод) или backend/secrets.properties
 * (ключ groq.api.key, файл в .gitignore). В чат/репозиторий ключ не попадает.
 */
object AiProcessor : AiStructurer {
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

    private val apiKey: String =
        (System.getenv("GROQ_API_KEY")?.trim()?.ifEmpty { null } ?: readSecret("groq.api.key")).orEmpty()
    private val model: String =
        System.getenv("GROQ_MODEL")?.trim()?.ifEmpty { null } ?: readSecret("groq.model") ?: "openai/gpt-oss-20b"

    // Тесты/CI принудительно выключают ИИ флагом -Ddevlog.ai.disabled=true, чтобы прогон был
    // детерминированным и не ходил в Groq по сети (иначе фоновая обработка мешает тестам).
    private val forceDisabled: Boolean = System.getProperty("devlog.ai.disabled") == "true"

    /** Включён ли ИИ (есть ключ и не выключен принудительно). Иначе записи остаются "queued". */
    val enabled: Boolean = apiKey.isNotEmpty() && !forceDisabled

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

    override val modelName: String get() = model

    /**
     * Разобрать сырой текст записи на структуру. Кидает исключение с понятным текстом,
     * если ИИ выключен, ответил ошибкой или вернул не тот JSON — воркер запишет причину в запись.
     */
    override suspend fun structure(rawText: String): StructuredDto {
        check(enabled) { "Обработка ИИ выключена на сервере (нет ключа GROQ_API_KEY)" }
        val response = client.post(GROQ_URL) {
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
        }
        if (!response.status.isSuccess()) {
            // Например: кончилась квота Groq, неверный ключ, слишком длинный текст.
            error("Groq ответил ${response.status.value}: ${response.bodyAsText().take(200)}")
        }
        val content = response.body<GroqResponse>().choices.firstOrNull()?.message?.content
            ?: error("Groq вернул пустой ответ")
        return parseStructured(content) ?: error("Не удалось разобрать JSON из ответа модели")
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

    private val reportPrompt = """
        Ты редактор. Ниже — черновик отчёта разработчика о проделанной за период работе.
        Перепиши его в связный, аккуратный отчёт для работодателя на русском в формате Markdown:
        сохрани ВСЕ факты и даты, добавь короткое вступление и итоговый абзац, используй
        заголовки и списки. Ответь ТОЛЬКО Markdown, без пояснений вокруг.
    """.trimIndent()

    /** Причесать черновик отчёта через ИИ. null → используем черновик как есть. */
    suspend fun polishReport(userId: UUID, baselineMarkdown: String): String? {
        if (!enabled || !AiLimiter.tryConsume(userId)) return null
        return try {
            val response: GroqResponse = client.post(GROQ_URL) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(
                    GroqRequest(
                        model = model,
                        messages = listOf(
                            GroqMessage("system", reportPrompt),
                            GroqMessage("user", baselineMarkdown),
                        ),
                        temperature = 0.4,
                    ),
                )
            }.body()
            response.choices.firstOrNull()?.message?.content?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            println("Отчёт: ошибка запроса к Groq — ${e.message}")
            null
        }
    }

    private fun readSecret(key: String): String? {
        val file = File("secrets.properties")
        if (!file.exists()) return null
        return Properties().apply { file.inputStream().use { load(it) } }
            .getProperty(key)?.trim()?.ifEmpty { null }
    }
}
