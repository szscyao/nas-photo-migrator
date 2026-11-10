package com.nasphoto.migrator.ui.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nasphoto.migrator.ui.permission.PermissionStatus

/**
 * 权限相关的系统能力封装（纯函数 + Intent 构造，UI 只调用这里，不散落 API 判断）。
 *
 * 权限矩阵（阶段1 第 4 节，锁定）：
 *  - API 33+：READ_MEDIA_IMAGES + READ_MEDIA_VIDEO + POST_NOTIFICATIONS
 *  - API 29~32：READ_EXTERNAL_STORAGE（API 29 额外需要 WRITE_EXTERNAL_STORAGE 才能删除原图）
 *  - API 30+：MANAGE_EXTERNAL_STORAGE（「所有文件访问」，一次性授权后 ContentResolver.delete 静默删图）
 */
object AppPermissions {

    /** 读取相册所需的运行时权限（按 API 分级；API 29 删除原图还需写权限） */
    fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }

    /** 是否已获得相册读取权限 */
    fun hasMediaPermission(context: Context): Boolean =
        mediaPermissions().all { isGranted(context, it) }

    /** 是否已获得通知权限（API 33 以下视为已授予） */
    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** 是否已获得「所有文件访问」授权（API 30 以下视为已授予） */
    fun isAllFilesAccessGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** 是否具备删除本地原图的权限（开始迁移前必须校验） */
    fun hasDeletePermission(context: Context): Boolean =
        isAllFilesAccessGranted() &&
            (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q ||
                isGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE))

    /** 是否已被加入电池优化白名单 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 读取当前权限快照（组合成一个不可变状态给 UI 用） */
    fun read(context: Context): PermissionStatus = PermissionStatus(
        mediaGranted = hasMediaPermission(context),
        deleteGranted = hasDeletePermission(context),
        notificationGranted = hasNotificationPermission(context),
    )

    // ---------------- 系统设置页跳转 Intent ----------------

    /** 本应用的「所有文件访问」授权页（API 30+）；低版本退化为应用详情页 */
    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            appDetailsIntent(context)
        }

    /** 「所有文件访问」授权页打开失败时的兜底（全部应用列表） */
    fun allFilesAccessFallbackIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }

    /** 请求忽略电池优化（系统弹窗） */
    fun ignoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )

    /** 电池优化设置列表页（请求弹窗不可用时的兜底） */
    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 本应用详情页（权限、通知等总入口） */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )

    /** 安全打开系统页：主 Intent 失败时尝试兜底 Intent，绝不抛到 UI */
    fun startSafely(context: Context, intent: Intent, fallback: Intent? = null) {
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched && fallback != null) {
            runCatching { context.startActivity(fallback) }
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
