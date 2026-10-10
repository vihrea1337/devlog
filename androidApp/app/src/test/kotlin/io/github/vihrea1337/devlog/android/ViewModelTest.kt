package io.github.vihrea1337.devlog.android

import app.cash.turbine.test
import io.github.vihrea1337.devlog.ProjectDto
import io.github.vihrea1337.devlog.ReportDto
import io.github.vihrea1337.devlog.android.data.AuthRepository
import io.github.vihrea1337.devlog.android.data.ProjectsRepository
import io.github.vihrea1337.devlog.android.data.ReportsRepository
import io.github.vihrea1337.devlog.android.reminder.delayUntilHour
import io.github.vihrea1337.devlog.android.ui.login.LoginViewModel
import io.github.vihrea1337.devlog.android.ui.reports.PeriodPreset
import io.github.vihrea1337.devlog.android.ui.reports.ReportsViewModel
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Юнит-тесты ViewModel — на обычной JVM, без телефона и без сети.
 *
 * Как это устроено: репозитории — интерфейсы, и вместо настоящих (с Retrofit) сюда
 * подставлены фейки ниже, которые возвращают заранее заданные данные или кидают ошибку.
 * viewModelScope работает на Dispatchers.Main, которого на JVM нет, — подменяем его
 * тестовым диспетчером (setMain), а advanceUntilIdle() «прокручивает» все корутины.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // --- Фейки репозиториев ---

    private class FakeAuth(var failWith: Exception? = null) : AuthRepository {
        var loggedInAs: String? = null
        override suspend fun login(email: String, password: String) {
            failWith?.let { throw it }
            loggedInAs = email
        }
        override suspend fun register(email: String, password: String, displayName: String) = login(email, password)
        override fun logout() { loggedInAs = null }
    }

    private class FakeProjects : ProjectsRepository {
        override suspend fun projects() = listOf(ProjectDto(id = "p1", name = "DevLog"))
    }

    private class FakeReports : ReportsRepository {
        val created = mutableListOf<Triple<String, String, String?>>()
        override suspend fun reports() = emptyList<ReportDto>()
        override suspend fun report(id: String) = report(id, "Отчёт")
        override suspend fun create(periodStart: String, periodEnd: String, projectId: String?): ReportDto {
            created += Triple(periodStart, periodEnd, projectId)
            return report("new", "Новый")
        }
        override suspend fun update(id: String, title: String, contentMd: String) = report(id, title)
        override suspend fun delete(id: String) {}
        override suspend fun share(id: String) = "tok" to "https://example/r/tok"
        override suspend fun unshare(id: String) {}

        fun report(id: String, title: String) = ReportDto(
            id = id, title = title, periodStart = "2026-10-01", periodEnd = "2026-10-10",
            format = "markdown", contentMd = "## Кратко\nтекст", createdAt = "2026-10-10T10:00:00Z",
        )
    }

    // --- Вход ---

    @Test
    fun `вход успешен — ошибки нет, репозиторий получил email`() = runTest(dispatcher) {
        val auth = FakeAuth()
        val vm = LoginViewModel(auth)
        vm.setEmail("demo@devlog.app")
        vm.setPassword("secret1")
        vm.submit()
        advanceUntilIdle()

        assertEquals("demo@devlog.app", auth.loggedInAs)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isBusy)
    }

    @Test
    fun `нет сети — показываем понятную причину, а не стектрейс`() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth(failWith = IOException("timeout")))
        vm.setEmail("a@b.c")
        vm.setPassword("secret1")
        vm.submit()
        advanceUntilIdle()

        assertEquals("Нет связи с сервером", vm.state.value.error)
    }

    @Test
    fun `короткий пароль при регистрации отклоняется без запроса на сервер`() = runTest(dispatcher) {
        val auth = FakeAuth()
        val vm = LoginViewModel(auth)
        vm.toggleMode()
        vm.setEmail("a@b.c")
        vm.setPassword("123")
        vm.submit()
        advanceUntilIdle()

        assertNull(auth.loggedInAs)
        assertEquals("Пароль — не короче 6 символов", vm.state.value.error)
    }

    // --- Отчёты ---

    @Test
    fun `готовый период подставляет даты`() = runTest(dispatcher) {
        val vm = ReportsViewModel(FakeReports(), FakeProjects())
        vm.selectPreset(PeriodPreset.LAST_7)
        val (from, to) = PeriodPreset.LAST_7.range()

        assertEquals(from.toString(), vm.state.value.periodStart)
        assertEquals(to.toString(), vm.state.value.periodEnd)
    }

    @Test
    fun `конец периода раньше начала — ошибка, отчёт не собирается`() = runTest(dispatcher) {
        val reports = FakeReports()
        val vm = ReportsViewModel(reports, FakeProjects())
        vm.setPeriodStart("2026-10-10")
        vm.setPeriodEnd("2026-10-01")
        vm.generate()
        advanceUntilIdle()

        assertTrue(reports.created.isEmpty())
        assertEquals("Конец периода раньше начала", vm.state.value.error)
    }

    @Test
    fun `сборка отчёта по проекту — отчёт в списке и событие «открыть»`() = runTest(dispatcher) {
        val reports = FakeReports()
        val vm = ReportsViewModel(reports, FakeProjects())
        vm.selectProject("p1")
        vm.opened.test {
            vm.generate()
            advanceUntilIdle()
            assertEquals("new", awaitItem())
        }
        assertEquals("p1", reports.created.single().third)
        assertEquals("new", vm.state.value.reports.first().id)
        assertFalse(vm.state.value.isGenerating)
    }

    // --- Напоминание ---

    @Test
    fun `напоминание — сегодня, если час ещё не наступил, иначе завтра`() {
        val morning = LocalDateTime.of(LocalDate.of(2026, 10, 10), java.time.LocalTime.of(9, 0))
        val night = LocalDateTime.of(LocalDate.of(2026, 10, 10), java.time.LocalTime.of(21, 30))

        assertEquals(Duration.ofHours(11), delayUntilHour(20, morning))
        assertEquals(Duration.ofMinutes(22 * 60 + 30), delayUntilHour(20, night))
    }
}
