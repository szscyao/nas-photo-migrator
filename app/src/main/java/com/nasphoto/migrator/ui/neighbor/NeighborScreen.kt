package com.nasphoto.migrator.ui.neighbor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nasphoto.migrator.ui.components.AppTopBar
import com.nasphoto.migrator.ui.components.ConfirmDialog
import com.nasphoto.migrator.ui.components.SectionCard
import com.nasphoto.migrator.ui.components.StatusPill
import com.nasphoto.migrator.ui.theme.warningColor
import com.nasphoto.migrator.ui.util.formatDuration
import com.nasphoto.migrator.ui.util.rememberAppContainer

/**
 * 网络邻居向导入口（单个导航目的地 "neighbor"，内部状态机 SCAN → SHARES → BROWSE）。
 *
 * 导航约定：
 *  - 扫描页（SCAN）按返回/左上角返回 → [onExit]（导航弹出回首页）；
 *  - 共享列表/目录浏览：系统返回键由 [BackHandler] 逐级回退（[NeighborStep] 递减）；
 *  - 生成配置成功后弹窗提供「返回首页」→ [onNavigateHome]。
 */
@Composable
fun NeighborScreen(
    onExit: () -> Unit,
    onNavigateHome: () -> Unit,
) {
    val container = rememberAppContainer()
    val viewModel: NeighborViewModel = viewModel(factory = NeighborViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 从系统页面/后台回到前台时刷新 WiFi 状态（用户可能刚连上 WiFi）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // 系统返回键：SHARES/BROWSE 逐级返回；SCAN 交给导航（回到首页）
    BackHandler(enabled = state.step != NeighborStep.SCAN) {
        when (state.step) {
            NeighborStep.BROWSE -> viewModel.navigateUp()
            NeighborStep.SHARES -> viewModel.backToScan()
            NeighborStep.SCAN -> Unit
        }
    }

    // 全屏向导页没有底部导航栏，自行处理系统导航栏 inset（边到边显示）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        when (state.step) {
            NeighborStep.SCAN -> ScanContent(
                state = state,
                viewModel = viewModel,
                onExit = onExit,
            )

            NeighborStep.SHARES -> ShareListScreen(
                state = state,
                viewModel = viewModel,
            )

            NeighborStep.BROWSE -> DirectoryBrowserScreen(
                state = state,
                viewModel = viewModel,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    // ---------------- 弹窗 ----------------
    if (state.showManualRangeDialog) {
        ManualRangeDialog(state = state, viewModel = viewModel)
    }

    state.authDialog?.let { dialog ->
        AuthDialog(
            state = dialog,
            onSubmit = viewModel::submitCredentials,
            onDismiss = viewModel::cancelAuth,
        )
    }

    state.pendingDeleteDevice?.let { device ->
        ConfirmDialog(
            title = "删除设备",
            message = "确定从「我的设备」中删除「${device.displayName}」？\n\n" +
                "只会移除本机保存的设备记录与加密凭据，不影响 NAS 上的任何文件。",
            confirmText = "删除",
            destructive = true,
            onConfirm = viewModel::confirmDeleteDevice,
            onDismiss = viewModel::cancelDeleteDevice,
        )
    }

    state.createdConfigName?.let { configName ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCreatedConfig,
            title = { Text(text = "已生成 NAS 配置") },
            text = {
                Text(
                    text = "已生成配置「$configName」并设为迁移目标。\n\n" +
                        "回到首页即可开始迁移（仍严格逐张：上传 → 校验 → 删除本地原图）。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissCreatedConfig()
                        onNavigateHome()
                    },
                ) {
                    Text(text = "返回首页")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCreatedConfig) {
                    Text(text = "留在此页")
                }
            },
        )
    }
}

/**
 * 首页「网络邻居（推荐）」入口卡片（放在迁移目标 NAS 卡之前）。
 */
@Composable
fun NeighborEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(30.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "网络邻居（推荐）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "自动扫描同一 WiFi 下的 NAS，逐级点选文件夹，自动生成迁移配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = "去扫描",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

// ============================================================================
// 扫描页
// ============================================================================

@Composable
private fun ScanContent(
    state: NeighborUiState,
    viewModel: NeighborViewModel,
    onExit: () -> Unit,
) {
    val savedDevices = state.savedDevices
    val scannedDevices = state.scannedDevices
    val scanError = state.scanError

    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            title = "网络邻居",
            subtitle = when {
                state.scanning -> "正在扫描 ${state.scanRange?.display ?: "本网段"}…"
                state.wifiConnected -> "同一 WiFi 自动发现 NAS 设备"
                else -> "请连接 WiFi 后扫描"
            },
            navigationIcon = {
                IconButton(onClick = onExit) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回首页")
                }
            },
            actions = {
                IconButton(
                    onClick = viewModel::rescan,
                    enabled = state.wifiConnected && !state.scanning,
                ) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = "重新扫描")
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.wifiConnected) {
                item(key = "wifi_warning") {
                    WifiWarningCard(onRefresh = viewModel::onResume)
                }
            }

            if (state.scanning) {
                item(key = "scan_progress") {
                    ScanProgressCard(state = state, onCancel = viewModel::cancelScan)
                }
            }

            item(key = "scan_actions") {
                ScanActionsCard(
                    state = state,
                    onRescan = viewModel::rescan,
                    onManualRange = viewModel::openManualRangeDialog,
                    enabled = state.wifiConnected && !state.scanning,
                )
            }

            if (scanError != null) {
                item(key = "scan_error") {
                    ScanErrorCard(
                        message = scanError,
                        onRetry = viewModel::rescan,
                        onManualRange = viewModel::openManualRangeDialog,
                    )
                }
            }

            if (savedDevices.isNotEmpty()) {
                item(key = "saved_header") {
                    DeviceSectionHeader(
                        title = "我的设备（${savedDevices.size}）",
                        subtitle = "已保存的设备，点击进入；可删除",
                    )
                }
                items(items = savedDevices, key = { "saved-${it.ip}" }) { device ->
                    DeviceRow(
                        device = device,
                        onClick = { viewModel.openDevice(device) },
                        onDelete = { viewModel.requestDeleteDevice(device) },
                    )
                }
            }

            if (scannedDevices.isNotEmpty()) {
                item(key = "scanned_header") {
                    DeviceSectionHeader(
                        title = "扫描发现（${scannedDevices.size}）",
                        subtitle = "本次扫描新发现，点击进入",
                    )
                }
                items(items = scannedDevices, key = { "scan-${it.ip}" }) { device ->
                    DeviceRow(
                        device = device,
                        onClick = { viewModel.openDevice(device) },
                        onDelete = null,
                    )
                }
            }

            if (!state.scanning && !state.hasAnyDevice) {
                item(key = "scan_empty") { ScanEmptyHint() }
            }
        }
    }
}

