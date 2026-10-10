package io.github.vihrea1337.devlog.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.vihrea1337.devlog.android.data.SessionStore
import io.github.vihrea1337.devlog.android.ui.feed.FeedScreen
import io.github.vihrea1337.devlog.android.ui.login.LoginScreen
import io.github.vihrea1337.devlog.android.ui.profile.ProfileScreen
import io.github.vihrea1337.devlog.android.ui.reports.ReportDetailScreen
import io.github.vihrea1337.devlog.android.ui.reports.ReportsScreen
import kotlinx.serialization.Serializable

// Маршруты навигации — обычные @Serializable-классы (типобезопасная навигация
// Navigation 2.8+). Аргументы — поля класса: опечатку в имени аргумента или
// неверный тип поймает компилятор, а не пользователь в рантайме.
@Serializable data object LoginRoute
@Serializable data object HomeRoute
@Serializable data class ReportRoute(val id: String)

/** Вкладки нижней навигации главного экрана. */
private enum class Tab(val title: String, val icon: ImageVector) {
    FEED("Лента", Icons.AutoMirrored.Outlined.Notes),
    REPORTS("Отчёты", Icons.Outlined.Description),
    PROFILE("Профиль", Icons.Outlined.Person),
}

/**
 * Граф навигации приложения.
 *
 * Вход/выход управляется ДАННЫМИ, а не кнопками: граф подписан на сессию. Нет сессии
 * (нажали «Выйти» или сервер ответил 401 — токен истёк) → экран входа, история экранов
 * очищается, чтобы «Назад» не вернул в чужие данные. Появилась сессия → главный экран.
 */
@Composable
fun DevLogNavHost(
    session: SessionStore,
    openNewEntry: Boolean,
    onNewEntryShown: () -> Unit,
) {
    val nav = rememberNavController()
    val current by session.session.collectAsStateWithLifecycle()
    val loggedIn = current != null

    LaunchedEffect(loggedIn) {
        val target: Any = if (loggedIn) HomeRoute else LoginRoute
        // popUpTo(0) — убрать из истории всё, что было до этого.
        nav.navigate(target) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    NavHost(navController = nav, startDestination = if (loggedIn) HomeRoute else LoginRoute) {
        composable<LoginRoute> { LoginScreen() }
        composable<HomeRoute> {
            HomeScreen(
                openNewEntry = openNewEntry,
                onNewEntryShown = onNewEntryShown,
                onOpenReport = { id -> nav.navigate(ReportRoute(id)) },
            )
        }
        composable<ReportRoute> {
            ReportDetailScreen(onBack = { nav.popBackStack() })
        }
    }
}

/**
 * Главный экран: нижняя навигация между лентой, отчётами и профилем. Выбранная вкладка —
 * rememberSaveable: переживает поворот экрана и возврат с экрана отчёта.
 */
@Composable
private fun HomeScreen(openNewEntry: Boolean, onNewEntryShown: () -> Unit, onOpenReport: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.FEED) }
    // Пришли из уведомления «что сделал сегодня?» — на ленту, там откроется окно записи.
    LaunchedEffect(openNewEntry) { if (openNewEntry) tab = Tab.FEED }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        // Только нижний отступ (под панель вкладок): верхний под статус-бар добавит
        // шапка (TopAppBar) каждой вкладки — иначе он применился бы дважды.
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            when (tab) {
                Tab.FEED -> FeedScreen(openNewEntry = openNewEntry, onNewEntryShown = onNewEntryShown)
                Tab.REPORTS -> ReportsScreen(onOpen = onOpenReport)
                Tab.PROFILE -> ProfileScreen()
            }
        }
    }
}
