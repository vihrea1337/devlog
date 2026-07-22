package io.github.vihrea1337.devlog.android.data

import io.github.vihrea1337.devlog.android.TokenStore
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
import retrofit2.http.Path

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

    /** GET /api/entries — лента записей текущего пользователя. */
    @GET("api/entries")
    suspend fun getEntries(): List<EntryDto>

    /** POST /api/entries — добавить запись; сервер вернёт её с id и статусом "queued". */
    @POST("api/entries")
    suspend fun addEntry(@Body body: NewEntry): EntryDto

    /** DELETE /api/entries/{id} — удалить запись (204 = удалено). */
    @DELETE("api/entries/{id}")
    suspend fun deleteEntry(@Path("id") id: String): Response<Unit>

    /** POST /api/entries/{id}/reprocess — заново отправить запись на обработку ИИ (202). */
    @POST("api/entries/{id}/reprocess")
    suspend fun reprocess(@Path("id") id: String): Response<Unit>
}

/**
 * Единая точка создания клиента Retrofit. by lazy — создаём его один раз при первом обращении.
 */
object ApiClient {
    // Адрес боевого бэкенда DevLog (Caddy отдаёт HTTPS на порту 34444). Должен оканчиваться на "/".
    // Если переедем — меняем только эту строку.
    private const val BASE_URL = "https://vihreaschedule.duckdns.org:34444/"

    val api: DevLogApi by lazy {
        // ignoreUnknownKeys = true — если сервер пришлёт лишние поля, не падаем.
        val json = Json { ignoreUnknownKeys = true }
        val contentType = "application/json".toMediaType()

        // OkHttp с перехватчиком: добавляет заголовок Authorization с JWT-токеном в каждый запрос
        // (для /api/auth/* токена ещё нет — тогда просто не добавляем).
        val httpClient = OkHttpClient.Builder()
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
