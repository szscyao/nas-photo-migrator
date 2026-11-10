package com.nasphoto.migrator.ui.neighbor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * SMB 账号密码弹窗（AUTH_REQUIRED 时由向导自动弹出）。
 *
 * 约定：
 *  - 密码默认隐藏，可点右侧眼睛切换明文（任务单要求）；
 *  - 密码只存在于本 Composable 的本地状态，提交后由 ViewModel 立即写入加密仓库，
 *    不进入 UI 状态快照、不写日志；
 *  - 输错时 [AuthDialogState.error] 显示「账号或密码不对，请重新输入」。
 */
@Composable
fun AuthDialog(
    state: AuthDialogState,
    onSubmit: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 以 host 作为 key：换设备重新输入；同设备重试时只保留用户名建议值，密码清空
    var username by remember(state.host) { mutableStateOf(state.suggestedUsername) }
    var password by remember(state.host) { mutableStateOf("") }
    var showPassword by remember(state.host) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        title = { Text(text = "这台设备需要账号密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "设备：${state.deviceName}（${state.host}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(text = "用户名") },
                    singleLine = true,
                    enabled = !state.submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(text = "密码") },
                    singleLine = true,
                    enabled = !state.submitting,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "账号密码会加密保存在本机（EncryptedSharedPreferences），仅用于访问该设备。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(username, password) },
                enabled = !state.submitting,
            ) {
                if (state.submitting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(text = "  连接中…")
                    }
                } else {
                    Text(text = "连接")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.submitting,
            ) {
                Text(text = "取消")
            }
        },
    )
}
