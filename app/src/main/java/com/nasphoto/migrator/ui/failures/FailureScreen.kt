package com.nasphoto.migrator.ui.failures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nasphoto.migrator.data.record.FailureRecord
import com.nasphoto.migrator.ui.components.AppTopBar
import com.nasphoto.migrator.ui.components.ConfirmDialog
import com.nasphoto.migrator.ui.components.EmptyState
import com.nasphoto.migrator.ui.components.InfoRow
import com.nasphoto.migrator.ui.components.StatusPill
import com.nasphoto.migrator.ui.util.formatBytes
import com.nasphoto.migrator.ui.util.formatDateTime
import com.nasphoto.migrator.ui.util.formatCount
import com.nasphoto.migrator.ui.util.rememberAppContainer

/**
 * 异常管理页。
 *
 * 展示失败文件的原因分类、详细原因、失败时间、大小、尝试次数、远程路径；
 * 支持单条重试（移除记录后回首页重新开始）、删除记录、查看详情与清空全部记录。
 *
 * 所有操作只作用于「失败记录表」，绝不删除本地相册文件。
 */
@Composable
fun FailureScreen(onNavigateToHome: () -> Unit) {
    val container = rememberAppContainer()
    val viewModel: FailureViewModel = viewModel(factory = FailureViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showClearAllConfirm by remember { mutableStateOf(false) }
    var detailRecord by remember { mutableStateOf<FailureRecord?>(null) }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = "异常管理",
                subtitle = if (uiState.failures.isEmpty()) {
                    "暂无失败记录"
                } else {
                    "共 ${formatCount(uiState.failures.size)} 条 · 本地文件均已保留"
                },
                actions = {
                    TextButton(
                        onClick = onNavigateToHome,
                        enabled = uiState.failures.isNotEmpty(),
                    ) {
                        Text(text = "回首页开始")
                    }
                    TextButton(
                        onClick = { showClearAllConfirm = true },
                        enabled = uiState.failures.isNotEmpty(),
                    ) {
                        Text(
                            text = "清空",
                            color = if (uiState.failures.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                },
            )

            when {
                uiState.loading -> Unit

                uiState.failures.isEmpty() -> EmptyState(
                    icon = Icons.Filled.CheckCircle,
                    title = "暂无失败记录",
                    description = "上传或校验失败的文件会保留在本地并记录在这里，可在此重试。",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = uiState.failures, key = { it.id }) { record ->
                        FailureCard(
                            record = record,
                            onRetry = { viewModel.retry(record) },
                            onDetail = { detailRecord = record },
                            onDelete = { viewModel.delete(record) },
                        )
                    }
                    item(key = "failure_tip") {
                        Text(
                            text = "失败的文件一律保留在本地；重试需回首页重新开始迁移，由引擎按单线程串行规则重新处理。",
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

    // 清空全部失败记录（二次确认，强调只清记录不删本地文件）
    if (showClearAllConfirm) {
        ConfirmDialog(
            title = "清空全部失败记录",
            message = "确定清空 ${formatCount(uiState.failures.size)} 条失败记录？\n\n" +
                "只清空记录，不会删除本地照片/视频。清空后这些文件仍在相册中，" +
                "下次开始迁移会重新尝试（若仍失败会再次记录）。",
            confirmText = "清空记录",
            destructive = true,
            onConfirm = {
                showClearAllConfirm = false
                viewModel.clearAll()
            },
            onDismiss = { showClearAllConfirm = false },
        )
    }

    // 失败详情弹窗
    detailRecord?.let { record ->
        FailureDetailDialog(
            record = record,
            onDismiss = { detailRecord = null },
            onRetry = {
                detailRecord = null
                viewModel.retry(record)
            },
        )
    }
}

/** 单条失败记录卡片 */
@Composable
private fun FailureCard(
    record: FailureRecord,
    onRetry: () -> Unit,
    onDetail: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatBytes(record.sizeBytes)} · ${formatDateTime(record.failedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    text = record.errorType.description,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "尝试 ${record.attempts} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = record.reason,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "远程路径：${record.remotePath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDetail) { Text(text = "详情") }
                TextButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "重试")
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 失败详情弹窗（完整原因、尝试次数、远程路径、fileKey 等） */
@Composable
private fun FailureDetailDialog(
    record: FailureRecord,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "失败详情") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoRow(label = "文件名", value = record.displayName)
                InfoRow(label = "所属相册", value = record.albumName.ifBlank { "-" })
                InfoRow(label = "文件大小", value = formatBytes(record.sizeBytes))
                InfoRow(label = "失败时间", value = formatDateTime(record.failedAt))
                InfoRow(label = "尝试次数", value = "${record.attempts} 次")
                InfoRow(label = "原因分类", value = record.errorType.description)
                InfoRow(label = "远程路径", value = record.remotePath)
                Text(
                    text = "详细原因",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = record.reason,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "文件标识（fileKey）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = record.fileKey,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    text = "本地文件已保留；重试需回首页重新开始迁移。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) { Text(text = "移除记录并重试") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "关闭") }
        },
    )
}
