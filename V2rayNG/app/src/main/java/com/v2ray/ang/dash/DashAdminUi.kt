package com.v2ray.ang.dash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.ui.compose.AppDivider
import kotlinx.coroutines.launch

/** Диалог пароля и панель админа. Показывается поверх главного экрана. */
@Composable
fun DashAdminDialogs(
    showLogin: Boolean,
    showPanel: Boolean,
    onDismissLogin: () -> Unit,
    onLoggedIn: () -> Unit,
    onDismissPanel: () -> Unit,
    onServersChanged: () -> Unit,
) {
    if (showLogin) AdminPasswordDialog(onDismiss = onDismissLogin, onSuccess = onLoggedIn)
    if (showPanel) AdminPanelDialog(onDismiss = onDismissPanel, onServersChanged = onServersChanged)
}

@Composable
private fun AdminPasswordDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    val submit = {
        if (DashManager.login(password.trim())) onSuccess() else wrong = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вход для администратора") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; wrong = false },
                label = { Text("Пароль") },
                singleLine = true,
                isError = wrong,
                supportingText = if (wrong) ({ Text("Неверный пароль") }) else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text("Войти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun AdminPanelDialog(onDismiss: () -> Unit, onServersChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<DashServer>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    fun run(action: suspend () -> List<DashServer>, changed: Boolean) {
        scope.launch {
            busy = true
            error = null
            try {
                servers = action()
                if (changed) onServersChanged()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { run({ DashManager.fetchCloud() }, changed = false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Админ: серверы", modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { run({ DashManager.fetchCloud() }, changed = false) }) {
                    Text("Обновить")
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (servers.isEmpty() && !busy) Text("Список пуст")
                servers.forEach { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                s.key,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = { run({ DashManager.removeServer(s) }, changed = true) },
                        ) { Text("Удалить") }
                    }
                }
                AppDivider(modifier = Modifier.padding(vertical = 12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Название") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = country, onValueChange = { if (it.length <= 2) country = it.uppercase() },
                    label = { Text("Страна (код, напр. DE)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("Ключ (vless://, hysteria2://, …)") },
                    minLines = 1, maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        enabled = !busy && key.isNotBlank(),
                        onClick = {
                            val server = DashServer(name.trim(), country.trim(), key.trim())
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    servers = DashManager.addServer(server)
                                    name = ""; country = ""; key = ""
                                    onServersChanged()
                                } catch (e: Exception) {
                                    error = e.message ?: e.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text("Добавить сервер")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = {
            TextButton(onClick = { DashManager.logout(); onDismiss() }) { Text("Выйти из админа") }
        },
    )
}
