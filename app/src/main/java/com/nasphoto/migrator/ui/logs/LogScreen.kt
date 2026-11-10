package com.nasphoto.migrator.ui.logs

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nasphoto.migrator.data.record.LogLevel
import com.nasphoto.migrator.data.record.LogRecord
import com.nasphoto.migrator.ui.components.AppTopBar
import com.nasphoto.migrator.ui.components.ChoiceChip
import com.nasphoto.migrator.ui.components.ConfirmDialog
import com.nasphoto.migrator.ui.components.EmptyState
import com.nasphoto.migrator.ui.components.StatusPill
import com.nasphoto.migrator.ui.theme.warningColor
import com.nasphoto.migrator.ui.util.formatCount
import com.nasphoto.migrator.ui.util.formatTime
import com.nasphoto.migrator.ui.util.rememberAppContainer

/**
 * 日志页：本地日志（时间倒序）、级别筛选、清空、导出分享。
 *
 * 导出：LogExporter 写 cacheDir/logs 目录下的 txt 文件 + FileProvider(content://) 分享；
 * FileProvider 异常时自动降级为纯文本分享，不会崩溃。
 */
@Composable
fun LogScreen() {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val viewModel: LogViewModel = viewModel(factory = LogViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirm by remember { mutableStateOf(false) }

    val filteredLogs = uiState.filteredLogs

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    // 分享 Intent 就绪后拉起系统分享面板
    LaunchedEffect(uiState.shareIntent) {
        val intent = uiState.shareIntent ?: return@LaunchedEffect
        runCatching {
            context.startActivity(Intent.createChooser(intent, "分享迁移日志"))
        }.onFailure { throwable ->
            viewModel.showMessage("无法打开分享面板：${throwable.message ?: "未知错误"}")
        }
        viewModel.consumeShareIntent()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = "日志",
                subtitle = "共 ${formatCount(uiState.logs.size)} 条本地日志（仅本机保存，不上报）",
                actions = {
                    IconButton(
                        onClick = { viewModel.exportLogs(context) },
                        enabled = uiState.logs.isNotEmpty(),
                    ) {
                        Icon(imageVector = Icons.Filled.Share, contentDescription = "导出/分享日志")
                    }
                    IconButton(
                        onClick = { showClearConfirm = true },
                        enabled = uiState.logs.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "清空日志",
                            tint = if (uiState.logs.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                },
            )

            // 级别筛选
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogFilter.entries.forEach { filter ->
                    ChoiceChip(
                        text = filter.label,
                        selected = uiState.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                    )
                }
            }
            HorizontalDivider()

            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                filteredLogs.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Description,
                    title = "暂无日志",
                    description = if (uiState.logs.isEmpty()) {
                        "迁移开始后，关键节点（开始/上传/校验/删除/失败）都会记录在这里。"
                    } else {
                        "当前级别下没有日志，可切换筛选条件查看其他级别。"
                    },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(items = filteredLogs, key = { it.id }) { record ->
                        LogRow(record = record)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
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

    if (showClearConfirm) {
        ConfirmDialog(
            title = "清空全部日志",
            message = "确定清空全部本地日志？\n\n只删除本机的日志记录，不影响迁移记录与相册文件。",
            confirmText = "清空日志",
            destructive = true,
            onConfirm = {
                showClearConfirm = false
                viewModel.clearLogs()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

/** 单条日志：时间 + 级别胶囊 + tag + 消息（ERROR 用错误色，WARN 用警告色） */
@Composable
private fun LogRow(record: LogRecord) {
    val levelColor: Color = when (record.level) {
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.WARN -> warningColor()
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = formatTime(record.timestamp),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusPill(text = record.level.label, color = levelColor)
            Text(
                text = record.tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = record.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (record.level == LogLevel.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