@Composable
private fun WifiWarningCard(onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "请连接 WiFi 后扫描",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "网络邻居只扫描当前 WiFi 所在网段；移动网络下无法发现 NAS。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onRefresh) {
                Text(text = "刷新", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun ScanProgressCard(state: NeighborUiState, onCancel: () -> Unit) {
    SectionCard(title = "正在扫描") {
        val fraction = if (state.scanTotal > 0) {
            state.scanned.toFloat() / state.scanTotal.toFloat()
        } else {
            0f
        }
        LinearProgressIndicator(
            progress = { fraction },
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
                text = "已扫描 ${state.scanned} / ${state.scanTotal}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "已发现 ${state.scanOpenCount} 台",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        state.scanRange?.let { range ->
            Text(
                text = "扫描范围：${range.display}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(onClick = onCancel) {
                Text(text = "取消扫描")
            }
        }
    }
}

@Composable
private fun ScanActionsCard(
    state: NeighborUiState,
    onRescan: () -> Unit,
    onManualRange: () -> Unit,
    enabled: Boolean,
) {
    SectionCard {
        when {
            state.scanFinished && state.scanError == null -> {
                Text(
                    text = "扫描完成",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "共扫描 ${state.scanned} 个地址，发现 ${state.scanOpenCount} 台 SMB 设备，" +
                        "耗时 ${formatDuration(state.scanElapsedMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            !state.scanning -> {
                Text(
                    text = "开始扫描",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "自动扫描同一 WiFi 下所有开放 445（SMB）端口的设备，最多 15 秒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRescan,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = if (state.scanFinished) "重新扫描" else "开始扫描")
            }
            OutlinedButton(
                onClick = onManualRange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "手动网段")
            }
        }
    }
}

@Composable
private fun ScanErrorCard(
    message: String,
    onRetry: () -> Unit,
    onManualRange: () -> Unit,
) {
    SectionCard(title = "扫描提示") {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) { Text(text = "重试") }
            TextButton(onClick = onManualRange) { Text(text = "手动网段") }
        }
    }
}

@Composable
private fun DeviceSectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceRow(
    device: NeighborDeviceItem,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (device.fromSaved) {
                        StatusPill(
                            text = "已保存",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (device.needsAuth) {
                        StatusPill(
                            text = "需要账号密码",
                            color = warningColor(),
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(device.ip)
                        device.shareCount?.let { count -> append(" · ").append("$count 个共享") }
                        if (device.hostName.isNullOrBlank()) append(" · 未获取到设备名")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除设备",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScanEmptyHint() {
    SectionCard(title = "没有发现 SMB 设备？按下面几点排查") {
        EmptyHintLine("手机和 NAS 是否连接同一个 WiFi（同一路由器、同一网段）")
        EmptyHintLine("路由器是否开启了「AP 隔离 / 访客网络隔离」，开启时设备之间互相看不到")
        EmptyHintLine("NAS 是否已开启 SMB 服务（群晖：控制面板 → 文件服务 → SMB）")
        EmptyHintLine("可以点「手动网段」直接填写 NAS 所在地址段再扫描")
        Text(
            text = "如果 NAS 与手机不在同一网段（如跨 VLAN），请使用「手动网段」或改用 NAS 配置页手动添加。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyHintLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManualRangeDialog(state: NeighborUiState, viewModel: NeighborViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissManualRangeDialog,
        title = { Text(text = "手动填写网段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "填写起止 IP 地址，扫描该范围内的 SMB 设备（最多 1024 个地址）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.manualRangeStart,
                    onValueChange = viewModel::setManualRangeStart,
                    label = { Text(text = "起始 IP") },
                    placeholder = { Text(text = "192.168.1.1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.manualRangeEnd,
                    onValueChange = viewModel::setManualRangeEnd,
                    label = { Text(text = "结束 IP") },
                    placeholder = { Text(text = "192.168.1.254") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.manualRangeError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmManualRange) {
                Text(text = "开始扫描")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissManualRangeDialog) {
                Text(text = "取消")
            }
        },
    )
}
