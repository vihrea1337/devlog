package io.github.vihrea1337.devlog.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.vihrea1337.devlog.android.data.ApiClient
import io.github.vihrea1337.devlog.LoginRequest
import io.github.vihrea1337.devlog.RegisterRequest
import kotlinx.coroutines.launch

/**
 * Экран входа/регистрации по email+паролю (JWT). onLoggedIn вызывается после успеха —
 * токен уже сохранён в TokenStore.
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("DevLog", style = MaterialTheme.typography.headlineMedium)
        Text("Дневник разработчика", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        if (isRegister) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Имя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val resp = if (isRegister) {
                            ApiClient.api.register(
                                RegisterRequest(email.trim(), password, displayName.trim()),
                            )
                        } else {
                            ApiClient.api.login(LoginRequest(email.trim(), password))
                        }
                        TokenStore.save(resp.token, resp.user.displayName)
                        onLoggedIn()
                    } catch (e: Exception) {
                        error = if (isRegister) "Не удалось зарегистрироваться" else "Неверный email или пароль"
                    }
                    busy = false
                }
            },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isRegister) "Создать аккаунт" else "Войти")
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { isRegister = !isRegister; error = null },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isRegister) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}
