package com.nasphoto.migrator.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nasphoto.migrator.business.model.MigrationState
import com.nasphoto.migrator.business.model.MigrationStatus
import com.nasphoto.migrator.business.model.PauseReason
import com.nasphoto.migrator.ui.components.InfoRow
import com.nasphoto.migrator.ui.components.SectionCard
import com.nasphoto.migrator.ui.components.StatusPill
import com.nasphoto.migrator.ui.theme.successColor
import com.nasphoto.migrator.ui.theme.warningColor
import com.nasphoto.migrator.ui.util.formatBytes
import com.nasphoto.migrator.ui.util.formatCount
import com.nasphoto.migrator.ui.util.formatDateTime
import com.nasphoto.migrator.ui.util.formatPercent

// ============================================================================
// 首页下半部分：迁移状态卡 / 控制按钮 / 网络电量标识 / 开始前预览弹窗
// ============================================================================

/** 迁移状态卡：状态机、暂停原因、整体进度、当前文件与阶段、NAS 剩余空间 */
@Composable
fun MigrationStatusCard(state: MigrationState) {
    SectionCard(title = "迁移状态") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(text = statusLabel(state.status), color = statusColor(state.status))
            Text(
                text = "${formatCount(state.processedCount)}/${formatCount(state.totalCount)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.activeConfigName.isNotBlank()) {
                Text(
                    text = "目标：${state.activeConfigName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 暂停原因（网络/电量/权限/NAS 等，必须显式展示）
        if (state.pauseReason != PauseReason.NONE) {
            Text(
                text = "暂停原因：${state.pauseReason.description}",
                style = MaterialTheme.typography.bodyMedium,
                color = warningColor(),
                fontWeight = FontWeight.Medium,
            )
        }

        // 整体进度（按文件数）
        LinearProgressIndicator(
            progress = { state.overallProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "整体进度 ${formatPercent(state.overallProgress)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "已处理 ${formatCount(state.processedCount)} / ${formatCount(state.totalCount)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 当前文件 + 当前文件进度 + 当前阶段
        if (state.currentFileName.isNotBlank()) {
            Text(
                text = "当前文件：${state.currentFileName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { state.currentFileProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.currentStage.ifBlank { "处理中" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatBytes(state.currentFileBytes)} / ${formatBytes(state.currentFileTotalBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.currentFileRetry > 0) {
                Text(
                    text = "当前文件已重试 ${state.currentFileRetry} 次（最多 3 次）",
                    style = MaterialTheme.typography.labelSmall,
                    color = warningColor(),
                )
            }
        } else {
            Text(
                text = state.lastMessage.ifBlank { "尚未开始迁移" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.currentFileName.isNotBlank() && state.lastMessage.isNotBlank()) {
            Text(
                text = "最近：${state.lastMessage}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // NAS 剩余空间（可获得时）
        state.nasFreeSpaceBytes?.let { free ->
            InfoRow(
                label = "NAS 剩余空间",
                value = formatBytes(free),
                valueColor = if (free < 500L * 1024 * 1024) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }

        if (state.startedAt > 0L && state.status != MigrationStatus.IDLE) {
            InfoRow(label = "开始时间", value = formatDateTime(state.startedAt))
        }

        // 设备环境（网络 / 电量）
        NetworkBatteryBar(state)
    }
}

/**
 * 迁移控制按钮区（按状态机启用/禁用）：
 * 开始 / 暂停 / 继续 / 停止 两行两列，避免四个按钮挤压。
 *
 * 说明：四个动作全部通过 MigrationForegroundService 的静态方法执行（由服务内部调用引擎），
 * 保证前台服务与通知栏正常，UI 不直接调用 engine 的启动/暂停/停止命令。
 */
@Composable
fun MigrationControlBar(
    state: MigrationState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val isActive = state.status.isActive
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                enabled = !isActive,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "开始")
            }
            OutlinedButton(
                onClick = onPause,
                enabled = state.status == MigrationStatus.RUNNING,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "暂停")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onResume,
                enabled = state.status == MigrationStatus.PAUSED,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "继续")
            }
            OutlinedButton(
                onClick = onStop,
                enabled = isActive,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "停止")
            }
        }
        Text(
            text = "铁则：单张上传 → 校验通过 → 立即删除本地原图 → 再处理下一张。失败绝不删除本地文件。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 网络 / 电量小标识（迁移状态卡底部） */
@Composable
private fun NetworkBatteryBar(state: MigrationState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (state.isWifi) Icons.Filled.Wifi else Icons.Filled.SignalCellularAlt,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    !state.isNetworkAvailable -> "网络不可用"
                    state.isWifi -> "WiFi"
                    else -> "移动网络"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (state.isNetworkAvailable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (state.batteryPercent >= 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (state.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (state.batteryPercent <= 20) warningColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildString {
                        append("${state.batteryPercent}%")
                        if (state.isCharging) append("（充电中）")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!state.isNetworkAvailable) {
            Text(
                text = "网络恢复后自动继续",
                style = MaterialTheme.typography.labelSmall,
                color = warningColor(),
            )
        }
    }
}

/** 开始迁移前的预览确认弹窗（由 engine.loadPreview() 提供数据） */
@Composable
fun StartPreviewDialog(
    state: StartPreviewState,
    configName: String,
    notificationGranted: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = state.preview
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = if (preview != null && preview.totalCount == 0) "没有待迁移文件" else "开始迁移确认") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(text = "正在统计待迁移文件…", style = MaterialTheme.typography.bodyMedium)
                    }

                    state.error != null -> Text(
                        text = "统计失败：${state.error}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    preview != null -> {
                        InfoRow(label = "目标 NAS", value = configName.ifBlank { "未激活配置" })
                        InfoRow(
                            label = "待迁移文件",
                            value = "${formatCount(preview.totalCount)} 个（图片 ${formatCount(preview.imageCount)} / 视频 ${formatCount(preview.videoCount)}）",
                        )
                        InfoRow(label = "待迁移大小", value = formatBytes(preview.totalBytes))
                        InfoRow(label = "预计释放空间", value = formatBytes(preview.estimatedFreeBytes))
                        if (preview.alreadyMigratedCount > 0) {
                            InfoRow(label = "已迁移跳过", value = "${formatCount(preview.alreadyMigratedCount)} 个")
                        }
                        Text(
                            text = "将严格逐张执行：上传 → 校验完整性 → 删除本地原图。任何失败都会保留本地文件并记入异常列表。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!notificationGranted) {
                            Text(
                                text = "未授予通知权限，迁移进度将不会显示在通知栏（不影响迁移本身）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = warningColor(),
                            )
                        }
                        if (preview.totalCount == 0) {
                            Text(
                                text = "当前没有待迁移文件，可能相册为空、相册筛选无匹配，或全部已迁移。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.loading && state.error == null,
            ) {
                Text(text = if (preview != null && preview.totalCount == 0) "知道了" else "开始迁移")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
    )
}

/** 迁移状态 → 中文文案 */
private fun statusLabel(status: MigrationStatus): String = when (status) {
    MigrationStatus.IDLE -> "空闲"
    MigrationStatus.RUNNING -> "迁移中"
    MigrationStatus.PAUSED -> "已暂停"
    MigrationStatus.STOPPING -> "正在停止"
    MigrationStatus.COMPLETED -> "已完成"
    MigrationStatus.FAILED -> "异常终止"
}

/** 迁移状态 → 语义色 */
@Composable
private fun statusColor(status: MigrationStatus): Color = when (status) {
    MigrationStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    MigrationStatus.RUNNING -> MaterialTheme.colorScheme.primary
    MigrationStatus.PAUSED -> warningColor()
    MigrationStatus.STOPPING -> MaterialTheme.colorScheme.tertiary
    MigrationStatus.COMPLETED -> successColor()
    MigrationStatus.FAILED -> MaterialTheme.colorScheme.error
}
