package io.github.vihrea1337.devlog.android.data

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
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Описание "ручек" REST API DevLog. Retrofit по этому интерфейсу сам сгенерирует код запросов.
 * suspend — функции асинхронные (вызываются из корутины ViewModel).
 *
 * Записи сюда не входят: их создание, правка и удаление идут через офлайн-очередь
 * EntryRepository из общего модуля shared/ (Ktor Client), а не напрямую по сети.
 */
interface DevLogApi {
    /** POST /api/auth/register — создать аккаунт, получить JWT-токен. */
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    /** POST /api/auth/login — войти по email+паролю, получить JWT-токен. */
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    /** GET /api/auth/demo — включён ли на сервере демо-вход (кнопка на экране входа). */
    @GET("api/auth/demo")
    suspend fun demoAvailable(): DemoAvailabilityDto

    /** POST /api/auth/demo — войти в демо-аккаунт «только для просмотра». */
    @POST("api/auth/demo")
    suspend fun demoLogin(): AuthResponse

    /** GET /api/me — кто мы (имя, email, привязанный логин GitHub). */
    @GET("api/me")
    suspend fun me(): UserDto

    /** PUT /api/me — привязать логин GitHub (пустая строка — отвязать). */
    @PUT("api/me")
    suspend fun updateMe(@Body body: UpdateProfile): UserDto

    /** GET /api/projects — проекты пользователя (для фильтра и привязки записей). */
    @GET("api/projects")
    suspend fun getProjects(): List<ProjectDto>

    /** POST /api/entries/{id}/reprocess — заново отправить запись на обработку ИИ (202). */
    @POST("api/entries/{id}/reprocess")
    suspend fun reprocess(@Path("id") id: String): Response<Unit>

    /** GET /api/stats/activity — сколько записей по дням за год + серии дней подряд. */
    @GET("api/stats/activity")
    suspend fun activity(@Query("from") from: String? = null, @Query("to") to: String? = null): ActivityStatsDto

    /** POST /api/import/github — превратить коммиты за период в записи дневника. */
    @POST("api/import/github")
    suspend fun importGithub(@Body body: GithubImportRequest): ImportResultDto

    // --- Отчёты ---

    /** GET /api/reports — все собранные отчёты, свежие сверху (вместе с текстом). */
    @GET("api/reports")
    suspend fun getReports(): List<ReportDto>

    /** GET /api/reports/{id} — один отчёт (для экрана просмотра). */
    @GET("api/reports/{id}")
    suspend fun getReport(@Path("id") id: String): ReportDto

    /**
     * POST /api/reports — собрать отчёт за период. Долгий запрос: если включён ИИ,
     * сервер сначала строит черновик, потом просит модель его причесать.
     */
    @POST("api/reports")
    suspend fun createReport(@Body body: NewReport): ReportDto

    /** PUT /api/reports/{id} — заменить текст отчёта (правка формулировок перед отправкой). */
    @PUT("api/reports/{id}")
    suspend fun updateReport(@Path("id") id: String, @Body body: UpdateReport): ReportDto

    /** DELETE /api/reports/{id} — удалить отчёт (204 = удалён). */
    @DELETE("api/reports/{id}")
    suspend fun deleteReport(@Path("id") id: String): Response<Unit>

    /** POST /api/reports/{id}/share — включить публичную ссылку и получить её адрес. */
    @POST("api/reports/{id}/share")
    suspend fun shareReport(@Path("id") id: String): ShareResponse

    /** DELETE /api/reports/{id}/share — отозвать публичную ссылку (204). */
    @DELETE("api/reports/{id}/share")
    suspend fun unshareReport(@Path("id") id: String): Response<Unit>

    companion object {
        // Адрес боевого бэкенда DevLog (Caddy отдаёт HTTPS на порту 34444). Должен оканчиваться на "/".
        // Если переедем — меняем только эту строку.
        const val BASE_URL = "https://vihreaschedule.duckdns.org:34444/"

        /**
         * Относительная ссылка от сервера («/r/abc») → полный адрес, который можно
         * отправить работодателю в мессенджере.
         */
        fun absoluteUrl(path: String): String = BASE_URL.trimEnd('/') + path
    }
}
