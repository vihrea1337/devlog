package io.github.vihrea1337.devlog.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Единственная Activity. Показывает экран входа, пока нет токена, иначе — ленту записей.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TokenStore.init(applicationContext) // загрузить сохранённый токен
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var loggedIn by rememberSaveable { mutableStateOf(TokenStore.token != null) }
                    if (loggedIn) {
                        FeedScreen(
                            onLogout = {
                                TokenStore.clear()
                                loggedIn = false
                            },
                        )
                    } else {
                        LoginScreen(onLoggedIn = { loggedIn = true })
                    }
                }
            }
        }
    }
}
