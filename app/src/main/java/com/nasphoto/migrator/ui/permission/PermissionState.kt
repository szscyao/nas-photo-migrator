package com.nasphoto.migrator.ui.permission

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 权限状态快照（由 MainActivity/AppRoot 在 onCreate、授权回调、ON_RESUME 时刷新）。
 *
 * UI 层只读本状态做展示与前置校验，不直接调用 checkSelfPermission。
 */
data class PermissionStatus(
    /** 相册读取权限（API 33+ 为 READ_MEDIA_IMAGES/VIDEO；33 以下为 READ_EXTERNAL_STORAGE） */
    val mediaGranted: Boolean = false,
    /** 删除原图权限（API 30+ 为「所有文件访问」；API 29 为写权限） */
    val deleteGranted: Boolean = true,
    /** 通知权限（API 33+ 运行时权限） */
    val notificationGranted: Boolean = true,
) {
    /** 开始迁移的硬性前提（缺相册读权限或删图权限都不允许开始） */
    val readyForMigration: Boolean get() = mediaGranted && deleteGranted

    val allGranted: Boolean get() = mediaGranted && deleteGranted && notificationGranted
}

/**
 * 权限请求动作集合（由 AppRoot 用 ActivityResult launcher 实现后下发）。
 *
 * 页面通过 CompositionLocal 拿到动作，从而无需持有 Activity。
 */
class PermissionActions(
    /** 申请相册读取权限（按 API 分级自动选择权限数组） */
    val requestMediaPermissions: () -> Unit,
    /** 申请通知权限（API 33+） */
    val requestNotificationPermission: () -> Unit,
    /** 跳转「所有文件访问」系统授权页 */
    val openAllFilesAccessSettings: () -> Unit,
    /** 跳转本应用系统详情页 */
    val openAppSettings: () -> Unit,
)

/** 当前权限状态（默认全未授予，由 AppRoot 提供真实值） */
val LocalPermissionStatus = staticCompositionLocalOf { PermissionStatus() }

/** 权限动作集合（AppRoot 必须提供） */
val LocalPermissionActions = staticCompositionLocalOf<PermissionActions> {
    error("LocalPermissionActions 未在 AppRoot 中提供")
}
