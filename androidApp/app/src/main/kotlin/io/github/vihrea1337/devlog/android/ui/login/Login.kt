package io.github.vihrea1337.devlog.android.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.vihrea1337.devlog.android.data.AuthRepository
import io.github.vihrea1337.devlog.android.ui.common.humanMessage
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Состояние экрана входа. Поля ввода живут здесь, а не в remember, — переживают поворот. */
data class LoginUiState(
    val isRegister: Boolean = false,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val isBusy: Boolean = false,
    val error: String? = null,
    /** Сервер разрешает демо-вход — показываем кнопку «Открыть демо». */
    val demoAvailable: Boolean = false,
) {
    /** Кнопка активна, только когда есть что отправлять (и не идёт запрос). */
    val canSubmit: Boolean get() = !isBusy && email.isNotBlank() && password.isNotBlank()
}

/**
 * ViewModel входа/регистрации. Про токен и навигацию она не знает: репозиторий сохраняет
 * сессию, а навигация, подписанная на неё, сама переключается на главный экран.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        // Кнопку демо показываем, только если сервер её разрешил (на время показа работодателю).
        viewModelScope.launch {
            val available = auth.demoAvailable()
            _state.update { it.copy(demoAvailable = available) }
        }
    }

    /** Войти в демо-аккаунт «только для просмотра» — без регистрации. */
    fun demoLogin() {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            try {
                auth.demoLogin()
                _state.update { it.copy(isBusy = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false, error = e.humanMessage()) }
            }
        }
    }

    fun setEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun setDisplayName(v: String) = _state.update { it.copy(displayName = v) }
    fun toggleMode() = _state.update { it.copy(isRegister = !it.isRegister, error = null) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        // Короткий пароль сервер всё равно отклонит — скажем сразу, без запроса.
        if (s.isRegister && s.password.length < 6) {
            _state.update { it.copy(error = "Пароль — не короче 6 символов") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            try {
                if (s.isRegister) auth.register(s.email, s.password, s.displayName) else auth.login(s.email, s.password)
                _state.update { it.copy(isBusy = false) }
            } catch (e: Exception) {
                // Настоящая причина: сервер присылает её в теле ответа («Пользователь с таким
                // email уже есть»), а сетевые сбои отличаем от неверного пароля.
                _state.update { it.copy(isBusy = false, error = e.humanMessage()) }
            }
        }
    }
}

@Composable
fun LoginScreen(vm: LoginViewModel = hiltViewModel()) {
    // collectAsStateWithLifecycle — подписка на состояние ставится на паузу, когда
    // приложение свёрнуто (collectAsState продолжал бы работать впустую).
    val state by vm.state.collectAsStateWithLifecycle()
    var showPassword by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .imePadding() // форма поднимается над клавиатурой
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Text("DevLog", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Пишите пару строк о работе каждый день — ИИ соберёт из них отчёт для работодателя.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            // Демо без регистрации: готовый дневник с отчётами, только просмотр.
            if (state.demoAvailable && !state.isRegister) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Посмотреть без регистрации: готовый дневник разработчика с отчётами.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Button(onClick = vm::demoLogin, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Открыть демо")
                        }
                    }
                }
                Text("или войдите", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            if (state.isRegister) {
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = vm::setDisplayName,
                    label = { Text("Имя") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = state.email,
                onValueChange = vm::setEmail,
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = vm::setPassword,
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPassword) "Скрыть пароль" else "Показать пароль",
                        )
                    }
                },
                // Подсказка правила ДО отправки — чтобы не узнавать о нём из ошибки.
                supportingText = if (state.isRegister) ({ Text("Не короче 6 символов") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = vm::submit,
                enabled = state.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (state.isRegister) "Создать аккаунт" else "Войти")
                }
            }
            TextButton(onClick = vm::toggleMode, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isRegister) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")
            }
        }
    }
}
