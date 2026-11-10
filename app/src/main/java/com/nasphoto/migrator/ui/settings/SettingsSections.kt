package com.nasphoto.migrator.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nasphoto.migrator.data.config.AlbumScope
import com.nasphoto.migrator.data.config.AppSettings
import com.nasphoto.migrator.data.config.MediaSortOrder
import com.nasphoto.migrator.data.gallery.AlbumInfo
import com.nasphoto.migrator.ui.components.ChoiceChip
import com.nasphoto.migrator.ui.components.LoadingRow
import com.nasphoto.migrator.ui.components.SectionCard
import com.nasphoto.migrator.ui.components.StatusPill
import com.nasphoto.migrator.ui.permission.PermissionStatus
import com.nasphoto.migrator.ui.theme.successColor
import com.nasphoto.migrator.ui.theme.warningColor
import com.nasphoto.migrator.ui.util.formatBytes
import com.nasphoto.migrator.ui.util.formatCount
import kotlin.math.roundToInt

// ============================================================================
// 设置页各分区（迁移规则 / 扫描范围 / 权限与保活 / 危险操作）+ 后台保活说明弹窗
// ============================================================================

/** 设置页通用「标题 + 说明 + 开关」行 */
@Composable
fun SettingSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 迁移规则分区：仅 WiFi / MD5 校验 / 低电量自动暂停（含阈值）/ 保持唤醒 */
@Composable
fun MigrationRulesSection(
    settings: AppSettings,
    onWifiOnlyChange: (Boolean) -> Unit,
    onVerifyMd5Change: (Boolean) -> Unit,
    onLowBatteryPauseChange: (Boolean) -> Unit,
    onLowBatteryThresholdChange: (Int) -> Unit,
    onKeepAwakeChange: (Boolean) -> Unit,
) {
    SectionCard(title = "迁移规则") {
        SettingSwitchRow(
            title = "仅 WiFi 迁移",
            description = "移动网络时自动暂停，WiFi 恢复后自动继续",
            checked = settings.wifiOnly,
            onCheckedChange = onWifiOnlyChange,
        )
        SettingSwitchRow(
            title = "MD5 完整性校验",
            description = "默认「大小一致」校验已满足铁则（校验通过才删本地）；开启后额外比对 MD5，" +
                "更严格但每张多读一遍本地文件，速度更慢",
            checked = settings.verifyMd5,
            onCheckedChange = onVerifyMd5Change,
        )
        SettingSwitchRow(
            title = "低电量自动暂停",
            description = "电量低于阈值时暂停迁移，充电后自动继续",
            checked = settings.lowBatteryPause,
            onCheckedChange = onLowBatteryPauseChange,
        )
        if (settings.lowBatteryPause) {
            val threshold = settings.lowBatteryThreshold
            var sliderValue by remember(threshold) { mutableStateOf(threshold.toFloat()) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = "低电量阈值", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${sliderValue.roundToInt()}%（范围 5%~50%）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 5f..50f,
                    steps = 44,
                    onValueChangeFinished = { onLowBatteryThresholdChange(sliderValue.roundToInt()) },
                )
            }
        }
        SettingSwitchRow(
            title = "迁移期间保持唤醒",
            description = "持有 PARTIAL_WAKE_LOCK，防止息屏后网络断开导致迁移暂停",
            checked = settings.keepAwake,
            onCheckedChange = onKeepAwakeChange,
        )
    }
}

