package io.github.vihrea1337.devlog.android.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.vihrea1337.devlog.android.data.DevLogApi
import io.github.vihrea1337.devlog.android.data.SessionStore
import io.github.vihrea1337.devlog.db.DevLogDatabase
import io.github.vihrea1337.devlog.sync.DevLogSyncClient
import io.github.vihrea1337.devlog.sync.EntryRepository
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Hilt-модуль: «рецепты», как создавать объекты, которые нельзя просто пометить @Inject
 * (классы из чужих библиотек: OkHttp, Retrofit, SQLDelight, общий модуль shared/).
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

    @Provides
    @Singleton
    fun provideApi(client: OkHttpClient, json: Json): DevLogApi = Retrofit.Builder()
        .baseUrl(DevLogApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(DevLogApi::class.java)

    /** Настоящий файл SQLite на телефоне — офлайн-кэш ленты (схема и запросы — в shared/). */
    @Provides
    @Singleton
    fun provideSqlDriver(@ApplicationContext context: Context): SqlDriver =
        AndroidSqliteDriver(DevLogDatabase.Schema, context, "devlog.db")

    @Provides
    @Singleton
    fun provideDatabase(driver: SqlDriver): DevLogDatabase = DevLogDatabase(driver)

    /**
     * Клиент синхронизации из shared/ работает на движке OkHttp и том же OkHttpClient,
     * что и Retrofit: общий пул соединений, TLS платформы и общий перехватчик (401 → выход).
     * Движок CIO по умолчанию на Android не проходил проверку сертификата (см. build.gradle.kts).
     */
    @Provides
    @Singleton
    fun provideSyncClient(session: SessionStore, okHttp: OkHttpClient): DevLogSyncClient =
        DevLogSyncClient(
            baseUrl = DevLogApi.BASE_URL,
            tokenProvider = { session.token },
            engine = OkHttp.create { preconfigured = okHttp },
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
