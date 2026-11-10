package com.nasphoto.migrator.business.guard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * 权限守卫（只读检查，绝不主动申请权限 —— 申请由 UI 层负责）。
 *
 * 阶段1 文档第 4 节锁定的分级策略：
 *  - 读取相册：
 *      API 33+ → READ_MEDIA_IMAGES + READ_MEDIA_VIDEO
 *      API 29~32 → READ_EXTERNAL_STORAGE
 *  - 删除本地原图（铁则 R2/R5 的前置条件）：
 *      API 30+ → MANAGE_EXTERNAL_STORAGE（「所有文件访问」，用 Environment.isExternalStorageManager 判定）
 *      API 29  → WRITE_EXTERNAL_STORAGE
 *
 * 为什么权限检查必须放在「每个文件处理前」而不是只在启动时检查一次：
 *  - 用户可能在中途去系统设置里撤销「所有文件访问」；
 *  - 一旦权限丢失，删除会失败（R5 要求的删除成功不可达成），必须暂停任务而不是把整队列文件
 *    全刷成 LOCal_DELETE_FAILED 失败记录。
 */
class PermissionGuard(context: Context) {

    private val appContext = context.applicationContext

    /** 相册/媒体读取权限是否具备 */
    fun hasMediaReadPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(Manifest.permission.READ_MEDIA_VIDEO)
        }

        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** 删除本地原图所需权限是否具备 */
    fun hasDeletePermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            // Android 11+ 分区存储强制，「所有文件访问」是静默删除相册原图的唯一途径
            Environment.isExternalStorageManager()
        }

        else -> {
            // Android 10：删除非本应用创建的媒体需要 WRITE_EXTERNAL_STORAGE
            granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** 通知权限（API 33+）；仅用于通知栏提示，缺失不阻断迁移 */
    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
