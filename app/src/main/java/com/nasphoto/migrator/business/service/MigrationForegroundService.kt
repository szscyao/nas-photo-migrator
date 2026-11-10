package com.nasphoto.migrator.business.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nasphoto.migrator.MigratorApp
import com.nasphoto.migrator.business.engine.MigrationEngine
import com.nasphoto.migrator.business.engine.MigrationEngineApi
import com.nasphoto.migrator.business.model.MigrationStatus
import com.nasphoto.migrator.business.model.PauseReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 迁移前台服务（`foregroundServiceType="dataSync"`）。
 *
 * 职责（只做三件事，绝不参与迁移决策）：
 *  1. 让进程在息屏/后台时不被系统回收（配合 PARTIAL_WAKE_LOCK）；
 *  2. 把引擎的 [MigrationEngineApi.state] 渲染成通知栏进度（节流 ≥500ms）；
 *  3. 接收通知栏的 暂停/继续/停止 动作并转发给引擎。
 *
 * 与铁则的关系：
 *  - R1：服务**不执行任何文件处理**，只在主线程观察状态；文件处理永远在引擎的串行会话协程里；
 *  - R4：服务被系统杀死后**不会自动重启迁移**（START_STICKY 重启时 intent 为 null，此时只做状态校验，
 *    不主动 startMigration）；用户点「开始」才会从断点继续（已完成 fileKey 已落库，不会重复上传）。
 *
 * API 差异处理：
 *  - API 29+：`ServiceCompat.startForeground` 传 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`；
 *  - API 34+：dataSync 前台服务必须声明 `FOREGROUND_SERVICE_DATA_SYNC` 权限（manifest 已声明），
 *    且**从后台启动前台服务会被系统拒绝**（ForegroundServiceStartNotAllowedException）——
 *    本服务的启动入口只用 `ContextCompat.startForegroundService`，且产品上只由 UI 在前台点击触发；
 *  - API 33+：未授予 POST_NOTIFICATIONS 时通知不显示，但服务仍可运行（不阻断迁移，只记 log）；
 *  - API 35+：dataSync 前台服务单次运行上限 6 小时，超时会回调 [onTimeout]，
 *    这里主动暂停任务并把暂停原因写成「前台服务时长到达系统上限」，用户可再次开始继续。
 */
class MigrationForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var engine: MigrationEngineApi

    private var wakeLock: PowerManager.WakeLock? = null

    /** 是否曾经观察到活动状态（RUNNING/PAUSED/STOPPING）；避免服务刚启动时把初始 IDLE 当成「已结束」 */
    @Volatile
    private var hasSeenActiveState = false

    /** 用户显式点击「停止」：即使从未进入活动状态也要结束服务 */
    @Volatile
    private var explicitStop = false

    /** 服务是否已进入结束流程（防止观察者/看门狗/停止兜底重复 stopSelf） */
    @Volatile
    private var finished = false

    private var lastNotificationAtMs = 0L

    /** 上一次通知时的状态与暂停原因：状态变化必须立即刷新通知，不能被节流吞掉 */
    private var lastNotifiedStatus: MigrationStatus? = null
    private var lastNotifiedPauseReason: PauseReason? = null

    // ================================================================ 生命周期

    override fun onCreate() {
        super.onCreate()
        engine = (application as MigratorApp).container.engine
        running = true
        finished = false

        NotificationHelper.ensureChannel(this)
        // 必须尽快进入前台（startForegroundService 启动后约 5s 内），否则系统会抛 ANR/崩溃
        startForegroundCompat(NotificationHelper.buildPreparing(this))

        setupWakeLock()
        observeEngineState()

        Log.i(TAG, "前台服务已创建（服务作用域=${serviceScope.coroutineContext}）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action startId=$startId flags=$flags")

        when (action) {
            ACTION_START -> {
                hasSeenActiveState = false
                explicitStop = false
                // 引擎侧命令幂等：已在运行时会忽略重复 Start
                engine.startMigration()
                startPreparingWatchdog()
            }

            ACTION_PAUSE -> engine.pauseMigration()
            ACTION_RESUME -> engine.resumeMigration()

            ACTION_STOP -> {
                explicitStop = true
                engine.stopMigration()
                // 若引擎当前无活动会话，则状态会很快回落 IDLE，由观察者结束服务；
                // 这里再兜底一次，避免服务残留。
                serviceScope.launch {
                    delay(STOP_FALLBACK_DELAY_MS)
                    if (!engine.state.value.status.isActive) finishService()
                }
            }

            // intent == null：系统按 START_STICKY 重启服务。
            // R4 安全：绝不在这里自动开始迁移，只校验引擎是否仍在活动，不活动则自行退出。
            null -> serviceScope.launch {
                delay(RESTART_CHECK_DELAY_MS)
                if (!engine.state.value.status.isActive) finishService()
            }
        }

        // START_STICKY：暂停时保持通知可见；真正结束由观察者调用 stopForeground + stopSelf
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** API 35+ dataSync 前台服务运行时长到上限的系统回调：主动暂停，等待用户再次开始 */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "前台服务达到系统时长上限（onTimeout startId=$startId），自动暂停迁移")
        try {
            // 用 FOREGROUND_LIMIT 作为暂停原因，UI/通知栏可明确告知用户「时长到达系统上限」
            val current = engine
            if (current is MigrationEngine) {
                current.pauseForForegroundLimit()
            } else {
                current.pauseMigration()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onTimeout 暂停迁移失败", e)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "前台服务销毁")
        running = false
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ================================================================ 前台化 / 通知

    /**
     * Android 14+ 要求 startForeground 明确 foregroundServiceType；
     * 使用 ServiceCompat 统一 API 29+ 的类型参数（低版本忽略类型）。
     */
    private fun startForegroundCompat(notification: Notification) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, NotificationHelper.NOTIFICATION_ID, notification, type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Android 12+ 后台启动限制 / Android 14+ 缺权限等场景：不能崩溃，记录后退出服务
            Log.e(TAG, "startForeground 失败（可能受后台启动限制或缺少前台服务权限）", e)
            stopSelf()
        }
    }

    /** 观察引擎状态：终态结束服务，其余状态节流刷新通知 */
    private fun observeEngineState() {
        serviceScope.launch {
            engine.state.collect { state ->
                if (finished) return@collect
                if (state.status != MigrationStatus.IDLE) hasSeenActiveState = true

                val terminal = when (state.status) {
                    MigrationStatus.COMPLETED, MigrationStatus.FAILED -> true
                    // 停止完成或从未启动成功（例如未配置 NAS）：服务没有继续存在的意义
                    MigrationStatus.IDLE -> hasSeenActiveState || explicitStop
                    else -> false
                }
                if (terminal) {
                    Log.i(TAG, "引擎到达终态 status=${state.status}，结束前台服务")
                    finishService()
                    return@collect
                }
                refreshNotificationThrottled(state)
            }
        }
    }

    /** 通知刷新节流（≥500ms），避免高频进度回调导致通知栏频繁重绘 */
    private fun refreshNotificationThrottled(state: com.nasphoto.migrator.business.model.MigrationState) {
        if (finished) return
        val statusChanged = state.status != lastNotifiedStatus ||
            state.pauseReason != lastNotifiedPauseReason
        val nowMs = SystemClock.elapsedRealtime()
        // 运行/暂停/完成等状态切换（含暂停原因变化）必须立即刷新，只有纯进度更新才节流
        if (!statusChanged && nowMs - lastNotificationAtMs < NOTIFICATION_MIN_INTERVAL_MS) return
        lastNotificationAtMs = nowMs
        lastNotifiedStatus = state.status
        lastNotifiedPauseReason = state.pauseReason
        try {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, state))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 通知刷新失败（权限/渠道被禁用）不影响迁移主流程
            Log.w(TAG, "刷新通知失败：${e.message}")
        }
    }

    /** 结束服务：移除通知并 stopSelf（完成/停止后不再保留通知） */
    private fun finishService() {
        if (finished) return
        finished = true
        try {
            // minSdk 29 → 可直接使用平台 API（STOP_FOREGROUND_REMOVE 自 API 24 起可用）
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Log.w(TAG, "stopForeground 失败：${e.message}")
        }
        stopSelf()
    }

    /** 服务启动后若引擎迟迟没有进入活动状态（如未激活 NAS 配置），自动退出，避免残留通知 */
    private fun startPreparingWatchdog() {
        serviceScope.launch {
            delay(PREPARING_WATCHDOG_MS)
            if (!hasSeenActiveState && !engine.state.value.status.isActive) {
                Log.w(TAG, "引擎未进入活动状态（可能缺少 NAS 配置/权限），前台服务退出")
                finishService()
            }
        }
    }

    // ================================================================ 唤醒锁

    /** 按设置 keepAwake 持有 PARTIAL_WAKE_LOCK（带超时，服务销毁时释放） */
    private fun setupWakeLock() {
        serviceScope.launch {
            val keepAwake = try {
                (application as MigratorApp).container.settingsRepository.get().keepAwake
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "读取 keepAwake 设置失败，默认持有唤醒锁：${e.message}")
                true
            }
            if (!keepAwake) {
                Log.i(TAG, "设置关闭「迁移期间保持唤醒」，不持有 PARTIAL_WAKE_LOCK")
                return@launch
            }
            acquireWakeLock()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG,
            ).apply {
                setReferenceCounted(false)
                // 带超时：防止进程异常时永久持有；正常结束时 onDestroy 会主动释放
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            Log.i(TAG, "已获取 PARTIAL_WAKE_LOCK（防止息屏断网）")
        } catch (e: Throwable) {
            Log.w(TAG, "获取唤醒锁失败（不影响迁移）：${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "已释放 PARTIAL_WAKE_LOCK")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "释放唤醒锁失败（忽略）：${e.message}")
        } finally {
            wakeLock = null
        }
    }

    // ================================================================ 静态入口（UI / 通知栏调用）

    companion object {
        private const val TAG = "MigrationService"

        /** 通知栏动作（公开给 NotificationHelper 构建 PendingIntent 使用） */
        const val ACTION_START = "com.nasphoto.migrator.action.MIGRATION_START"
        const val ACTION_PAUSE = "com.nasphoto.migrator.action.MIGRATION_PAUSE"
        const val ACTION_RESUME = "com.nasphoto.migrator.action.MIGRATION_RESUME"
        const val ACTION_STOP = "com.nasphoto.migrator.action.MIGRATION_STOP"

        private const val PREPARING_WATCHDOG_MS = 8_000L
        private const val STOP_FALLBACK_DELAY_MS = 1_500L
        private const val RESTART_CHECK_DELAY_MS = 1_000L
        private const val NOTIFICATION_MIN_INTERVAL_MS = 500L
        private const val WAKE_LOCK_TAG = "NasPhotoMigrator:migration"
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000 // 6 小时（与 API 35 dataSync 上限对齐）

        /** 服务是否在本进程内运行（用于区分「发给服务」还是「直接调引擎」） */
        @Volatile
        private var running = false

        /** 启动迁移：拉起前台服务并开始迁移（UI 首页的「开始」按钮） */
        fun start(context: Context) {
            try {
                val intent = Intent(context, MigrationForegroundService::class.java)
                    .setAction(ACTION_START)
                // 必须用 startForegroundService：服务会在 onCreate 里立即 startForeground
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Throwable) {
                // Android 12+ 后台启动前台服务限制等：绝不让 UI 崩溃
                Log.e(TAG, "启动迁移前台服务失败", e)
                // 降级：直接让引擎开始（无前台服务，但至少迁移能跑）
                engineOf(context)?.startMigration()
            }
        }

        /** 暂停（服务运行中走服务刷新通知；否则直接调引擎，保持状态一致） */
        fun pause(context: Context) {
            dispatch(context, ACTION_PAUSE) { engine -> engine.pauseMigration() }
        }

        /** 继续 */
        fun resume(context: Context) {
            dispatch(context, ACTION_RESUME) { engine -> engine.resumeMigration() }
        }

        /** 停止：结束会话并移除前台通知 */
        fun stop(context: Context) {
            dispatch(context, ACTION_STOP) { engine -> engine.stopMigration() }
        }

        /** 有服务就发给服务（顺带刷新通知），没有服务就直接操作引擎；任何失败都不抛给调用方 */
        private inline fun dispatch(
            context: Context,
            action: String,
            engineAction: (MigrationEngineApi) -> Unit,
        ) {
            try {
                if (running) {
                    context.startService(
                        Intent(context, MigrationForegroundService::class.java).setAction(action),
                    )
                } else {
                    val engine = engineOf(context)
                    if (engine == null) {
                        Log.e(TAG, "无法获取迁移引擎，忽略动作：$action")
                        return
                    }
                    engineAction(engine)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "发送服务动作失败（降级为直接调用引擎）：$action", e)
                try {
                    engineOf(context)?.let(engineAction)
                } catch (inner: Throwable) {
                    Log.e(TAG, "直接调用引擎也失败：$action", inner)
                }
            }
        }

        private fun engineOf(context: Context): MigrationEngineApi? = try {
            (context.applicationContext as? MigratorApp)?.container?.engine
        } catch (e: Throwable) {
            Log.e(TAG, "获取迁移引擎失败", e)
            null
        }
    }
}
