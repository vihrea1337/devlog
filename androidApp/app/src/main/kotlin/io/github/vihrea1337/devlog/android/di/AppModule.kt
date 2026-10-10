package io.github.vihrea1337.devlog.android.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.vihrea1337.devlog.ErrorResponse
import io.github.vihrea1337.devlog.android.data.ApiException
import io.github.vihrea1337.devlog.android.data.DevLogApi
import io.github.vihrea1337.devlog.android.data.SessionStore
import io.github.vihrea1337.devlog.db.DevLogDatabase
import io.github.vihrea1337.devlog.sync.DevLogSyncClient
import io.github.vihrea1337.devlog.sync.EntryRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Hilt-модуль: «рецепты», как создавать объекты, которые нельзя просто пометить @Inject
 * (классы из чужих библиотек: OkHttp, Ktor, SQLDelight, общий модуль shared/).
 *
 * Сетевой стек — одна цепочка: OkHttpClient (таймауты, токен, 401 → выход) → движок Ktor
 * поверх него → HttpClient для DevLogApi и DevLogSyncClient для синхронизации записей.
 * Раньше рядом жил ещё Retrofit — второй клиент со своей обработкой ошибок.
 *
 * @InstallIn(SingletonComponent) + @Singleton — один экземпляр на всё приложение.
 * Свои классы (SessionStore, репозитории) Hilt умеет создавать сам по @Inject-конструктору —
 * им рецепты здесь не нужны.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true } // лишние поля от сервера не роняют разбор

    @Provides
    @Singleton
    fun provideOkHttp(session: SessionStore): OkHttpClient = OkHttpClient.Builder()
        // Сборка отчёта идёт синхронно и вместе с ИИ занимает секунды, иногда
        // десятки секунд. Со стандартными 10 секундами OkHttp обрывал бы её
        // «таймаутом», хотя сервер честно работает.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // Перехватчик — код, через который проходит КАЖДЫЙ запрос и ответ.
            // 1) Добавляем JWT-токен (для /api/auth/* его ещё нет — тогда просто не добавляем).
            //    Синхронизация (Ktor из shared/) ставит заголовок сама — второй не добавляем.
            val request = chain.request()
            val token = session.token
            val authorized = if (token.isNullOrEmpty() || request.header("Authorization") != null) request else {
                request.newBuilder().addHeader("Authorization", "Bearer $token").build()
            }
            val response = chain.proceed(authorized)
            // 2) 401 на запросе С токеном — токен истёк или отозван. Стираем сессию:
            //    навигация подписана на неё и сама откроет экран входа. Иначе пользователь
            //    видел бы непонятные ошибки на всех экранах. 401 на самом входе (неверный
            //    пароль) сюда не попадает — там токена ещё нет.
            if (response.code == 401 && !token.isNullOrEmpty()) session.clear()
            response
        }
        .build()

    /**
     * Движок Ktor поверх нашего OkHttpClient: TLS платформы, общий пул соединений и тот же
     * перехватчик (токен, 401 → выход). Движок CIO по умолчанию на Android не проходил
     * проверку сертификата (см. build.gradle.kts). Один движок на всё приложение — его
     * делят HttpClient для DevLogApi и клиент синхронизации.
     */
    @Provides
    @Singleton
    fun provideEngine(okHttp: OkHttpClient): HttpClientEngine = OkHttp.create { preconfigured = okHttp }

    /**
     * HttpClient для DevLogApi. Плагины Ktor делают то, что раньше делал Retrofit:
     * - defaultRequest — адрес сервера (в DevLogApi пути относительные) и тип тела JSON;
     * - ContentNegotiation — JSON <-> классы из shared/ через kotlinx.serialization;
     * - HttpResponseValidator — на 4xx/5xx бросить ApiException с причиной из тела ответа.
     *   Без него Ktor молча попытался бы разобрать `{"error": "…"}` как, например, ReportDto
     *   и упал бы с непонятной ошибкой сериализации.
     */
    @Provides
    @Singleton
    fun provideHttpClient(engine: HttpClientEngine, json: Json): HttpClient = HttpClient(engine) {
        defaultRequest {
            url(DevLogApi.BASE_URL)
            contentType(ContentType.Application.Json)
        }
        install(ContentNegotiation) { json(json) }
        HttpResponseValidator {
            validateResponse { response ->
                if (!response.status.isSuccess()) {
                    // Тело может быть чем угодно (например, HTML от прокси) — не смогли
                    // разобрать, останется только код HTTP.
                    val reason = runCatching {
                        json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()).error.ifBlank { null }
                    }.getOrNull()
                    throw ApiException(response.status.value, reason)
                }
            }
        }
    }

    /** Настоящий файл SQLite на телефоне — офлайн-кэш ленты (схема и запросы — в shared/). */
    @Provides
    @Singleton
    fun provideSqlDriver(@ApplicationContext context: Context): SqlDriver =
        AndroidSqliteDriver(DevLogDatabase.Schema, context, "devlog.db")

    @Provides
    @Singleton
    fun provideDatabase(driver: SqlDriver): DevLogDatabase = DevLogDatabase(driver)

    /** Клиент синхронизации из shared/ — на том же движке, что и DevLogApi. */
    @Provides
    @Singleton
    fun provideSyncClient(session: SessionStore, engine: HttpClientEngine): DevLogSyncClient =
        DevLogSyncClient(
            baseUrl = DevLogApi.BASE_URL,
            tokenProvider = { session.token },
            engine = engine,
        )

    /**
     * Репозиторий записей из общего модуля: лента читается из локальной базы (работает
     * без сети), изменения копятся в очереди и уезжают на сервер при синхронизации.
     */
    @Provides
    @Singleton
    fun provideEntryRepository(database: DevLogDatabase, syncClient: DevLogSyncClient): EntryRepository =
        EntryRepository(database, syncClient)
}