/** 扫描范围分区：排序方式 + 相册筛选（全部/仅选中，按需加载相册列表） */
@Composable
fun ScanScopeSection(
    settings: AppSettings,
    albums: List<AlbumInfo>,
    albumsLoading: Boolean,
    albumsError: String?,
    onSortOrderChange: (MediaSortOrder) -> Unit,
    onAlbumScopeChange: (AlbumScope) -> Unit,
    onToggleAlbum: (String) -> Unit,
    onReloadAlbums: () -> Unit,
) {
    SectionCard(title = "扫描范围") {
        Text(
            text = "排序方式（决定先传哪一张，不改变单张串行规则）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MediaSortOrder.entries.forEach { order ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSortOrderChange(order) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.sortOrder == order,
                    onClick = { onSortOrderChange(order) },
                )
                Text(text = order.label, style = MaterialTheme.typography.bodyMedium)
            }
        }

        HorizontalDivider()

        Text(
            text = "相册筛选",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AlbumScope.entries.forEach { scope ->
                ChoiceChip(
                    text = scope.label,
                    selected = settings.albumScope == scope,
                    onClick = { onAlbumScopeChange(scope) },
                )
            }
        }
        if (settings.albumScope == AlbumScope.SELECTED) {
            when {
                albumsLoading -> LoadingRow(text = "正在读取相册…")

                albumsError != null -> {
                    Text(
                        text = albumsError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onReloadAlbums) { Text(text = "重新读取") }
                }

                albums.isEmpty() -> {
                    Text(
                        text = "没有读取到相册（请检查相册读取权限）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onReloadAlbums) { Text(text = "重新读取") }
                }

                else -> {
                    albums.forEach { album ->
                        val checked = album.albumId in settings.selectedAlbumIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleAlbum(album.albumId) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggleAlbum(album.albumId) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = album.albumName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${formatCount(album.itemCount)} 项 · ${formatBytes(album.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "已选 ${settings.selectedAlbumIds.size} / ${albums.size} 个相册",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onReloadAlbums) { Text(text = "刷新列表") }
                    }
                }
            }
        } else {
            Text(
                text = "将扫描全部相册中的图片与视频（排除已迁移记录）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 权限与保活分区：所有文件访问 / 通知权限 / 电池优化 / 后台保活说明入口 */
@Composable
fun SystemSection(
    permissions: PermissionStatus,
    batteryOptimizationIgnored: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onShowKeepAliveGuide: () -> Unit,
) {
    SectionCard(title = "权限与后台保活") {
        SettingStatusRow(
            title = "所有文件访问",
            description = "Android 11+ 删除本地原图必需（严格通过 MediaStore 操作）",
            statusText = if (permissions.deleteGranted) "已授权" else "未授权",
            statusOk = permissions.deleteGranted,
            actionText = if (permissions.deleteGranted) "去查看" else "去开启",
            onAction = onRequestAllFilesAccess,
        )
        SettingStatusRow(
            title = "通知权限",
            description = "用于通知栏展示迁移进度",
            statusText = if (permissions.notificationGranted) "已授权" else "未授权",
            statusOk = permissions.notificationGranted,
            actionText = if (permissions.notificationGranted) "去查看" else "去授权",
            onAction = onRequestNotification,
        )
        SettingStatusRow(
            title = "电池优化",
            description = "设为「不优化」可避免迁移期间被系统冻结",
            statusText = if (batteryOptimizationIgnored) "已忽略电池优化" else "未忽略（可能被限制）",
            statusOk = batteryOptimizationIgnored,
            actionText = "去设置",
            onAction = onOpenBatterySettings,
        )
        SettingStatusRow(
            title = "相册读取权限",
            description = "读取本地照片/视频并统计待迁移数据",
            statusText = if (permissions.mediaGranted) "已授权" else "未授权",
            statusOk = permissions.mediaGranted,
            actionText = "应用设置",
            onAction = onOpenAppSettings,
        )

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onShowKeepAliveGuide)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "后台保活说明",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "华为 / 小米 / OPPO / vivo 等机型设置要点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "查看",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            text = "权限状态以系统实际授权为准；从系统设置返回本页后会自动刷新。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 权限状态行（标题 + 胶囊状态 + 说明 + 跳转按钮） */
@Composable
private fun SettingStatusRow(
    title: String,
    description: String,
    statusText: String,
    statusOk: Boolean,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                StatusPill(
                    text = statusText,
                    color = if (statusOk) successColor() else warningColor(),
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAction) {
            Text(text = actionText)
        }
    }
}

/** 危险操作分区：重置已完成迁移记录（只清记录，不删文件） */
@Composable
fun DangerSection(
    migratedCount: Int,
    onResetClick: () -> Unit,
) {
    SectionCard(title = "危险操作") {
        Text(
            text = "已完成迁移记录：${formatCount(migratedCount)} 条",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "重置后会重新扫描全部相册；NAS 端已存在且大小一致的文件会被跳过（不会重复上传），" +
                "不会删除手机或 NAS 上的任何文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onResetClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "重置已完成迁移记录")
        }
    }
}

/** 后台保活说明弹窗（长文案，可滚动） */
@Composable
fun KeepAliveGuideDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "后台保活说明",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = GuideTexts.KEEP_ALIVE_GUIDE,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) {
                        Text(text = "我知道了")
                    }
                }
            }
        }
    }
}
