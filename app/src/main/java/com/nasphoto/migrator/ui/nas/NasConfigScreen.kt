package com.nasphoto.migrator.ui.nas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nasphoto.migrator.data.config.NasConfig
import com.nasphoto.migrator.ui.components.AppTopBar
import com.nasphoto.migrator.ui.components.ConfirmDialog
import com.nasphoto.migrator.ui.components.EmptyState
import com.nasphoto.migrator.ui.components.StatusPill
import com.nasphoto.migrator.ui.util.rememberAppContainer

/**
 * NAS 配置页：配置列表（激活/编辑/删除/新增）+ 编辑弹窗 + 测试连接。
 *
 * 展示约定：列表卡片只展示 [NasConfig.summary]（不含密码）与掩码密码；
 * 密码明文仅存在于编辑弹窗的密码输入框（PasswordVisualTransformation 隐藏）。
 */
@Composable
fun NasConfigScreen() {
    val container = rememberAppContainer()
    val viewModel: NasConfigViewModel = viewModel(factory = NasConfigViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = "NAS 配置",
                subtitle = when {
                    uiState.configs.isEmpty() -> "尚未添加配置"
                    uiState.activeConfigId == null -> "共 ${uiState.configs.size} 套配置 · 未激活"
                    else -> "共 ${uiState.configs.size} 套配置 · 已激活 1 套"
                },
                actions = {
                    IconButton(onClick = viewModel::startCreate) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = "新增配置")
                    }
                },
            )

            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                uiState.configs.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Cloud,
                    title = "还没有 NAS 配置",
                    description = "添加一套 SMB 或 WebDAV 配置后，即可把相册逐张迁移到你的 NAS。",
                    action = {
                        Button(onClick = viewModel::startCreate) {
                            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "新增配置")
                        }
                    },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = uiState.configs, key = { it.id }) { config ->
                        NasConfigCard(
                            config = config,
                            isActive = config.id == uiState.activeConfigId,
                            onActivate = { viewModel.setActive(config) },
                            onEdit = { viewModel.startEdit(config) },
                            onDelete = { viewModel.requestDelete(config) },
                        )
                    }
                    item(key = "nas_tip") {
                        Text(
                            text = "密码使用 Android Keystore 加密（EncryptedSharedPreferences）后保存在本机，" +
                                "不会随配置导出，也不会上传任何服务器。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    // 删除二次确认
    uiState.pendingDelete?.let { config ->
        ConfirmDialog(
            title = "删除配置",
            message = "确定删除配置「${config.name}」？\n\n" +
                "只会删除本机保存的连接信息（含加密密码），不影响 NAS 上已有的文件。",
            confirmText = "删除",
            destructive = true,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }

    // 新增/编辑弹窗
    uiState.editor?.let { editor ->
        NasConfigEditorDialog(
            editor = editor,
            viewModel = viewModel,
        )
    }
}

/**
 * 单套配置卡片：名称/协议、摘要、脱敏密码、激活单选、编辑/删除/设为目标。
 */
@Composable
private fun NasConfigCard(
    config: NasConfig,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // 展示前统一走 sanitized()，避免任何明文密码进入 UI 树
    val displayConfig = config.sanitized()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 8.dp, top = 10.dp, end = 8.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isActive, onClick = onActivate)
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayConfig.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusPill(
                            text = if (isActive) "迁移目标" else displayConfig.protocol.displayName,
                            color = if (isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(
                        text = displayConfig.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "密码：${if (config.password.isNotEmpty()) "••••••（已加密保存）" else "未设置"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isActive) {
                    TextButton(onClick = onActivate) {
                        Text(text = "设为目标")
                    }
                }
                TextButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.width(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "编辑")
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.width(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
