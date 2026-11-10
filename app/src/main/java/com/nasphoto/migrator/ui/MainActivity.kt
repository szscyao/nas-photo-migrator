package com.nasphoto.migrator.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nasphoto.migrator.ui.navigation.AppNavHost
import com.nasphoto.migrator.ui.permission.LocalPermissionActions
import com.nasphoto.migrator.ui.permission.LocalPermissionStatus
import com.nasphoto.migrator.ui.permission.PermissionActions
import com.nasphoto.migrator.ui.util.AppPermissions
import com.nasphoto.migrator.ui.theme.NasMigratorTheme

/**
 * 单 Activity 入口。
 *
 * 职责：仅负责主题 + 根组合；权限申请与导航全部在 Compose 层（[AppRoot]）完成。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式（边到边）：状态栏/导航栏背景透明，跟随后续 Compose 主题明暗
        enableEdgeToEdge()
        setContent {
            NasMigratorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

/**
 * 应用根组合：权限状态收集 + 授权跳转 + 导航宿主。
 *
 * 权限流程（阶段1 第 4 节）：
 *  1) 首次进入首页时申请相册读取权限（API 33+ 拆分；API 29~32 传统读权限），
 *     同时申请 POST_NOTIFICATIONS（API 33+）；
 *  2) API 30+ 的「所有文件访问」（MANAGE_EXTERNAL_STORAGE）通过 Intent 跳系统设置页引导，
 *     用 Environment.isExternalStorageManager() 判断；
 *  3) 从系统设置返回（ON_RESUME）自动刷新权限状态；
 *  4) 权限缺失只在首页显示醒目卡片，「开始迁移」前强制校验（缺权限不允许开始）。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    var permissionStatus by remember { mutableStateOf(AppPermissions.read(context)) }

    // 相册/通知权限申请结果回调
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionStatus = AppPermissions.read(context)
    }

    // 「所有文件访问」等系统设置页返回回调
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionStatus = AppPermissions.read(context)
    }

    val permissionActions = remember(context) {
        PermissionActions(
            requestMediaPermissions = {
                permissionLauncher.launch(AppPermissions.mediaPermissions())
            },
            requestNotificationPermission = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            },
            openAllFilesAccessSettings = {
                val launched = runCatching {
                    settingsLauncher.launch(AppPermissions.allFilesAccessIntent(context))
                }.isSuccess
                if (!launched) {
                    runCatching {
                        settingsLauncher.launch(AppPermissions.allFilesAccessFallbackIntent())
                    }
                }
            },
            openAppSettings = {
                runCatching {
                    settingsLauncher.launch(AppPermissions.appDetailsIntent(context))
                }
            },
        )
    }

    // 从系统设置/授权页返回时刷新权限（覆盖「所有文件访问」这类无法用回调精确获知的权限）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionStatus = AppPermissions.read(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 首次进入首页自动申请「相册读取 + 通知」权限（只自动弹一次，避免骚扰）
    var autoRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoRequested) return@LaunchedEffect
        autoRequested = true
        val toRequest = buildList {
            if (!permissionStatus.mediaGranted) {
                addAll(AppPermissions.mediaPermissions())
            }
            if (!permissionStatus.notificationGranted &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    CompositionLocalProvider(
        LocalPermissionStatus provides permissionStatus,
        LocalPermissionActions provides permissionActions,
    ) {
        AppNavHost()
    }
}
