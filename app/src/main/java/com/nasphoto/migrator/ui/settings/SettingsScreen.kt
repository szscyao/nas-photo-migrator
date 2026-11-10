package com.nasphoto.migrator.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nasphoto.migrator.ui.components.AppTopBar
import com.nasphoto.migrator.ui.components.ConfirmDialog
import com.nasphoto.migrator.ui.permission.LocalPermissionActions
import com.nasphoto.migrator.ui.permission.LocalPermissionStatus
import com.nasphoto.migrator.ui.util.AppPermissions
import com.nasphoto.migrator.ui.util.rememberAppContainer

/**
 * 设置页：迁移规则 / 扫描范围 / 权限与后台保活 / 危险操作。
 *
 * 权限状态由 AppRoot 的 CompositionLocal 提供；电池优化状态在本页自行读取并在 ON_RESUME 时刷新
 * （系统「忽略电池优化」弹窗无法通过 ActivityResult 精确回调）。
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissions = LocalPermissionStatus.current
    val permissionActions = LocalPermissionActions.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showKeepAliveGuide by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // 电池优化白名单状态（从系统设置返回时刷新）
    var batteryOptimizationIgnored by remember {
        mutableStateOf(AppPermissions.isIgnoringBatteryOptimizations(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationIgnored = AppPermissions.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 切到「仅选中相册」时按需加载相册列表
    LaunchedEffect(uiState.settings.albumScope) {
        if (uiState.settings.albumScope == com.nasphoto.migrator.data.config.AlbumScope.SELECTED) {
            viewModel.loadAlbums()
        }
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "设置")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "migration_rules") {
                    MigrationRulesSection(
                        settings = uiState.settings,
                        onWifiOnlyChange = viewModel::setWifiOnly,
                        onVerifyMd5Change = viewModel::setVerifyMd5,
                        onLowBatteryPauseChange = viewModel::setLowBatteryPause,
                        onLowBatteryThresholdChange = viewModel::setLowBatteryThreshold,
                        onKeepAwakeChange = viewModel::setKeepAwake,
                    )
                }
                item(key = "scan_scope") {
                    ScanScopeSection(
                        settings = uiState.settings,
                        albums = uiState.albums,
                        albumsLoading = uiState.albumsLoading,
                        albumsError = uiState.albumsError,
                        onSortOrderChange = viewModel::setSortOrder,
                        onAlbumScopeChange = viewModel::setAlbumScope,
                        onToggleAlbum = viewModel::toggleAlbum,
                        onReloadAlbums = { viewModel.loadAlbums(force = true) },
                    )
                }
                item(key = "system") {
                    SystemSection(
                        permissions = permissions,
                        batteryOptimizationIgnored = batteryOptimizationIgnored,
                        onRequestAllFilesAccess = permissionActions.openAllFilesAccessSettings,
                        onRequestNotification = permissionActions.requestNotificationPermission,
                        onOpenBatterySettings = {
                            AppPermissions.startSafely(
                                context = context,
                                intent = AppPermissions.ignoreBatteryOptimizationsIntent(context),
                                fallback = AppPermissions.batteryOptimizationSettingsIntent(),
                            )
                        },
                        onOpenAppSettings = permissionActions.openAppSettings,
                        onShowKeepAliveGuide = { showKeepAliveGuide = true },
                    )
                }
                item(key = "danger") {
                    DangerSection(
                        migratedCount = uiState.migratedCount,
                        onResetClick = { showResetConfirm = true },
                    )
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

    if (showKeepAliveGuide) {
        KeepAliveGuideDialog(onDismiss = { showKeepAliveGuide = false })
    }

    if (showResetConfirm) {
        ConfirmDialog(
            title = "重置已完成迁移记录",
            message = "确定清空全部已完成迁移记录？\n\n" +
                "• 只清空本机的迁移记录，不删除手机上的照片/视频\n" +
                "• 不删除 NAS 上的任何文件\n" +
                "• 下次开始会重新扫描相册（NAS 上已存在且大小一致的文件会跳过并删除本地原图）",
            confirmText = "确认重置",
            destructive = true,
            onConfirm = {
                showResetConfirm = false
                viewModel.resetMigratedRecords()
            },
            onDismiss = { showResetConfirm = false },
        )
    }
}
