package io.github.vihrea1337.devlog.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.vihrea1337.devlog.android.data.SessionStore
import io.github.vihrea1337.devlog.android.reminder.EXTRA_OPEN_NEW_ENTRY
import io.github.vihrea1337.devlog.android.ui.navigation.DevLogNavHost
import io.github.vihrea1337.devlog.android.ui.theme.DevLogTheme
import javax.inject.Inject

/**
 * Единственная Activity — всё остальное экраны Compose внутри неё (single-activity).
 * @AndroidEntryPoint — Hilt подставляет зависимости в поля с @Inject.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var session: SessionStore

    /** Открыть окно новой записи — приложение запущено нажатием на напоминание. */
    private val openNewEntry = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // до super.onCreate — так требует SplashScreen API
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // рисуем под системными панелями, отступы задаёт Scaffold
        openNewEntry.value = intent.getBooleanExtra(EXTRA_OPEN_NEW_ENTRY, false)

        setContent {
            DevLogTheme {
                // Surface задаёт фон и цвет текста по умолчанию из темы. Без него текст без
                // явного цвета рисовался чёрным — в тёмной теме заголовок на экране входа
                // был невидим (найдено на телефоне).
                Surface(color = MaterialTheme.colorScheme.background) {
                    DevLogNavHost(
                        session = session,
                        openNewEntry = openNewEntry.value,
                        onNewEntryShown = { openNewEntry.value = false },
                    )
                }
            }
        }
    }

    /** Приложение уже открыто (launchMode=singleTop), а пользователь нажал на напоминание. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_NEW_ENTRY, false)) openNewEntry.value = true
    }
}
