package io.github.vihrea1337.devlog.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vihrea1337.devlog.EntryDto
import io.github.vihrea1337.devlog.StructuredDto
import io.github.vihrea1337.devlog.android.ui.feed.EntryCard
import io.github.vihrea1337.devlog.android.ui.feed.WeekCard
import io.github.vihrea1337.devlog.android.ui.feed.WeekSummary
import java.time.LocalDate
import io.github.vihrea1337.devlog.android.ui.theme.DevLogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI-тест карточки записи: рисуем один composable и проверяем, что видит пользователь.
 *
 * В отличие от юнит-тестов, идёт на эмуляторе или телефоне:
 * `./gradlew :app:connectedDebugAndroidTest`. Сеть и сервер не нужны — карточке просто
 * передаём готовую запись, а вместо действий — лямбды, которые считают нажатия.
 */
@RunWith(AndroidJUnit4::class)
class EntryCardTest {

    @get:Rule val compose = createComposeRule()

    private val ready = EntryDto(
        id = "e1",
        occurredOn = "2026-10-10",
        rawText = "перевёл миграции на flyway",
        status = "structured",
        createdAt = "",
        updatedAt = "",
        structured = StructuredDto(summary = "Перешёл на Flyway", tags = listOf("flyway")),
    )

    private fun show(entry: EntryDto, readOnly: Boolean, onDelete: () -> Unit = {}) = compose.setContent {
        DevLogTheme(darkTheme = false) {
            EntryCard(entry = entry, readOnly = readOnly, project = null, onEdit = {}, onDelete = onDelete, onReprocess = {})
        }
    }

    @Test
    fun демо_режим_нет_меню_действий() {
        show(ready, readOnly = true)

        compose.onNodeWithText("Перешёл на Flyway").assertIsDisplayed()
        compose.onNodeWithContentDescription("Действия").assertDoesNotExist()
    }

    @Test
    fun обычный_режим_меню_удаляет_запись() {
        var deleted = 0
        show(ready, readOnly = false, onDelete = { deleted++ })

        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Удалить").performClick()

        assertEquals(1, deleted)
    }

    @Test
    fun запись_в_очереди_показывает_заглушку_ии() {
        // Заглушка мерцает бесконечной анимацией — останавливаем часы теста,
        // иначе он ждал бы «покоя» экрана, который не наступит.
        compose.mainClock.autoAdvance = false
        show(ready.copy(status = "queued", structured = null), readOnly = false)
        compose.mainClock.advanceTimeBy(100)

        compose.onNodeWithText("ИИ разбирает запись…").assertExists()
        compose.onNodeWithText("перевёл миграции на flyway").assertExists()
    }

    @Test
    fun пустая_неделя_фраза_вместо_нулей() {
        compose.setContent {
            DevLogTheme(darkTheme = false) {
                WeekCard(WeekSummary.from(listOf(ready.copy(occurredOn = "2026-10-01")), today = LocalDate.parse("2026-10-20")))
            }
        }

        compose.onNodeWithText("За неделю записей не было. Последняя — 19 дней назад.").assertIsDisplayed()
        compose.onNodeWithText("учтено").assertDoesNotExist()
    }
}
