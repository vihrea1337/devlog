package io.github.vihrea1337.devlog.android.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.vihrea1337.devlog.android.TokenStore
import io.github.vihrea1337.devlog.db.DevLogDatabase
import io.github.vihrea1337.devlog.sync.DevLogSyncClient
import io.github.vihrea1337.devlog.sync.EntryRepository

/**
 * Единая точка доступа к офлайн-кэшу записей (SQLDelight) и синхронизации с сервером
 * (Ktor Client, since-протокол) — оба живут в общем модуле shared/, здесь только
 * Android-специфичная обвязка: настоящий файл БД на диске телефона.
 *
 * init(context) вызывается один раз в MainActivity, как и TokenStore.init() —
 * так же просто, синглтоном, без DI-фреймворка (в проекте его пока нет).
 */
object AppDatabase {
    lateinit var entryRepository: EntryRepository
        private set

    fun init(context: Context) {
        val driver = AndroidSqliteDriver(DevLogDatabase.Schema, context.applicationContext, "devlog.db")
        val database = DevLogDatabase(driver)
        val syncClient = DevLogSyncClient(
            baseUrl = ApiClient.BASE_URL,
            tokenProvider = { TokenStore.token },
        )
        entryRepository = EntryRepository(database, syncClient)
    }
}
