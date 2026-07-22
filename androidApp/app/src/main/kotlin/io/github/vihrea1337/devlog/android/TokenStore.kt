package io.github.vihrea1337.devlog.android

import android.content.Context

/**
 * Хранит JWT-токен и имя пользователя. Токен лежит в SharedPreferences
 * (переживает перезапуск приложения) и в памяти (быстрый доступ из сетевого перехватчика).
 */
object TokenStore {
    private const val PREFS = "devlog_prefs"
    private const val KEY_TOKEN = "token"
    private const val KEY_NAME = "name"

    private var prefs: android.content.SharedPreferences? = null

    var token: String? = null
        private set
    var userName: String? = null
        private set

    /** Загрузить сохранённый токен при старте приложения. */
    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        token = p.getString(KEY_TOKEN, null)
        userName = p.getString(KEY_NAME, null)
    }

    /** Сохранить токен и имя (после входа/регистрации). */
    fun save(token: String, name: String?) {
        this.token = token
        this.userName = name
        prefs?.edit()?.putString(KEY_TOKEN, token)?.putString(KEY_NAME, name)?.apply()
    }

    /** Выйти — стереть токен. */
    fun clear() {
        token = null
        userName = null
        prefs?.edit()?.remove(KEY_TOKEN)?.remove(KEY_NAME)?.apply()
    }
}
