package com.nasphoto.migrator.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nasphoto.migrator.R
import com.nasphoto.migrator.business.model.MigrationState
import com.nasphoto.migrator.business.model.MigrationStatus
import com.nasphoto.migrator.data.config.NasConfig
import com.nasphoto.migrator.ui.components.PermissionWarningCard
import com.nasphoto.migrator.ui.components.SectionCard
import com.nasphoto.migrator.ui.components.StatTile
import com.nasphoto.migrator.ui.permission.PermissionActions
import com.nasphoto.migrator.ui.permission.PermissionStatus
import com.nasphoto.migrator.ui.theme.successColor
import com.nasphoto.migrator.ui.util.formatBytes
import com.nasphoto.migrator.ui.util.formatCount

// ============================================================================
// 首页上半部分：迁移目标 NAS 卡片 / 统计卡片 / 权限缺失提示卡
// ============================================================================

/** 迁移目标 NAS 摘要卡（未配置时提示去配置页） */
@Composable
fun NasTargetCard(
    activeConfig: NasConfig?,
    configCount: Int,
    onNavigateToNas: () -> Unit,
) {
    SectionCard(title = "迁移目标 NAS") {
        if (activeConfig != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeConfig.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = activeConfig.protocol.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = onNavigateToNas) {
                    Text(text = "管理配置")
                }
            }
            // 摘要不含密码；密码单独以掩码展示，满足「脱敏展示」要求
            Text(
                text = activeConfig.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "密码：${if (activeConfig.password.isNotEmpty()) "••••••（已加密保存）" else "未设置"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "尚未配置 NAS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (configCount == 0) {
                    "迁移前需要先添加一套 NAS 连接配置（SMB 或 WebDAV）。"
                } else {
                    "已保存 $configCount 套配置，但还没有激活任何一套作为迁移目标。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onNavigateToNas) {
                Icon(
                    imageVector = Icons.Filled.Cloud,
                    contentDescription = null,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "去配置 NAS")
            }
        }
    }
}

/**
 * 权限缺失提示区（不阻断浏览，但「开始迁移」前必须校验）：
 * 缺读相册权限 / 缺删图权限（所有文件访问）/ 缺通知权限。
 */
@Composable
fun PermissionWarnings(
    permissions: PermissionStatus,
    actions: PermissionActions,
) {
    if (permissions.allGranted) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 权限说明文案统一使用 strings.xml 中已有的 perm_* 资源
        if (!permissions.mediaGranted) {
            PermissionWarningCard(
                title = stringResource(id = R.string.perm_media_title),
                description = stringResource(id = R.string.perm_media_desc),
                actionText = stringResource(id = R.string.perm_grant),
                onAction = actions.requestMediaPermissions,
            )
        }
        if (!permissions.deleteGranted) {
            PermissionWarningCard(
                title = stringResource(id = R.string.perm_manage_storage_title),
                description = stringResource(id = R.string.perm_manage_storage_desc),
                actionText = stringResource(id = R.string.perm_manage_storage_go),
                onAction = actions.openAllFilesAccessSettings,
            )
        }
        if (!permissions.notificationGranted) {
            PermissionWarningCard(
                title = stringResource(id = R.string.perm_notification_title),
                description = stringResource(id = R.string.perm_notification_desc),
                actionText = stringResource(id = R.string.perm_grant),
                onAction = actions.requestNotificationPermission,
            )
        }
    }
}

/**
 * 统计卡片区：
 * 待迁移数量 / 待迁移总大小（预计释放空间）/ 已释放空间 / 历史已完成 / 本次成功·失败·跳过。
 */
@Composable
fun HomeStatsGrid(
    uiState: HomeUiState,
    onRefresh: () -> Unit,
) {
    val state: MigrationState = uiState.state
    val hasSession = state.totalCount > 0 || state.status.isActive || state.status == MigrationStatus.COMPLETED

    val pendingCount = if (hasSession) state.remainingCount else (uiState.preview?.totalCount ?: 0)
    val pendingBytes = if (hasSession) state.totalBytes else (uiState.preview?.totalBytes ?: 0L)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "待迁移数量",
                value = if (uiState.previewLoading && !hasSession) "统计中…" else "${formatCount(pendingCount)} 个",
                icon = Icons.Filled.Folder,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                caption = if (hasSession) "本次会话剩余" else "来自相册扫描",
            )
            StatTile(
                label = "待迁移总大小",
                value = if (uiState.previewLoading && !hasSession) "统计中…" else formatBytes(pendingBytes),
                icon = Icons.Filled.Upload,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                caption = "预计可释放空间",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "已释放空间",
                value = formatBytes(uiState.freedBytes),
                icon = Icons.Filled.CheckCircle,
                tint = successColor(),
                modifier = Modifier.weight(1f),
                caption = "累计删除本地原图",
            )
            StatTile(
                label = "历史已完成",
                value = "${formatCount(uiState.migratedCount)} 个",
                icon = Icons.Filled.Sync,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
                caption = "断点续传记录",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "成功",
                value = "${state.successCount}",
                icon = Icons.Filled.CheckCircle,
                tint = successColor(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "失败",
                value = "${state.failedCount}",
                icon = Icons.Filled.Error,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
                caption = "记录 ${uiState.failureCount} 条",
            )
            StatTile(
                label = "跳过",
                value = "${state.skippedCount}",
                icon = Icons.Filled.Refresh,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = uiState.previewError ?: "统计口径：按设置中的排序与相册筛选，排除已迁移记录",
                style = MaterialTheme.typography.labelSmall,
                color = if (uiState.previewError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !uiState.previewLoading) {
                Text(text = if (uiState.previewLoading) "统计中…" else "刷新统计")
            }
        }
    }
}
