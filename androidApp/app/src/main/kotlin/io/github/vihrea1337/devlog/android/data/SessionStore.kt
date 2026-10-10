package io.github.vihrea1337.devlog.android.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Кто вошёл: JWT-токен и имя для шапки. */
data class Session(val token: String, val displayName: String)

/**
 * Хранит сессию (JWT-токен и имя пользователя).
 *
 * Раньше это был глобальный `object TokenStore` с `init(context)` в Activity. Теперь —
 * обычный класс, который создаёт Hilt (@Singleton — один экземпляр на всё приложение)
 * и подставляет туда, где он нужен. Плюсы: зависимость видна в конструкторе, а в тестах
 * её легко подменить.
 *
 * Почему SharedPreferences, а не DataStore: токен нужен СИНХРОННО в сетевом перехватчике
 * OkHttp (он вызывается не из корутины). Файл лежит в приватной папке приложения, а
 * android:allowBackup="false" в манифесте не даёт ему уехать в облачную резервную копию.
 *
 * [session] — StateFlow: навигация подписана на него и сама уводит на экран входа, когда
 * сессия пропадает — и при нажатии «Выйти», и когда сервер ответил 401 (токен истёк).
 */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Токен для заголовка Authorization (читается из перехватчика OkHttp). */
    val token: String? get() = _session.value?.token

    fun save(token: String, displayName: String) {
        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_NAME, displayName).apply()
        _session.value = Session(token, displayName)
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_NAME).apply()
        _session.value = null
    }

    private fun load(): Session? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return Session(token, prefs.getString(KEY_NAME, null).orEmpty())
    }

    private companion object {
        // Имя файла и ключи — те же, что у прежнего TokenStore: после обновления
        // приложения пользователь остаётся залогиненным.
        const val PREFS = "devlog_prefs"
        const val KEY_TOKEN = "token"
        const val KEY_NAME = "name"
    }
}
