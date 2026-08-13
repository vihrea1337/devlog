package io.github.vihrea1337.devlog.android.data

import io.github.vihrea1337.devlog.AuthResponse
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.LoginRequest
import io.github.vihrea1337.devlog.NewEntry
import io.github.vihrea1337.devlog.NewReport
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.RegisterRequest
import io.github.vihrea1337.devlog.ReportDto
import io.github.vihrea1337.devlog.ShareResponse
import io.github.vihrea1337.devlog.UpdateReport
import io.github.vihrea1337.devlog.UserDto
import io.github.vihrea1337.devlog.android.TokenStore
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
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
 */
interface DevLogApi {
    /** POST /api/auth/register — создать аккаунт, получить JWT-токен. */
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    /** POST /api/auth/login — войти по email+паролю, получить JWT-токен. */
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    /** GET /api/me — проверить токен и узнать, кто мы. */
    @GET("api/me")
    suspend fun me(): UserDto

    /** GET /api/entries — лента записей текущего пользователя (можно сузить до проекта). */
    @GET("api/entries")
    suspend fun getEntries(@Query("projectId") projectId: String? = null): List<EntryDto>

    /** GET /api/projects — проекты пользователя (для фильтра и привязки записей). */
    @GET("api/projects")
    suspend fun getProjects(): List<ProjectDto>

    /** POST /api/entries — добавить запись; сервер вернёт её с id и статусом "queued". */
    @POST("api/entries")
    suspend fun addEntry(@Body body: NewEntry): EntryDto

    /** DELETE /api/entries/{id} — удалить запись (204 = удалено). */
    @DELETE("api/entries/{id}")
    suspend fun deleteEntry(@Path("id") id: String): Response<Unit>

    /** POST /api/entries/{id}/reprocess — заново отправить запись на обработку ИИ (202). */
    @POST("api/entries/{id}/reprocess")
    suspend fun reprocess(@Path("id") id: String): Response<Unit>

    // --- Отчёты ---

    /** GET /api/reports — все собранные отчёты, свежие сверху (вместе с текстом). */
    @GET("api/reports")
    suspend fun getReports(): List<ReportDto>

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
}

/**
 * Единая точка создания клиента Retrofit. by lazy — создаём его один раз при первом обращении.
 */
object ApiClient {
    // Адрес боевого бэкенда DevLog (Caddy отдаёт HTTPS на порту 34444). Должен оканчиваться на "/".
    // Если переедем — меняем только эту строку.
    const val BASE_URL = "https://vihreaschedule.duckdns.org:34444/"

    /**
     * Относительная ссылка от сервера («/r/abc») → полный адрес, который можно
     * отправить работодателю в мессенджере.
     */
    fun absoluteUrl(path: String): String = BASE_URL.trimEnd('/') + path

    val api: DevLogApi by lazy {
        // ignoreUnknownKeys = true — если сервер пришлёт лишние поля, не падаем.
        val json = Json { ignoreUnknownKeys = true }
        val contentType = "application/json".toMediaType()

        // OkHttp с перехватчиком: добавляет заголовок Authorization с JWT-токеном в каждый запрос
        // (для /api/auth/* токена ещё нет — тогда просто не добавляем).
        val httpClient = OkHttpClient.Builder()
            // Сборка отчёта идёт синхронно и вместе с ИИ занимает секунды, иногда
            // десятки секунд. Со стандартными 10 секундами OkHttp обрывал бы её
            // «таймаутом», хотя сервер честно работает.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                val token = TokenStore.token
                if (!token.isNullOrEmpty()) {
                    builder.addHeader("Authorization", "Bearer $token")
                }
                chain.proceed(builder.build())
            }
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(DevLogApi::class.java)
    }
}
