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
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.util.Properties
import java.util.UUID

// --- Модели запроса/ответа Groq (OpenAI-совместимый chat completions) ---
@Serializable
private data class GroqRequest(
    val model: String,
    val messages: List<GroqMessage>,
    val temperature: Double = 0.2,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    // Модели gpt-oss "думают" перед ответом, и эти скрытые рассуждения тоже тратят лимит
    // токенов в минуту. "medium" — и для разбора заметки, и для отчёта (на "high" ответ съедают рассуждения).
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
)

@Serializable
private data class GroqMessage(val role: String, val content: String? = null)

@Serializable
private data class ResponseFormat(val type: String)

@Serializable
private data class GroqResponse(val choices: List<GroqChoice> = emptyList())

@Serializable
private data class GroqChoice(
    val message: GroqMessage,
    // Почему модель остановилась: "stop" — дописала, "length" — упёрлась в max_completion_tokens.
    @SerialName("finish_reason") val finishReason: String? = null,
)

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

    // Модель для отчётов — отдельная и покрупнее: отчёт пишется редко (раз в неделю/месяц),
    // а качество текста здесь важнее всего. Плюс у каждой модели Groq свой поминутный лимит,
    // так что сборка отчёта не конкурирует с фоновым разбором записей.
    private val reportModel: String =
        System.getenv("GROQ_REPORT_MODEL")?.trim()?.ifEmpty { null } ?: readSecret("groq.report.model")
            ?: "openai/gpt-oss-120b"

    // Тесты/CI принудительно выключают ИИ флагом -Ddevlog.ai.disabled=true, чтобы прогон был
    // детерминированным и не ходил в Groq по сети (иначе фоновая обработка мешает тестам).
    private val forceDisabled: Boolean = System.getProperty("devlog.ai.disabled") == "true"

    /** Включён ли ИИ (есть ключ и не выключен принудительно). Иначе записи остаются "queued". */
    val enabled: Boolean = apiKey.isNotEmpty() && !forceDisabled

    private val parseJson = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 90_000 } // отчёт на крупной модели может думать долго
    }

    private val systemPrompt = """
        Ты помогаешь разработчику вести рабочий дневник. На входе — его сырая заметка за день:
        как попало, с сокращениями и опечатками. Разбери её в структуру и верни СТРОГО
        JSON-объект (без markdown и пояснений) с полями:
        "summary" — суть дня одной фразой (до 15 слов), что сделано по существу;
        "steps" — массив: что сделано. Каждый пункт — законченное действие с конкретикой
          (технологии, названия, числа из заметки). Объединяй мелочи, не дроби;
        "decisions" — массив: принятые решения В ФОРМЕ «что выбрал — почему» (если причина есть в заметке);
        "problems" — массив: проблемы В ФОРМЕ «что сломалось → в чём была причина → как решил».
          Только то, что РЕАЛЬНО сломалось. Меры предосторожности («если сервис упадёт — отдаём
          черновик») и защитные проверки («сервер не стартует с дефолтным секретом») — это
          решения (decisions), а не проблемы;
        "outcome" — итог дня одной-двумя фразами: что теперь работает / что это дало;
        "tags" — 3–6 коротких тегов в нижнем регистре: технологии и темы.
        Правила: пиши грамотно по-русски (термины и названия — как в заметке, английские слова
        не склоняй русскими окончаниями), от первого лица («перевёл», «починил»), без канцелярита.
        Ничего не выдумывай: чего нет в заметке — пустой массив или пустая строка. Планы
        («надо сделать») — не шаги: их место в outcome как «дальше: …». Решения и проблемы
        не дублируй в steps.
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
        return try {
            requestStructure(rawText, strictJson = true)
        } catch (e: JsonModeRejected) {
            // В строгом JSON-режиме Groq сам проверяет ответ модели и на битом JSON отвечает
            // 400 json_validate_failed (бывает на длинных заметках). Повторяем без строгого
            // режима: JSON из обычного ответа достанет parseStructured — он к этому готов.
            requestStructure(rawText, strictJson = false)
        }
    }

    /** Groq отверг ответ модели в строгом JSON-режиме (400 json_validate_failed). */
    private class JsonModeRejected : RuntimeException()

    private suspend fun requestStructure(rawText: String, strictJson: Boolean): StructuredDto {
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
                    responseFormat = if (strictJson) ResponseFormat("json_object") else null,
                    // "medium": на "low" маленькая модель писала коряво («dedupировал», опечатки),
                    // а эти сводки видны в ленте и хронологии отчёта. Лимит 429 воркер переживает.
                    reasoningEffort = "medium",
                ),
            )
        }
        if (response.status.value == 429) throw rateLimited(response.headers["retry-after"], response.bodyAsText())
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            if (strictJson && response.status.value == 400 && "json_validate_failed" in body) throw JsonModeRejected()
            // Например: кончилась квота Groq, неверный ключ, слишком длинный текст.
            error("Groq ответил ${response.status.value}: ${body.take(200)}")
        }
        val choice = response.body<GroqResponse>().choices.firstOrNull()
        val content = choice?.message?.content?.takeIf { it.isNotBlank() }
            ?: error("Groq вернул пустой ответ (finish_reason=${choice?.finishReason})")
        // В тексте ошибки — почему модель остановилась и чем начинался ответ: эта строка
        // видна пользователю в карточке записи и в логах, по ней сразу понятно, что не так.
        return parseStructured(content) ?: error(
            "Не удалось разобрать JSON из ответа модели (finish_reason=${choice.finishReason}, " +
                "начало ответа: ${content.take(120).replace('\n', ' ')})",
        )
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

    /**
     * Промпт отчёта. Главное отличие от прежнего «перепиши черновик»: раньше ИИ получал
     * список шагов ПО ДНЯМ с требованием «сохрани все факты и даты» — и честно возвращал тот же
     * список по дням, только с заголовками. Работодателю так читать тяжело: он хочет понять,
     * ЧТО получилось, какие решения принимались и какие проблемы снимались, а не дневник.
     * Теперь ИИ получает полную структуру записей (с решениями и проблемами) и пишет
     * аналитический текст ПО ТЕМАМ. Цифры периода и хронологию дописывает код (ReportGenerator),
     * чтобы модель не придумывала числа.
     */
    private val reportPrompt = """
        Ты — опытный тимлид и редактор. По рабочим записям разработчика за период напиши
        отчёт для работодателя/руководителя. Читатель — технически грамотный, но занятой
        человек: за минуту он должен понять, что получилось, и при желании — как и почему.

        Верни ТОЛЬКО Markdown (без пояснений вокруг) строго с такими разделами:

        ## Кратко
        2–4 предложения связным текстом: на чём был фокус периода и главный результат.
        Без списков.

        ## Главные результаты
        3–7 пунктов. СГРУППИРУЙ работу ПО ТЕМАМ (направлениям), объединяя записи разных дней
        об одном и том же. Каждый пункт: **результат жирным** — затем 1–2 предложения: что
        конкретно сделано и что это даёт (пользователю, команде, надёжности).

        ## Ключевые решения
        3–7 САМЫХ ВАЖНЫХ решений (архитектура, надёжность, безопасность — а не мелочи).
        Пункты вида: **решение** — почему так (альтернатива, если была в записях).

        ## Сложные проблемы
        Пункты вида: **проблема** — причина → как решено. Только нетривиальные и РЕШЁННЫЕ;
        нерешённое и отложенное — только в «Что дальше».

        ## Что дальше
        Только если в записях есть недоделки, найденные недочёты или планы. Иначе раздел не пиши.

        Правила:
        - Каждое утверждение должно опираться на конкретную запись. Сомневаешься — не пиши.
          Не добавляй последствий, которых в записях нет («без простоев», «сервис не отвечал»).
        - Записи идут по времени, и поздние важнее ранних: если в более поздней записи сказано,
          что недочёт исправлен, — он идёт в результаты, а НЕ в «Что дальше».
        - Groq — это сервис, а gpt-oss-20b/120b — модели на нём; не пиши «Groq заменён на модель».
        - НЕ пересказывай по дням и не начинай пункты с дат — хронологию добавят отдельно.
        - Никаких оценок и обещаний, которых нет в записях: «надёжный», «безопасный»,
          «готово к масштабированию», «стабильно работает». Эффект пиши, только если он
          прямо следует из записи; если не следует — просто опиши, что сделано.
        - Точно передавай причины и следствия: если баг «терял» записи — не пиши
          «дублировал»; если импорт берёт коммиты — не называй его «восстановлением данных».
        - Причину решения пиши, ТОЛЬКО если она есть в записях. Нет причины — просто назови
          решение, не додумывай «потому что» и не придумывай альтернатив.
        - Планы и выводы («надо настроить», «стоит сделать») — это НЕ сделанная работа:
          им место только в «Что дальше».
        - Защитная проверка — не баг: «сервер не стартует с дефолтным секретом» — это добавленная
          защита, а не проблема.
        - Английские термины не склоняй русскими окончаниями («dedupировал» — нельзя, пиши
          «убрал дубли»).
        - Ничего не выдумывай: только факты из записей. Сохраняй конкретику — названия
          технологий, числа, имена файлов и сервисов.
        - Не повторяй один и тот же факт в разных разделах.
        - Пиши по-русски, от первого лица ЕДИНСТВЕННОГО числа («перевёл», «нашёл и починил»;
          не «мы»), живо и по делу,
          без канцелярита («было осуществлено», «произведена работа по»).
        - Заголовок отчёта и период не пиши — они уже есть.
    """.trimIndent()

    /**
     * Написать аналитическую часть отчёта по подготовленным данным [entriesDigest]
     * (см. ReportGenerator.digest). null → ИИ недоступен, ReportGenerator соберёт текст сам.
     *
     * Сначала — модель для отчётов (покрупнее); если она недоступна (лимит, ошибка) —
     * пробуем основную. На 429 с коротким ожиданием (до 20 с) подождём и повторим один раз:
     * пользователь ждёт ответа на кнопку, но 20 секунд лучше, чем отчёт-черновик.
     */
    suspend fun writeReport(userId: UUID, entriesDigest: String): String? {
        if (!enabled || !AiLimiter.tryConsume(userId)) return null
        for (m in listOf(reportModel, model).distinct()) {
            for (attempt in 0..1) {
                try {
                    val body = requestReport(m, entriesDigest)
                    if (body != null) return body
                    break // модель ответила, но не по формату — пробуем следующую
                } catch (e: AiRateLimitedException) {
                    if (attempt == 0 && e.retryAfter.seconds < 20) {
                        delay(e.retryAfter.toMillis() + 500)
                    } else {
                        println("Отчёт: лимит Groq на модели $m — ${e.message}")
                        break
                    }
                } catch (e: Exception) {
                    println("Отчёт: ошибка запроса к Groq ($m) — ${e.message}")
                    break
                }
            }
        }
        return null
    }

    private suspend fun requestReport(modelName: String, digest: String): String? {
        val response = client.post(GROQ_URL) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(
                GroqRequest(
                    model = modelName,
                    messages = listOf(
                        GroqMessage("system", reportPrompt),
                        GroqMessage("user", digest),
                    ),
                    temperature = 0.2, // ниже — меньше «творчества», а в отчёте нужна точность
                    // "medium", а не "high": на high модель тратит весь бюджет ответа на скрытые
                    // рассуждения и возвращает пустой текст (finish_reason=length) — проверено.
                    reasoningEffort = "medium",
                    // Бюджет ответа (рассуждения + текст). Groq засчитывает его в лимит 8000
                    // токенов в минуту ВМЕСТЕ с запросом: 6000 + данные отчёта давали 413
                    // «Request too large». 3500 + digest (до ~3500 токенов) — с запасом влезают.
                    maxCompletionTokens = 3500,
                ),
            )
        }
        if (response.status.value == 429) throw rateLimited(response.headers["retry-after"], response.bodyAsText())
        if (!response.status.isSuccess()) error("Groq ответил ${response.status.value}: ${response.bodyAsText().take(200)}")
        val choice = response.body<GroqResponse>().choices.firstOrNull()
        val body = cleanReportBody(choice?.message?.content)
        if (body == null) {
            // Ответ пришёл, но не годится — пишем в лог, ПОЧЕМУ (иначе отчёт молча
            // откатится к запасному варианту и причину не найти).
            println(
                "Отчёт: модель $modelName вернула неподходящий ответ (finish_reason=${choice?.finishReason}): " +
                    choice?.message?.content.orEmpty().take(200).replace('\n', ' '),
            )
        }
        return body
    }

    /**
     * Привести ответ модели к телу отчёта: снять обёртку ```markdown ... ``` (модели иногда
     * её добавляют) и выкинуть заголовок первого уровня, если модель всё-таки его написала, —
     * заголовок ставит ReportGenerator. Пусто или нет ни одного раздела «##» → null (не годится).
     * Функция чистая — покрыта юнит-тестом.
     */
    fun cleanReportBody(raw: String?): String? {
        var text = raw?.trim() ?: return null
        if (text.startsWith("```")) {
            text = text.substringAfter('\n').substringBeforeLast("```").trim()
        }
        text = text.lineSequence().dropWhile { it.isBlank() || it.startsWith("# ") }.joinToString("\n").trim()
        return text.takeIf { "## " in it }
    }

    /**
     * Сколько ждать после 429. Groq присылает заголовок retry-after (секунды), а в тексте
     * ошибки — «Please try again in 1.234s» или «in 2m3.5s». Берём что найдётся; иначе 10 с.
     */
    fun parseRetryAfter(header: String?, body: String): Duration {
        header?.trim()?.toDoubleOrNull()?.let { return Duration.ofMillis((it * 1000).toLong()) }
        val m = Regex("""try again in (?:(\d+)m)?([\d.]+)s""").find(body)
        if (m != null) {
            val minutes = m.groupValues[1].toLongOrNull() ?: 0
            val seconds = m.groupValues[2].toDoubleOrNull() ?: 0.0
            return Duration.ofMillis(minutes * 60_000 + (seconds * 1000).toLong())
        }
        return Duration.ofSeconds(10)
    }

    private fun rateLimited(header: String?, body: String) =
        AiRateLimitedException(parseRetryAfter(header, body), "Groq: превышен лимит запросов (429)")

    private fun readSecret(key: String): String? {
        val file = File("secrets.properties")
        if (!file.exists()) return null
        return Properties().apply { file.inputStream().use { load(it) } }
            .getProperty(key)?.trim()?.ifEmpty { null }
    }
}
