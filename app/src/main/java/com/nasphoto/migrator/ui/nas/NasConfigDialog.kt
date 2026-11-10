package com.nasphoto.migrator.ui.nas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nasphoto.migrator.protocol.NasProtocol
import com.nasphoto.migrator.ui.components.ChoiceChip
import com.nasphoto.migrator.ui.components.LoadingRow
import com.nasphoto.migrator.ui.theme.successColor
import com.nasphoto.migrator.ui.util.formatBytes

/**
 * NAS 配置新增/编辑弹窗。
 *
 * 特点：
 *  - 协议选择（SMB/WebDAV）切换时端口默认值联动（445 / 80 / 443）；
 *  - 密码使用 PasswordVisualTransformation 隐藏，可临时切换明文以核对；
 *  - 「测试连接」按钮独立于保存：测试期间显示 loading，且不阻塞其他输入；
 *  - 测试结果分成功（服务器信息/远程根路径/可写/剩余空间）与失败（错误分类 + 原始信息）。
 */
@Composable
fun NasConfigEditorDialog(
    editor: NasConfigEditorState,
    viewModel: NasConfigViewModel,
) {
    var showPassword by remember(editor.draft.id) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { viewModel.closeEditor() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (editor.isNew) "新增 NAS 配置" else "编辑 NAS 配置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { viewModel.closeEditor() },
                        enabled = !editor.saving,
                    ) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                HorizontalDivider()

                // 表单项（可滚动）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "协议",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NasProtocol.entries.forEach { protocol ->
                            ChoiceChip(
                                text = protocol.displayName,
                                selected = editor.draft.protocol == protocol,
                                onClick = { viewModel.setProtocol(protocol) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = editor.draft.name,
                        onValueChange = viewModel::setName,
                        label = { Text(text = "配置名称") },
                        placeholder = { Text(text = "例如：家里群晖") },
                        singleLine = true,
                        enabled = !editor.saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editor.draft.host,
                        onValueChange = viewModel::setHost,
                        label = { Text(text = "主机 / IP") },
                        placeholder = { Text(text = "192.168.1.10 或 nas.example.com") },
                        singleLine = true,
                        enabled = !editor.saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editor.portText,
                        onValueChange = viewModel::setPortText,
                        label = { Text(text = "端口") },
                        singleLine = true,
                        enabled = !editor.saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text(text = "SMB 默认 445；WebDAV 默认 80，HTTPS 默认 443") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (editor.draft.isSmb) {
                        OutlinedTextField(
                            value = editor.draft.smbShare,
                            onValueChange = viewModel::setSmbShare,
                            label = { Text(text = "共享名（SMB 必填）") },
                            placeholder = { Text(text = "例如：home") },
                            singleLine = true,
                            enabled = !editor.saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = editor.draft.basePath,
                        onValueChange = viewModel::setBasePath,
                        label = { Text(text = "远程目录（迁移根目录）") },
                        placeholder = { Text(text = "例如：PhotoBackup") },
                        singleLine = true,
                        enabled = !editor.saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editor.draft.username,
                        onValueChange = viewModel::setUsername,
                        label = { Text(text = "用户名") },
                        singleLine = true,
                        enabled = !editor.saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editor.draft.password,
                        onValueChange = viewModel::setPassword,
                        label = { Text(text = "密码") },
                        singleLine = true,
                        enabled = !editor.saving,
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
                    if (editor.draft.isWebDav) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "使用 HTTPS", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "WebDAV 建议开启（需服务器支持）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = editor.draft.useHttps,
                                onCheckedChange = viewModel::setUseHttps,
                                enabled = !editor.saving,
                            )
                        }
                    }

                    // 表单校验错误
                    editor.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // 测试连接结果
                    ConnectionTestBlock(editor = editor)

                    Text(
                        text = "SMB 使用 jcifs-ng（SMB2/SMB3），WebDAV 使用 sardine；" +
                            "密码仅在本机加密保存，不会上传到任何服务器。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                // 底部操作区
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        enabled = !editor.saving && editor.testResult != ConnectionTestResult.Testing,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (editor.testResult == ConnectionTestResult.Testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "测试中…")
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "测试连接")
                        }
                    }
                    TextButton(
                        onClick = { viewModel.closeEditor() },
                        enabled = !editor.saving,
                    ) {
                        Text(text = "取消")
                    }
                    Button(
                        onClick = viewModel::save,
                        enabled = !editor.saving,
                    ) {
                        if (editor.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(text = "保存")
                    }
                }
            }
        }
    }
}

/** 测试连接结果块：成功展示服务器信息与剩余空间，失败展示错误分类与原始信息 */
@Composable
private fun ConnectionTestBlock(editor: NasConfigEditorState) {
    when (val result = editor.testResult) {
        ConnectionTestResult.Idle -> Unit

        ConnectionTestResult.Testing -> LoadingRow(text = "正在连接 NAS，请稍候…")

        is ConnectionTestResult.Success -> {
            val info = result.info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = successColor().copy(alpha = 0.12f)),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "连接成功",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = successColor(),
                    )
                    info.serverDescription?.takeIf { it.isNotBlank() }?.let { server ->
                        Text(text = "服务器：$server", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = "远程根路径：${info.remoteRootPath}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "可写：${if (info.writable) "是" else "否（请检查远程目录权限）"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    info.freeSpaceBytes?.let { free ->
                        Text(
                            text = "NAS 剩余空间：${formatBytes(free)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        is ConnectionTestResult.Failure -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "连接失败：${result.reason}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    result.detail?.takeIf { it != result.reason }?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}
