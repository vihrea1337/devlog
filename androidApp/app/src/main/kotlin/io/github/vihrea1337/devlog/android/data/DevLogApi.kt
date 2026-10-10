package io.github.vihrea1337.devlog.android.data

import io.github.vihrea1337.devlog.android.BuildConfig
import io.github.vihrea1337.devlog.ActivityStatsDto
import io.github.vihrea1337.devlog.AuthResponse
import io.github.vihrea1337.devlog.DemoAvailabilityDto
import io.github.vihrea1337.devlog.GithubImportRequest
import io.github.vihrea1337.devlog.ImportResultDto
import io.github.vihrea1337.devlog.LoginRequest
import io.github.vihrea1337.devlog.NewReport
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.RegisterRequest
import io.github.vihrea1337.devlog.ReportDto
import io.github.vihrea1337.devlog.ShareResponse
import io.github.vihrea1337.devlog.UpdateProfile
import io.github.vihrea1337.devlog.UpdateReport
import io.github.vihrea1337.devlog.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Ручки" REST API DevLog поверх Ktor Client — того же HTTP-клиента, что и синхронизация
 * записей в shared/. Раньше здесь был интерфейс Retrofit (код запросов генерировался по
 * аннотациям), и в приложении жили ДВА HTTP-клиента с разной обработкой ошибок. Теперь
 * один: общий HttpClient настраивается в AppModule (адрес сервера, JSON, разбор ошибок),
 * а этот класс — просто список запросов, каждый в одну строку.
 *
 * Ошибки: на ответ 4xx/5xx клиент бросает [ApiException] (см. AppModule.provideHttpClient),
 * поэтому здесь ни один метод не проверяет код ответа сам.
 *
 * Пути — без "/" в начале: они дописываются к BASE_URL, заданному в defaultRequest.
 * Записи сюда не входят: их создание, правка и удаление идут через офлайн-очередь
 * EntryRepository из shared/, а не напрямую по сети.
 */
@Singleton
class DevLogApi @Inject constructor(private val http: HttpClient) {
    /** POST /api/auth/register — создать аккаунт, получить JWT-токен. */
    suspend fun register(body: RegisterRequest): AuthResponse =
        http.post("api/auth/register") { setBody(body) }.body()

    /** POST /api/auth/login — войти по email+паролю, получить JWT-токен. */
    suspend fun login(body: LoginRequest): AuthResponse =
        http.post("api/auth/login") { setBody(body) }.body()

    /** GET /api/auth/demo — включён ли на сервере демо-вход (кнопка на экране входа). */
    suspend fun demoAvailable(): DemoAvailabilityDto = http.get("api/auth/demo").body()

    /** POST /api/auth/demo — войти в демо-аккаунт «только для просмотра». */
    suspend fun demoLogin(): AuthResponse = http.post("api/auth/demo").body()

    /** GET /api/me — кто мы (имя, email, привязанный логин GitHub). */
    suspend fun me(): UserDto = http.get("api/me").body()

    /** PUT /api/me — привязать логин GitHub (пустая строка — отвязать). */
    suspend fun updateMe(body: UpdateProfile): UserDto =
        http.put("api/me") { setBody(body) }.body()

    /** GET /api/projects — проекты пользователя (для фильтра и привязки записей). */
    suspend fun getProjects(): List<ProjectDto> = http.get("api/projects").body()

    /** POST /api/entries/{id}/reprocess — заново отправить запись на обработку ИИ (202). */
    suspend fun reprocess(id: String) {
        http.post("api/entries/$id/reprocess")
    }

    /** GET /api/stats/activity — сколько записей по дням за год + серии дней подряд. */
    suspend fun activity(from: String? = null, to: String? = null): ActivityStatsDto =
        http.get("api/stats/activity") {
            // null-параметр Ktor просто не добавит в адрес — как и Retrofit раньше.
            parameter("from", from)
            parameter("to", to)
        }.body()

    /** POST /api/import/github — превратить коммиты за период в записи дневника. */
    suspend fun importGithub(body: GithubImportRequest): ImportResultDto =
        http.post("api/import/github") { setBody(body) }.body()

    // --- Отчёты ---

    /** GET /api/reports — все собранные отчёты, свежие сверху (вместе с текстом). */
    suspend fun getReports(): List<ReportDto> = http.get("api/reports").body()

    /** GET /api/reports/{id} — один отчёт (для экрана просмотра). */
    suspend fun getReport(id: String): ReportDto = http.get("api/reports/$id").body()

    /**
     * POST /api/reports — собрать отчёт за период. Долгий запрос: если включён ИИ,
     * сервер сначала строит черновик, потом просит модель его причесать
     * (поэтому у OkHttp в AppModule увеличен таймаут чтения).
     */
    suspend fun createReport(body: NewReport): ReportDto =
        http.post("api/reports") { setBody(body) }.body()

    /** PUT /api/reports/{id} — заменить текст отчёта (правка формулировок перед отправкой). */
    suspend fun updateReport(id: String, body: UpdateReport): ReportDto =
        http.put("api/reports/$id") { setBody(body) }.body()

    /** DELETE /api/reports/{id} — удалить отчёт (204 = удалён, иначе — ApiException). */
    suspend fun deleteReport(id: String) {
        http.delete("api/reports/$id")
    }

    /** POST /api/reports/{id}/share — включить публичную ссылку и получить её адрес. */
    suspend fun shareReport(id: String): ShareResponse = http.post("api/reports/$id/share").body()

    /** DELETE /api/reports/{id}/share — отозвать публичную ссылку (204). */
    suspend fun unshareReport(id: String) {
        http.delete("api/reports/$id/share")
    }

    companion object {
        // Адрес бэкенда DevLog — из настроек сборки (см. app/build.gradle.kts): release всегда
        // смотрит на боевой сервер, debug можно направить на локальный. Оканчивается на "/".
        val BASE_URL: String = BuildConfig.BASE_URL

        /**
         * Относительная ссылка от сервера («/r/abc») → полный адрес, который можно
         * отправить работодателю в мессенджере.
         */
        fun absoluteUrl(path: String): String = BASE_URL.trimEnd('/') + path
    }
}

/**
 * Сервер ответил ошибкой (4xx/5xx). [code] — код HTTP, [serverMessage] — причина из тела
 * ответа (`{"error": "…"}`), если её удалось прочитать. Своё исключение, а не ktor-овское:
 * экранам не нужно знать, каким клиентом сделан запрос, — только код и текст.
 *
 * Наследуется от Exception, а НЕ от IOException: иначе humanMessage() принял бы ответ
 * сервера за обрыв связи и написал «Нет связи с сервером».
 */
class ApiException(val code: Int, val serverMessage: String?) :
    Exception("HTTP $code" + (serverMessage?.let { ": $it" } ?: ""))
