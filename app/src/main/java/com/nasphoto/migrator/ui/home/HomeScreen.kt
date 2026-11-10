package com.nasphoto.migrator.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nasphoto.migrator.R
import com.nasphoto.migrator.business.service.MigrationForegroundService
import com.nasphoto.migrator.ui.components.AppTopBar
import com.nasphoto.migrator.ui.neighbor.NeighborEntryCard
import com.nasphoto.migrator.ui.permission.LocalPermissionActions
import com.nasphoto.migrator.ui.permission.LocalPermissionStatus
import com.nasphoto.migrator.ui.util.formatCount
import com.nasphoto.migrator.ui.util.rememberAppContainer

/**
 * 首页（阶段4 核心页面）。
 *
 * 构成：
 *  1) 顶部：应用名 + 副标题；
 *  2) 网络邻居（推荐）入口卡片 → 自动扫描局域网 NAS 并生成迁移配置；
 *  3) 迁移目标 NAS 摘要（未配置时引导去配置页）；
 *  3) 权限缺失提示卡（缺相册/缺删图/缺通知，不阻断浏览）；
 *  4) 统计卡片：待迁移数量/大小、已释放空间、历史完成、成功/失败/跳过；
 *  5) 迁移状态卡：状态机 + 暂停原因 + 整体进度 + 当前文件/阶段 + NAS 剩余空间 + 网络电量；
 *  6) 控制按钮：开始/暂停/继续/停止（全部通过前台服务静态方法执行）。
 *
 * 所有业务动作只调用 ViewModel 与 [MigrationForegroundService] 静态方法，不直接触碰引擎命令。
 */
@Composable
fun HomeScreen(
    onNavigateToNas: () -> Unit,
    onNavigateToFailures: () -> Unit,
    onNavigateToNeighbor: () -> Unit,
) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val startPreview by viewModel.startPreview.collectAsStateWithLifecycle()
    val permissions = LocalPermissionStatus.current
    val permissionActions = LocalPermissionActions.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 相册权限授予后再统计（避免无权限时查询 MediaStore 抛 SecurityException）
    LaunchedEffect(permissions.mediaGranted) {
        if (permissions.mediaGranted) {
            viewModel.refreshPreview()
        }
    }

    // 一次性消息 → Snackbar
    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // 开始迁移前的权限硬校验 + 预览
    val onStartClick: () -> Unit = {
        when {
            uiState.activeConfig == null ->
                viewModel.showMessage("请先在「NAS」页面添加并激活一套配置")
            !permissions.mediaGranted ->
                viewModel.showMessage("缺少相册读取权限，请先在提示卡中授权")
            !permissions.deleteGranted ->
                viewModel.showMessage("缺少删除原图权限，请先开启「所有文件访问」")
            else -> viewModel.requestStartPreview()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(id = R.string.app_name),
                subtitle = "逐张上传 · 校验通过才删本地 · 失败保留",
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 「网络邻居（推荐）」入口：自动扫描同一 WiFi 下的 NAS 并生成配置
                item(key = "neighbor_entry") {
                    NeighborEntryCard(onClick = onNavigateToNeighbor)
                }
                item(key = "nas_target") {
                    NasTargetCard(
                        activeConfig = uiState.activeConfig,
                        configCount = uiState.configCount,
                        onNavigateToNas = onNavigateToNas,
                    )
                }
                item(key = "permissions") {
                    PermissionWarnings(
                        permissions = permissions,
                        actions = permissionActions,
                    )
                }
                item(key = "stats") {
                    HomeStatsGrid(
                        uiState = uiState,
                        onRefresh = viewModel::refreshPreview,
                    )
                }
                item(key = "status") {
                    MigrationStatusCard(state = uiState.state)
                }
                item(key = "controls") {
                    MigrationControlBar(
                        state = uiState.state,
                        onStart = onStartClick,
                        onPause = {
                            // 暂停/继续/停止统一走前台服务静态方法（服务内部调用引擎命令）
                            MigrationForegroundService.pause(context)
                        },
                        onResume = {
                            MigrationForegroundService.resume(context)
                        },
                        onStop = {
                            MigrationForegroundService.stop(context)
                            viewModel.showMessage("已请求停止：当前文件处理到安全点后结束（不删除未校验文件）")
                        },
                    )
                }
                if (uiState.failureCount > 0) {
                    item(key = "failures_entry") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "有 ${formatCount(uiState.failureCount)} 个文件迁移失败（本地已保留）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onNavigateToFailures) {
                                Text(text = "查看异常")
                            }
                        }
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

    // 开始前预览确认弹窗（数据来自 engine.loadPreview()）
    startPreview?.let { previewState ->
        StartPreviewDialog(
            state = previewState,
            configName = uiState.activeConfig?.name.orEmpty(),
            notificationGranted = permissions.notificationGranted,
            onConfirm = {
                val preview = previewState.preview
                viewModel.dismissStartPreview()
                if (preview != null && preview.totalCount > 0) {
                    // 必须通过前台服务启动：服务内部负责 startForeground + engine.startMigration()
                    MigrationForegroundService.start(context)
                    viewModel.showMessage("已开始迁移，可在通知栏查看进度")
                }
            },
            onDismiss = viewModel::dismissStartPreview,
        )
    }
}
