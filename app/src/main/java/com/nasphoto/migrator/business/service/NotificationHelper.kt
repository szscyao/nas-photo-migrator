package com.nasphoto.migrator.business.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nasphoto.migrator.R
import com.nasphoto.migrator.business.model.MigrationState
import com.nasphoto.migrator.business.model.MigrationStatus
import java.util.Locale

/**
 * 前台服务通知构建器。
 *
 * 设计要点：
 *  - 渠道 IMPORTANCE_LOW：迁移是长任务，禁止声音/振动打扰（阶段1 技术选型 + 任务书要求）；
 *  - 通知内容全部来自 [MigrationState] 快照（引擎是唯一数据源），本类不持有任何业务状态；
 *  - 暂停/继续/停止三个动作按钮通过 [PendingIntent.getService] 回到
 *    [MigrationForegroundService] 的对应 action，避免在通知回调里直接操作引擎；
 *  - 点击通知正文拉起应用首页：用 PackageManager 的 launch intent，
 *    这样业务层不依赖 UI 层的 MainActivity 类（保持分层单向依赖）。
 */
object NotificationHelper {

    /** 通知渠道 ID（与 MigrationForegroundService 共用） */
    const val CHANNEL_ID = "nas_migration"

    /** 迁移进度通知 ID（固定，后续更新同一条） */
    const val NOTIFICATION_ID = 1001

    private const val REQUEST_CODE_PAUSE = 100
    private const val REQUEST_CODE_RESUME = 101
    private const val REQUEST_CODE_STOP = 102
    private const val REQUEST_CODE_CONTENT = 103

    /** 创建通知渠道（幂等，可在 Service.onCreate 每次调用） */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_migration_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_migration_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** 服务刚启动、尚无引擎状态时的占位通知（必须立刻 startForeground） */
    fun buildPreparing(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.svc_notif_title_preparing))
            .setContentText(context.getString(R.string.svc_notif_text_preparing))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(0, 0, true)
            .setContentIntent(contentPendingIntent(context))
            .build()

    /** 按引擎状态构建进度通知 */
    fun build(context: Context, state: MigrationState): Notification {
        val title = titleFor(context, state)
        val progressText = context.getString(
            R.string.notif_progress_text,
            state.successCount,
            state.totalCount,
            formatBytes(state.freedBytes),
        )
        val detail = buildDetail(context, state)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(progressText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(state.status == MigrationStatus.RUNNING || state.status == MigrationStatus.PAUSED)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPendingIntent(context))

        // 进度条：有总文件数时用总体百分比，否则用不确定进度（准备/暂停且总数为 0）
        if (state.totalCount > 0) {
            val percent = (state.overallProgress * 100).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        // 次行：当前文件
        if (state.currentFileName.isNotBlank()) {
            builder.setSubText(context.getString(R.string.notif_current_file, state.currentFileName))
        } else if (state.status == MigrationStatus.PAUSED && state.pauseReason.description.isNotEmpty()) {
            builder.setSubText(context.getString(R.string.notif_waiting_reason, state.pauseReason.description))
        }

        // 动作按钮（暂停 / 继续 / 停止）
        when (state.status) {
            MigrationStatus.RUNNING -> {
                builder.addAction(
                    0,
                    context.getString(R.string.notif_action_pause),
                    servicePendingIntent(context, MigrationForegroundService.ACTION_PAUSE, REQUEST_CODE_PAUSE),
                )
                builder.addAction(
                    0,
                    context.getString(R.string.notif_action_stop),
                    servicePendingIntent(context, MigrationForegroundService.ACTION_STOP, REQUEST_CODE_STOP),
                )
            }

            MigrationStatus.PAUSED -> {
                builder.addAction(
                    0,
                    context.getString(R.string.notif_action_resume),
                    servicePendingIntent(context, MigrationForegroundService.ACTION_RESUME, REQUEST_CODE_RESUME),
                )
                builder.addAction(
                    0,
                    context.getString(R.string.notif_action_stop),
                    servicePendingIntent(context, MigrationForegroundService.ACTION_STOP, REQUEST_CODE_STOP),
                )
            }

            MigrationStatus.STOPPING -> {
                builder.addAction(
                    0,
                    context.getString(R.string.notif_action_stop),
                    servicePendingIntent(context, MigrationForegroundService.ACTION_STOP, REQUEST_CODE_STOP),
                )
            }

            else -> Unit
        }

        return builder.build()
    }

    /** 标题：运行/暂停/完成/停止/等待条件 */
    private fun titleFor(context: Context, state: MigrationState): String = when (state.status) {
        MigrationStatus.RUNNING -> context.getString(R.string.notif_title_running)
        MigrationStatus.PAUSED -> context.getString(R.string.notif_title_paused)
        MigrationStatus.COMPLETED -> context.getString(R.string.notif_title_completed)
        MigrationStatus.STOPPING -> context.getString(R.string.svc_notif_stopping)
        MigrationStatus.IDLE -> context.getString(R.string.notif_title_stopped)
        MigrationStatus.FAILED -> context.getString(R.string.notif_title_stopped)
    }

    /** 展开后的详细信息（阶段/失败数/NAS 剩余空间） */
    private fun buildDetail(context: Context, state: MigrationState): String {
        val sb = StringBuilder()
        sb.append(
            context.getString(
                R.string.notif_progress_text,
                state.successCount,
                state.totalCount,
                formatBytes(state.freedBytes),
            ),
        )
        if (state.currentFileName.isNotBlank()) {
            sb.append('\n').append(context.getString(R.string.notif_current_file, state.currentFileName))
        }
        // 暂停原因放在展开详情里，折叠态标题保持「迁移已暂停」的稳定语义
        if (state.status == MigrationStatus.PAUSED && state.pauseReason.description.isNotEmpty()) {
            sb.append('\n').append(
                context.getString(R.string.notif_waiting_reason, state.pauseReason.description),
            )
        }
        if (state.currentStage.isNotBlank()) {
            sb.append('\n').append(context.getString(R.string.svc_notif_stage, state.currentStage))
        }
        if (state.currentFileRetry > 0) {
            sb.append('\n').append(context.getString(R.string.svc_notif_retry, state.currentFileRetry))
        }
        if (state.failedCount > 0) {
            sb.append('\n').append(context.getString(R.string.notif_failed_count, state.failedCount))
        }
        state.nasFreeSpaceBytes?.let { free ->
            sb.append('\n').append(context.getString(R.string.svc_notif_nas_free_space, formatBytes(free)))
        }
        return sb.toString()
    }

    /** 通知正文点击：拉起应用（不直接依赖 UI 层 Activity 类名） */
    private fun contentPendingIntent(context: Context): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_CONTENT,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 通知动作按钮：回到本服务的对应 action */
    private fun servicePendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, MigrationForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 1.2 GB 之类的人类可读格式（通知栏用，使用默认 Locale） */
    fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "-"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }
}
