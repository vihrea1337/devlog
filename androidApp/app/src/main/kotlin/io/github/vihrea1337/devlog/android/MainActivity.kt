package io.github.vihrea1337.devlog.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Вкладки приложения. */
private enum class AppTab(val title: String) {
    FEED("Лента"),
    REPORTS("Отчёты"),
}

/**
 * Единственная Activity. Показывает экран входа, пока нет токена, иначе — приложение
 * с двумя вкладками: лента записей и отчёты за период.
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
                        MainScreen(
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

/**
 * Каркас приложения: шапка с именем пользователя и выходом, переключатель вкладок
 * и содержимое выбранной вкладки.
 *
 * Каждая вкладка держит свою ViewModel, привязанную к Activity, — поэтому при
 * переключении туда-обратно лента не перезагружается с нуля.
 */
@Composable
private fun MainScreen(onLogout: () -> Unit) {
    // rememberSaveable — выбранная вкладка переживает поворот экрана.
    var tab by rememberSaveable { mutableStateOf(AppTab.FEED) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DevLog", style = MaterialTheme.typography.headlineSmall)
                TokenStore.userName?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onLogout) { Text("Выйти") }
        }

        TabRow(selectedTabIndex = tab.ordinal) {
            AppTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tab = item },
                    text = { Text(item.title) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Box с weight(1f) отдаёт вкладке всё оставшееся место — чтобы списки внутри
        // неё знали свою высоту и прокручивались, а не растягивали экран.
        Box(Modifier.weight(1f)) {
            when (tab) {
                AppTab.FEED -> FeedScreen()
                AppTab.REPORTS -> ReportsScreen()
            }
        }
    }
}
