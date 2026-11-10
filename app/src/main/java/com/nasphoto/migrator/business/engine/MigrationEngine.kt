package com.nasphoto.migrator.business.engine

import android.app.Application
import android.os.SystemClock
import com.nasphoto.migrator.business.guard.NetworkMonitor
import com.nasphoto.migrator.business.guard.PermissionGuard
import com.nasphoto.migrator.business.guard.PowerMonitor
import com.nasphoto.migrator.business.log.Logger
import com.nasphoto.migrator.business.model.FileProcessResult
import com.nasphoto.migrator.business.model.MigrationCommand
import com.nasphoto.migrator.business.model.MigrationPreview
import com.nasphoto.migrator.business.model.MigrationState
import com.nasphoto.migrator.business.model.MigrationStatus
import com.nasphoto.migrator.business.model.PauseReason
import com.nasphoto.migrator.business.verify.IntegrityVerifier
import com.nasphoto.migrator.data.config.AppSettings
import com.nasphoto.migrator.data.config.NasConfig
import com.nasphoto.migrator.data.config.NasConfigRepository
import com.nasphoto.migrator.data.config.SettingsRepository
import com.nasphoto.migrator.data.gallery.GalleryRepository
import com.nasphoto.migrator.data.gallery.MigrationSource
import com.nasphoto.migrator.data.record.MigrationStore
import com.nasphoto.migrator.protocol.NasClient
import com.nasphoto.migrator.protocol.NasClientFactory
import com.nasphoto.migrator.protocol.NasErrorType
import com.nasphoto.migrator.protocol.UploadSource
import com.nasphoto.migrator.protocol.toNasErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 迁移引擎（状态机 + 单线程串行闭环）。
 *
 * ======================= 铁则落点一览（任何优化都不得改动） =======================
 *  R1 单线程串行：
 *      - 命令由 [MigrationScheduler] 的唯一消费者协程串行处理；
 *      - 文件处理只发生在 [runSession] → [runLoop] → [FileProcessPipeline.process] 这一条调用链上，
 *        该链运行在 `Dispatchers.IO.limitedParallelism(1)`（物理上 1 个线程），
 *        并由 [MigrationScheduler.launchSession] 的 AtomicBoolean 保证最多 1 个会话协程；
 *      - 因此「并发批量上传」在本实现中无代码路径可走。
 *  R2 校验通过才删除：
 *      - 删除本地只存在于 [FileProcessPipeline.completeAfterVerification]，
 *        而该方法只能由「校验通过」分支调用（upload → verify → delete 顺序由管线强制）。
 *  R3 失败绝不删除本地：
 *      - 管线所有失败分支都返回 Failed/DeleteFailed 且不调用 deleteLocal；
 *      - 本引擎只根据结果类型更新计数，不直接删除任何东西。
 *  R4 断点续传：
 *      - 队列 = queryPending(settings, migratedFileKeys()) 差集；
 *      - MigratedRecord 只在「校验通过 + 本地删除确认成功」后写入；
 *      - 重启/进程被杀后从差集重新排队，已完成文件不会重复上传。
 *  R5 删完才进下一张：
 *      - 会话循环中「处理结果落账 → ctx.index++ → 下一轮」是同一协程内的顺序语句；
 *      - 被暂停/停止中断时 **不推进 index**，当前文件保持未迁移状态，恢复后重新处理。
 * ============================================================================
 *
 * 线程安全：
 *  - 公开方法（start/pause/resume/stop/loadPreview/shutdown）可被 UI 主线程与前台服务同时调用：
 *    命令统一走 Channel（串行），暂停/停止标志直接置位原子变量（保证命令尚未被消费时也能尽快中断上传）；
 *  - 所有状态写入集中在本类的 [updateState]，通过 MutableStateFlow.update 原子更新。
 */
class MigrationEngine(
    private val appContext: Application,
    private val galleryRepository: GalleryRepository,
    private val nasConfigRepository: NasConfigRepository,
    private val settingsRepository: SettingsRepository,
    private val migrationStore: MigrationStore,
    private val logger: Logger,
    /** NAS 客户端工厂（默认走协议层 NasClientFactory；测试可注入替身） */
    private val nasClientProvider: (NasConfig) -> NasClient = { config -> NasClientFactory.create(config) },
) : MigrationEngineApi {

    // ------------------------------------------------------------------ 状态

    private val _state = MutableStateFlow(MigrationState())
    override val state: StateFlow<MigrationState> = _state.asStateFlow()

    // ------------------------------------------------------------------ 依赖

    private val pipeline = FileProcessPipeline(
        galleryRepository = galleryRepository,
        migrationStore = migrationStore,
        logger = logger,
        verifier = IntegrityVerifier(logger),
        retryPolicy = RetryPolicy(),
    )

    private val networkMonitor = NetworkMonitor(appContext)
    private val powerMonitor = PowerMonitor(appContext)
    private val permissionGuard = PermissionGuard(appContext)

    /** 引擎级作用域：只承载「自动恢复监视」这种轻量任务，绝不承载文件处理 */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------ 调度

    private val scheduler = MigrationScheduler(logger) { command -> handleCommand(command) }

    // ------------------- 跨线程请求标志（公开方法可直接置位，保证尽快中断上传） -------------------

    private val pauseRequested = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    /**
     * 暂停原因覆盖（默认用户手动暂停）。
     * API 35+ dataSync 前台服务到达系统时长上限时，服务会调用 [pauseForForegroundLimit]，
     * 把暂停原因标记为 [PauseReason.FOREGROUND_LIMIT]，让 UI/通知栏能给出正确解释。
     */
    private val pauseReasonOverride = AtomicReference<PauseReason?>(null)

    // ------------------- 会话上下文（仅会话协程写，命令协程/自动恢复只读引用） -------------------

    @Volatile
    private var sessionContext: SessionContext? = null

    @Volatile
    private var autoResumeJob: Job? = null

    // ================================================================ 公开 API

    override fun startMigration() {
        logger.i(TAG_ENGINE, "请求开始迁移")
        scheduler.ensureConsumer()
        scheduler.submit(MigrationCommand.Start)
    }

    override fun pauseMigration() {
        // 先置标志再投递命令：即使命令消费者正被阻塞的上传占住线程，会话也能在进度回调里尽快看到暂停
        pauseReasonOverride.set(null)
        pauseRequested.set(true)
        logger.i(TAG_ENGINE, "请求暂停迁移")
        scheduler.ensureConsumer()
        scheduler.submit(MigrationCommand.Pause)
    }

    /**
     * 前台服务时长到达系统上限（API 35+ dataSync 6 小时限制）时的暂停入口。
     *
     * 不在 [MigrationEngineApi] 契约里（只由 [com.nasphoto.migrator.business.service.MigrationForegroundService]
     * 的 onTimeout 调用），因此不会影响 UI 层依赖面。
     */
    fun pauseForForegroundLimit() {
        pauseReasonOverride.set(PauseReason.FOREGROUND_LIMIT)
        pauseRequested.set(true)
        logger.w(TAG_ENGINE, "前台服务达到系统时长上限，暂停迁移（${PauseReason.FOREGROUND_LIMIT.description}）")
        scheduler.ensureConsumer()
        scheduler.submit(MigrationCommand.Pause)
    }

    override fun resumeMigration() {
        pauseRequested.set(false)
        stopRequested.set(false)
        pauseReasonOverride.set(null)
        logger.i(TAG_ENGINE, "请求继续迁移")
        scheduler.ensureConsumer()
        scheduler.submit(MigrationCommand.Resume)
    }

    override fun stopMigration() {
        stopRequested.set(true)
        logger.i(TAG_ENGINE, "请求停止迁移")
        scheduler.ensureConsumer()
        scheduler.submit(MigrationCommand.Stop)
    }

    override suspend fun loadPreview(): MigrationPreview = withContext(Dispatchers.IO) {
        try {
            val settings = settingsRepository.get()
            val migratedKeys = migrationStore.migratedFileKeys()
            val stats = galleryRepository.pendingStats(settings, migratedKeys)
            logger.d(
                TAG_ENGINE,
                "预览：待迁移 ${stats.itemCount} 个（图片 ${stats.imageCount} / 视频 ${stats.videoCount}）" +
                    " 共 ${stats.totalBytes} B，已完成记录 ${migratedKeys.size} 个",
            )
            MigrationPreview(
                totalCount = stats.itemCount,
                totalBytes = stats.totalBytes,
                imageCount = stats.imageCount,
                videoCount = stats.videoCount,
                estimatedFreeBytes = stats.totalBytes,
                alreadyMigratedCount = migratedKeys.size,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e(TAG_ENGINE, "计算迁移预览失败：${e.message}", e)
            MigrationPreview()
        }
    }

    override suspend fun shutdown() {
        logger.i(TAG_ENGINE, "引擎 shutdown：停止会话、断开 NAS、释放资源（不做任何删除）")
        stopRequested.set(true)
        pauseRequested.set(true)
        cancelAutoResume()
        scheduler.submit(MigrationCommand.Stop)
        // 等待会话在安全点退出（超时后仍继续释放：会话 finally 会用 NonCancellable 释放客户端）
        withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { scheduler.awaitSession() }
        scheduler.shutdown()
        sessionContext = null
        updateState {
            it.copy(
                status = MigrationStatus.IDLE,
                pauseReason = PauseReason.NONE,
                currentFileName = "",
                currentStage = "",
                currentFileBytes = 0,
                currentFileTotalBytes = 0,
                currentFileRetry = 0,
                lastMessage = "引擎已释放",
            )
        }
    }

    // ================================================================ 命令处理（唯一消费者协程）

    private suspend fun handleCommand(command: MigrationCommand) {
        when (command) {
            MigrationCommand.Start -> handleStart()
            MigrationCommand.Pause -> handlePause()
            MigrationCommand.Resume -> handleResume()
            MigrationCommand.Stop -> handleStop()
        }
    }

    /** 开始：仅在无活动会话时启动唯一会话协程；重复调用幂等 */
    private suspend fun handleStart() {
        val current = _state.value
        // 暂停中按「开始」等价于继续（UI/通知栏可能只有一个按钮，语义上「开始」= 让任务跑起来）
        if (!scheduler.isSessionActive &&
            current.status == MigrationStatus.PAUSED &&
            sessionContext != null
        ) {
            logger.i(TAG_ENGINE, "当前处于暂停状态，开始请求转为继续")
            handleResume()
            return
        }
        if (scheduler.isSessionActive) {
            logger.w(TAG_ENGINE, "重复的开始请求已忽略（会话正在运行，status=${current.status}）")
            return
        }
        if (current.status == MigrationStatus.STOPPING) {
            logger.w(TAG_ENGINE, "忽略开始请求：上一次停止尚未完成")
            return
        }
        pauseRequested.set(false)
        pauseReasonOverride.set(null)
        stopRequested.set(false)
        cancelAutoResume()
        if (!scheduler.launchSession { runSession(resumeExisting = false) }) {
            logger.w(TAG_ENGINE, "启动迁移会话失败（已存在活动会话）")
        }
    }

    /** 暂停：置位原子标志后由会话在安全点退出；幂等 */
    private suspend fun handlePause() {
        val current = _state.value
        if (!scheduler.isSessionActive) {
            // 未运行时的暂停请求：清掉标志，避免误伤下一次开始
            pauseRequested.set(false)
            pauseReasonOverride.set(null)
            logger.d(TAG_ENGINE, "忽略暂停请求（当前状态 ${current.status}，无活动会话）")
            return
        }
        pauseRequested.set(true)
        updateState { it.copy(lastMessage = "已收到暂停请求，当前文件到达安全点后暂停…") }
        logger.i(TAG_ENGINE, "收到暂停请求：当前文件处理到安全点后暂停（不删除未完成校验的文件）")
    }

    /** 继续：从断点（当前未完成文件）继续，不重复上传已完成文件 */
    private suspend fun handleResume() {
        val current = _state.value
        if (scheduler.isSessionActive) {
            logger.d(TAG_ENGINE, "忽略继续请求：会话正在运行（status=${current.status}）")
            return
        }
        if (current.status == MigrationStatus.RUNNING) {
            logger.d(TAG_ENGINE, "忽略继续请求：已在运行")
            return
        }
        pauseRequested.set(false)
        pauseReasonOverride.set(null)
        stopRequested.set(false)
        cancelAutoResume()
        val resumeExisting = sessionContext != null
        logger.i(
            TAG_ENGINE,
            if (resumeExisting) {
                "继续迁移：从断点第 ${(sessionContext?.index ?: 0) + 1} 个文件恢复"
            } else {
                "继续迁移：重新建立待迁移队列"
            },
        )
        if (!scheduler.launchSession { runSession(resumeExisting = resumeExisting) }) {
            logger.w(TAG_ENGINE, "启动迁移会话失败（已存在活动会话）")
        }
    }

    /** 停止：安全点退出，绝不半途删除；幂等 */
    private suspend fun handleStop() {
        stopRequested.set(true)
        pauseRequested.set(true)
        cancelAutoResume()
        if (scheduler.isSessionActive) {
            updateState {
                it.copy(
                    status = MigrationStatus.STOPPING,
                    lastMessage = "正在停止：等待当前文件到达安全点（不会删除未校验完成文件）",
                )
            }
            logger.i(TAG_ENGINE, "收到停止请求：等待当前单文件操作安全退出（绝不半途删除本地）")
            return
        }
        // 无活动会话（空闲/已暂停）→ 立即结束本次会话
        sessionContext = null
        updateState {
            it.copy(
                status = MigrationStatus.IDLE,
                pauseReason = PauseReason.NONE,
                currentFileName = "",
                currentFileBytes = 0,
                currentFileTotalBytes = 0,
                currentFileRetry = 0,
                currentStage = "",
                finishedAt = now(),
                lastMessage = "迁移已停止",
            )
        }
        logger.i(TAG_ENGINE, "迁移已停止（当前无活动会话）")
    }

    // ================================================================ 会话执行（唯一文件处理协程）

    private suspend fun runSession(resumeExisting: Boolean) {
        var contextToRelease: SessionContext? = null
        var activeClient: NasClient? = null
        try {
            // 提前进入 RUNNING：准备阶段（权限检查、NAS connect 可能耗时数十秒）也要让 UI/通知栏
            // 看到「正在准备」，否则前台服务的启动看门狗会把慢连接误判成「没启动起来」而结束服务。
            // 若前置检查失败，下面会立刻用 PAUSED/IDLE 覆盖该状态。
            updateState {
                it.copy(
                    status = MigrationStatus.RUNNING,
                    pauseReason = PauseReason.NONE,
                    currentStage = "",
                    startedAt = if (it.startedAt == 0L) now() else it.startedAt,
                    finishedAt = 0L,
                    lastMessage = "正在准备迁移：检查权限/网络并连接 NAS…",
                )
            }

            // 准备阶段前先响应「已存在的暂停/停止请求」
            val prepared = when {
                stopRequested.get() -> null
                pauseRequested.get() -> null
                resumeExisting -> prepareResumeSession()
                else -> prepareNewSession()
            }

            if (prepared == null) {
                // prepare 内部已经写入 PAUSED（前置条件不满足）或 IDLE/COMPLETED（无配置/无文件）；
                // 这里只处理「用户在准备阶段点了停止」的优先级问题：停止必须覆盖暂停。
                if (stopRequested.get()) {
                    finishStopped()
                } else if (pauseRequested.get() && _state.value.status != MigrationStatus.PAUSED) {
                    applyPaused(currentPauseReason(), "用户手动暂停")
                }
                return
            }

            contextToRelease = prepared
            activeClient = prepared.client
            sessionContext = prepared

            // ---- 串行闭环主循环 ----
            when (val outcome = runLoop(prepared)) {
                is LoopOutcome.Completed -> onCompleted(prepared)
                is LoopOutcome.Stopped -> onStopped(prepared)
                is LoopOutcome.Paused -> onPaused(prepared, outcome.reason)
            }
        } catch (e: CancellationException) {
            // 取消必须原样抛出（shutdown/进程退出），不能被吞
            logger.i(TAG_ENGINE, "迁移会话被取消（进程退出或引擎 shutdown）")
            throw e
        } catch (e: Throwable) {
            logger.e(TAG_ENGINE, "迁移会话异常终止：${e.message}", e)
            sessionContext = null
            updateState {
                it.copy(
                    status = MigrationStatus.FAILED,
                    pauseReason = PauseReason.NONE,
                    currentFileName = "",
                    currentFileBytes = 0,
                    currentFileTotalBytes = 0,
                    currentFileRetry = 0,
                    currentStage = "",
                    finishedAt = now(),
                    lastMessage = "迁移异常终止：${e.message ?: e.javaClass.simpleName}",
                )
            }
        } finally {
            // 无论是正常完成、暂停、停止、异常还是取消，都必须断开并关闭 NAS 客户端（不允许连接泄漏）。
            // NonCancellable：协程被取消时仍要完成 disconnect，避免协议层线程/套接字残留。
            withContext(NonCancellable) {
                activeClient?.let { client -> releaseClient(client) }
                contextToRelease?.client = null
            }
        }
    }

    /**
     * 串行闭环主循环。
     *
     * 每一轮只处理队首 1 个文件，处理完（成功删完 / 明确失败 / 跳过）才 `index++`。
     * 暂停/停止/守卫不通过 → 立即返回，**不推进 index**（当前文件下次重新处理）。
     */
    private suspend fun runLoop(ctx: SessionContext): LoopOutcome {
        val client = ctx.client ?: return LoopOutcome.Paused(PauseReason.NAS_UNREACHABLE)

        while (true) {
            // ---------- 安全点 1：停止/暂停优先（R1：入口唯一，所有中断都在这里收敛） ----------
            if (stopRequested.get()) return LoopOutcome.Stopped
            if (pauseRequested.get()) return LoopOutcome.Paused(currentPauseReason())

            // ---------- 队列耗尽 → 完成 ----------
            if (ctx.index >= ctx.queue.size) return LoopOutcome.Completed

            // ---------- 安全点 2：系统守卫（权限/网络/电量/NAS 空间） ----------
            val guardReason = evaluateGuards(ctx)
            if (guardReason != null) {
                logger.w(TAG_ENGINE, "系统守卫未通过（${guardReason.description}），暂停整个任务")
                return LoopOutcome.Paused(guardReason)
            }

            val source = ctx.queue[ctx.index]

            // ---------- 断点去重兜底 ----------
            // 队列构建时已做过差集；这里再查一次，防止「队列构建后、处理前」这段时间里
            // 别的会话/异常管理页重试已经把该文件写成完成记录。
            val alreadyMigrated = try {
                migrationStore.isMigrated(source.fileKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.w(TAG_ENGINE, "查询断点记录失败（按未迁移处理）：${e.message}")
                false
            }
            if (alreadyMigrated) {
                logger.i(TAG_ENGINE, "断点去重命中，跳过已迁移文件：${source.displayName}")
                applyResult(
                    ctx,
                    FileProcessResult.Skipped(
                        fileKey = source.fileKey,
                        displayName = source.displayName,
                        sizeBytes = source.size,
                        reason = "完成记录中已存在该文件（断点续传去重）",
                    ),
                )
                ctx.index++
                continue
            }

            // ---------- 单文件闭环（唯一文件处理调用点） ----------
            updateState {
                it.copy(
                    currentFileName = source.displayName,
                    currentFileBytes = 0,
                    currentFileTotalBytes = source.size,
                    currentFileRetry = 0,
                    currentStage = MigrationStage.CHECK,
                    lastMessage = "正在处理：${source.displayName}",
                )
            }
            logger.i(
                TAG_ENGINE,
                "开始第 ${ctx.index + 1}/${ctx.queue.size} 个文件：${source.displayName} " +
                    "(${source.size} B) fileKey=${source.fileKey}",
            )

            val result = try {
                pipeline.process(
                    source = source,
                    config = ctx.config,
                    client = client,
                    uploadSource = createUploadSource(source),
                    settings = ctx.settings,
                    control = SessionProcessControl(ctx),
                )
            } catch (e: ProcessInterruptedException) {
                // ---------- 步骤 9：暂停/停止检测 → 安全点退出，不写完成记录、不删本地 ----------
                logger.i(
                    TAG_ENGINE,
                    "当前文件被中断（${e.message}）：${source.displayName}；" +
                        "未写完成记录、未删除本地，队列位置保持在第 ${ctx.index + 1} 个",
                )
                return when (val signal = e.signal) {
                    is InterruptSignal.Stop -> LoopOutcome.Stopped
                    is InterruptSignal.Pause -> LoopOutcome.Paused(signal.reason)
                }
            }

            // ---------- 结果落账（更新进度 / 累计释放空间） ----------
            applyResult(ctx, result)

            // ---------- R5：闭环确认结束，才推进到下一张 ----------
            ctx.index++
            updateState {
                it.copy(
                    currentFileName = "",
                    currentFileBytes = 0,
                    currentFileTotalBytes = 0,
                    currentFileRetry = 0,
                    currentStage = "",
                )
            }

            // ---------- 全局性错误（认证/空间/权限）→ 暂停整个任务 ----------
            if (result is FileProcessResult.Failed && result.shouldPauseTask) {
                val reason = result.errorType.toPauseReason()
                logger.w(TAG_ENGINE, "全局性错误（${result.errorType.name}），暂停整个任务：${reason.description}")
                return LoopOutcome.Paused(reason)
            }
        }
    }

    // ================================================================ 会话准备 / 前置检查

    /** 新建会话：前置检查 → 建立待迁移队列 → 状态置 RUNNING */
    private suspend fun prepareNewSession(): SessionContext? {
        val config = nasConfigRepository.getActive()
        if (config == null) {
            logger.e(TAG_ENGINE, "启动失败：没有激活的 NAS 配置")
            updateState {
                it.copy(
                    status = MigrationStatus.IDLE,
                    pauseReason = PauseReason.NONE,
                    currentFileName = "",
                    currentStage = "",
                    finishedAt = now(),
                    lastMessage = "未配置 NAS：请先在 NAS 配置页添加并激活一套配置",
                )
            }
            return null
        }
        val settings = settingsRepository.get()
        return when (val outcome = precheck(config, settings)) {
            is PrecheckOutcome.Blocked -> {
                applyPaused(outcome.reason, outcome.message)
                null
            }

            is PrecheckOutcome.Ready -> buildSession(config, settings, outcome.client)
        }
    }

    /** 恢复会话：复用断点队列，只重新连接 NAS 并复查前置条件 */
    private suspend fun prepareResumeSession(): SessionContext? {
        val existing = sessionContext
        if (existing == null) {
            logger.i(TAG_ENGINE, "没有可恢复的断点会话，重新建立待迁移队列")
            return prepareNewSession()
        }
        val config = nasConfigRepository.getActive()
        if (config == null) {
            logger.e(TAG_ENGINE, "恢复失败：激活的 NAS 配置已被删除")
            sessionContext = null
            updateState {
                it.copy(
                    status = MigrationStatus.IDLE,
                    pauseReason = PauseReason.NONE,
                    finishedAt = now(),
                    lastMessage = "NAS 配置已被删除，请重新配置后再开始",
                )
            }
            return null
        }
        if (config.id != existing.config.id) {
            logger.i(TAG_ENGINE, "NAS 配置已变更（${existing.config.name} → ${config.name}），重新建立队列")
            sessionContext = null
            return prepareNewSession()
        }
        // 设置可能在暂停期间被修改（例如关闭「仅 WiFi」），恢复时重新读取
        val settings = settingsRepository.get()
        existing.config = config
        existing.settings = settings
        return when (val outcome = precheck(config, settings)) {
            is PrecheckOutcome.Blocked -> {
                applyPaused(outcome.reason, outcome.message)
                null
            }

            is PrecheckOutcome.Ready -> {
                existing.client = outcome.client
                updateState {
                    it.copy(
                        status = MigrationStatus.RUNNING,
                        pauseReason = PauseReason.NONE,
                        lastMessage = "继续迁移：从断点第 ${existing.index + 1} 个文件恢复",
                    )
                }
                logger.i(
                    TAG_ENGINE,
                    "断点恢复：已有完成记录保证不重复上传，从第 ${existing.index + 1}/${existing.queue.size} 个继续",
                )
                existing
            }
        }
    }

    /** 前置检查结果 */
    private sealed interface PrecheckOutcome {
        data class Blocked(val reason: PauseReason, val message: String) : PrecheckOutcome
        data class Ready(val client: NasClient) : PrecheckOutcome
    }

    /**
     * 启动前置检查（顺序：权限 → 网络规则 → 电量 → NAS 可连接）。
     * 失败返回 [PrecheckOutcome.Blocked]（由调用方置 PAUSED），成功返回已连接的客户端。
     */
    private suspend fun precheck(config: NasConfig, settings: AppSettings): PrecheckOutcome {
        // 1) 权限（相册读取 + 删除原图）
        if (!permissionGuard.hasMediaReadPermission() || !permissionGuard.hasDeletePermission()) {
            return PrecheckOutcome.Blocked(
                PauseReason.STORAGE_PERMISSION_LOST,
                "相册读取或删除权限缺失（Android 11+ 删除原图需「所有文件访问」权限）",
            )
        }

        // 2) 网络规则（仅 WiFi / 断网）
        val net = networkMonitor.snapshot()
        updateState { it.copy(isNetworkAvailable = net.isAvailable, isWifi = net.isWifi) }
        if (!net.isAvailable) {
            return PrecheckOutcome.Blocked(PauseReason.NETWORK_LOST, "网络已断开，等待网络恢复")
        }
        if (settings.wifiOnly && !net.isWifi) {
            return PrecheckOutcome.Blocked(PauseReason.WIFI_REQUIRED, "已开启「仅 WiFi 迁移」，当前为移动网络")
        }

        // 3) 低电量规则
        val power = powerMonitor.snapshot()
        updateState { it.copy(batteryPercent = power.percent, isCharging = power.isCharging) }
        if (settings.lowBatteryPause && !power.isCharging &&
            power.percent in 1..settings.lowBatteryThreshold
        ) {
            return PrecheckOutcome.Blocked(
                PauseReason.LOW_BATTERY,
                "电量 ${power.percent}% 低于阈值 ${settings.lowBatteryThreshold}%，已自动暂停（充电后自动继续）",
            )
        }

        // 4) NAS 可连接（connect 是阻塞操作，运行在 IO 单线程调度器上）
        if (stopRequested.get() || pauseRequested.get()) {
            return PrecheckOutcome.Blocked(currentPauseReason(), "准备连接 NAS 前收到暂停/停止请求")
        }
        val client = try {
            nasClientProvider(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e(TAG_ENGINE, "创建 NAS 客户端失败：${e.message}", e)
            return PrecheckOutcome.Blocked(PauseReason.NAS_UNREACHABLE, "创建 NAS 客户端失败：${e.message}")
        }
        try {
            logger.i(TAG_ENGINE, "正在连接 NAS：${config.summary()}")
            client.connect()
            logger.i(TAG_ENGINE, "NAS 连接成功：${config.summary()}")
        } catch (e: CancellationException) {
            withContext(NonCancellable) { releaseClient(client) }
            throw e
        } catch (e: Throwable) {
            val errorType = e.toNasErrorType()
            withContext(NonCancellable) { releaseClient(client) }
            val reason = if (errorType == NasErrorType.AUTH_FAILED) {
                PauseReason.NAS_AUTH_FAILED
            } else {
                PauseReason.NAS_UNREACHABLE
            }
            return PrecheckOutcome.Blocked(reason, "NAS 连接失败（${errorType.name}）：${e.message}")
        }

        // 连接可能耗时数十秒：期间用户可能已经点了暂停/停止
        if (stopRequested.get() || pauseRequested.get()) {
            withContext(NonCancellable) { releaseClient(client) }
            return PrecheckOutcome.Blocked(currentPauseReason(), "连接 NAS 期间收到暂停/停止请求")
        }
        return PrecheckOutcome.Ready(client)
    }

    /** 建立会话上下文：查完成记录差集 → 查待迁移队列 → 初始化状态 */
    private suspend fun buildSession(
        config: NasConfig,
        settings: AppSettings,
        client: NasClient,
    ): SessionContext? {
        // R4：已完成 fileKey 的差集就是断点续传的依据
        val migratedKeys = try {
            migrationStore.migratedFileKeys()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e(TAG_ENGINE, "读取已完成记录失败：${e.message}", e)
            withContext(NonCancellable) { releaseClient(client) }
            updateState {
                it.copy(
                    status = MigrationStatus.FAILED,
                    pauseReason = PauseReason.NONE,
                    finishedAt = now(),
                    lastMessage = "读取迁移记录失败：${e.message}",
                )
            }
            return null
        }

        val queue = try {
            galleryRepository.queryPending(settings, migratedKeys)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e(TAG_ENGINE, "查询待迁移文件失败：${e.message}", e)
            withContext(NonCancellable) { releaseClient(client) }
            applyPaused(
                PauseReason.STORAGE_PERMISSION_LOST,
                "读取相册失败：${e.message}（请检查相册权限）",
            )
            return null
        }

        if (queue.isEmpty()) {
            withContext(NonCancellable) { releaseClient(client) }
            updateState {
                it.copy(
                    status = MigrationStatus.COMPLETED,
                    pauseReason = PauseReason.NONE,
                    totalCount = 0,
                    processedCount = 0,
                    successCount = 0,
                    failedCount = 0,
                    skippedCount = 0,
                    totalBytes = 0,
                    freedBytes = 0,
                    currentFileName = "",
                    currentStage = "",
                    startedAt = now(),
                    finishedAt = now(),
                    activeConfigName = config.name,
                    lastMessage = "没有需要迁移的文件",
                )
            }
            logger.i(TAG_ENGINE, "会话结束：没有待迁移文件（已完成记录 ${migratedKeys.size} 个）")
            return null
        }

        val totalBytes = queue.sumOf { it.size }
        updateState {
            it.copy(
                status = MigrationStatus.RUNNING,
                pauseReason = PauseReason.NONE,
                totalCount = queue.size,
                processedCount = 0,
                successCount = 0,
                failedCount = 0,
                skippedCount = 0,
                sessionFailedCount = 0,
                totalBytes = totalBytes,
                freedBytes = 0,
                currentFileName = "",
                currentFileBytes = 0,
                currentFileTotalBytes = 0,
                currentFileRetry = 0,
                currentStage = "",
                startedAt = now(),
                finishedAt = 0L,
                activeConfigName = config.name,
                lastMessage = "开始迁移：共 ${queue.size} 个文件",
            )
        }
        logger.i(
            TAG_ENGINE,
            "会话开始：配置=${config.name}(${config.protocol.name}) | 队列=${queue.size} 个 / " +
                "${formatBytes(totalBytes)} | 校验方式=${if (settings.verifyMd5) "SIZE+MD5" else "SIZE"} | " +
                "仅WiFi=${settings.wifiOnly} | 排序=${settings.sortOrder.name}",
        )
        return SessionContext(
            config = config,
            settings = settings,
            queue = queue.toMutableList(),
            index = 0,
            client = client,
        )
    }

    // ================================================================ 守卫 / 进度 / 结果落账

    /**
     * 每个文件处理前的系统守卫。
     * @return 需要暂停时返回原因；null 表示可以继续
     */
    private suspend fun evaluateGuards(ctx: SessionContext): PauseReason? {
        // 权限：中途被用户在系统设置里撤销 → 暂停（不能继续删除）
        if (!permissionGuard.hasMediaReadPermission() || !permissionGuard.hasDeletePermission()) {
            return PauseReason.STORAGE_PERMISSION_LOST
        }

        // 网络：断网 / 仅 WiFi 规则被破坏 → 暂停并保留队列位置（恢复后从当前文件重来）
        val net = networkMonitor.snapshot()
        updateState { it.copy(isNetworkAvailable = net.isAvailable, isWifi = net.isWifi) }
        if (!net.isAvailable) return PauseReason.NETWORK_LOST
        if (ctx.settings.wifiOnly && !net.isWifi) return PauseReason.WIFI_REQUIRED

        // 电量：低电量且未充电 → 暂停（充电后自动恢复）
        val power = powerMonitor.snapshot()
        updateState { it.copy(batteryPercent = power.percent, isCharging = power.isCharging) }
        if (ctx.settings.lowBatteryPause && !power.isCharging &&
            power.percent in 1..ctx.settings.lowBatteryThreshold
        ) {
            return PauseReason.LOW_BATTERY
        }

        // NAS 剩余空间：freeSpace != null 且 < 当前文件大小 + 50MB 安全余量 → 暂停
        val freeSpace = try {
            ctx.client?.freeSpaceBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // WebDAV 等无法获取空间属于正常情况：忽略，不能因此阻断迁移
            logger.w(TAG_ENGINE, "查询 NAS 剩余空间失败（忽略）：${e.message}")
            null
        }
        if (freeSpace != null) {
            updateState { it.copy(nasFreeSpaceBytes = freeSpace) }
            val currentSize = ctx.queue.getOrNull(ctx.index)?.size ?: 0L
            val required = currentSize + NAS_SPACE_SAFETY_BYTES
            if (freeSpace < required) {
                logger.w(
                    TAG_ENGINE,
                    "NAS 剩余空间不足：可用 ${formatBytes(freeSpace)}，本次需要 ${formatBytes(required)}",
                )
                return PauseReason.NAS_NO_SPACE
            }
        }
        return null
    }

    /** 结果落账：更新进度计数（读取侧 UI/通知栏只依赖 StateFlow） */
    private fun applyResult(ctx: SessionContext, result: FileProcessResult) {
        val index = ctx.index + 1
        when (result) {
            is FileProcessResult.Success -> {
                updateState {
                    it.copy(
                        processedCount = it.processedCount + 1,
                        successCount = it.successCount + 1,
                        freedBytes = it.freedBytes + result.sizeBytes,
                        lastMessage = "已完成：${result.displayName}（释放 ${formatBytes(result.sizeBytes)}）",
                    )
                }
                logger.i(
                    TAG_ENGINE,
                    "进度 $index/${ctx.queue.size}：成功 ${result.displayName}（校验=${result.verification.name}）",
                )
            }

            is FileProcessResult.AlreadyExisted -> {
                // 「NAS 已存在且校验一致」同样删除了本地并释放空间，计入成功而非跳过，
                // 保证 processedCount = success + failed + skipped 恒等
                updateState {
                    it.copy(
                        processedCount = it.processedCount + 1,
                        successCount = it.successCount + 1,
                        freedBytes = it.freedBytes + result.sizeBytes,
                        lastMessage = "NAS 已存在且校验通过，已删除本地：${result.displayName}",
                    )
                }
                logger.i(
                    TAG_ENGINE,
                    "进度 $index/${ctx.queue.size}：已存在并校验通过 ${result.displayName}（校验=${result.verification.name}）",
                )
            }

            is FileProcessResult.Failed -> {
                updateState {
                    it.copy(
                        processedCount = it.processedCount + 1,
                        failedCount = it.failedCount + 1,
                        sessionFailedCount = it.sessionFailedCount + 1,
                        lastMessage = "失败（本地已保留）：${result.displayName}【${result.errorType.description}】",
                    )
                }
                logger.w(
                    TAG_ENGINE,
                    "进度 $index/${ctx.queue.size}：失败 ${result.displayName} " +
                        "errorType=${result.errorType.name} reason=${result.reason}（本地文件已保留）",
                )
            }

            is FileProcessResult.DeleteFailed -> {
                updateState {
                    it.copy(
                        processedCount = it.processedCount + 1,
                        failedCount = it.failedCount + 1,
                        sessionFailedCount = it.sessionFailedCount + 1,
                        lastMessage = "上传成功但本地删除失败（已记异常）：${result.displayName}",
                    )
                }
                logger.w(
                    TAG_ENGINE,
                    "进度 $index/${ctx.queue.size}：删除失败 ${result.displayName} reason=${result.reason}（未写完成记录）",
                )
            }

            is FileProcessResult.Skipped -> {
                updateState {
                    it.copy(
                        processedCount = it.processedCount + 1,
                        skippedCount = it.skippedCount + 1,
                        lastMessage = "跳过：${result.displayName}",
                    )
                }
                logger.i(TAG_ENGINE, "进度 $index/${ctx.queue.size}：跳过 ${result.displayName}（${result.reason}）")
            }
        }
    }

    // ================================================================ 会话结束状态

    private fun onCompleted(ctx: SessionContext) {
        sessionContext = null
        cancelAutoResume()
        val snapshot = _state.value
        updateState {
            it.copy(
                status = MigrationStatus.COMPLETED,
                pauseReason = PauseReason.NONE,
                currentFileName = "",
                currentFileBytes = 0,
                currentFileTotalBytes = 0,
                currentFileRetry = 0,
                currentStage = "",
                finishedAt = now(),
                lastMessage = "迁移完成：成功 ${snapshot.successCount} · 失败 ${snapshot.failedCount} · " +
                    "跳过 ${snapshot.skippedCount}",
            )
        }
        val elapsedSec = if (snapshot.startedAt > 0L) (now() - snapshot.startedAt) / 1000 else 0
        logger.i(
            TAG_ENGINE,
            "会话完成：文件=${ctx.queue.size} 成功=${snapshot.successCount} " +
                "失败=${snapshot.failedCount} 跳过=${snapshot.skippedCount} " +
                "释放空间=${formatBytes(snapshot.freedBytes)} 用时=${elapsedSec}s",
        )
    }

    private fun onStopped(ctx: SessionContext) {
        sessionContext = null
        cancelAutoResume()
        val snapshot = _state.value
        updateState {
            it.copy(
                status = MigrationStatus.IDLE,
                pauseReason = PauseReason.NONE,
                currentFileName = "",
                currentFileBytes = 0,
                currentFileTotalBytes = 0,
                currentFileRetry = 0,
                currentStage = "",
                finishedAt = now(),
                lastMessage = "迁移已停止：已处理 ${ctx.index}/${ctx.queue.size}",
            )
        }
        logger.i(
            TAG_ENGINE,
            "会话已停止：处理 ${ctx.index}/${ctx.queue.size} 个，成功=${snapshot.successCount} " +
                "失败=${snapshot.failedCount} 跳过=${snapshot.skippedCount} 释放=${formatBytes(snapshot.freedBytes)}",
        )
    }

    private fun onPaused(ctx: SessionContext, reason: PauseReason) {
        // 注意：不写完成记录、不删除本地、不推进 index（R3/R4/R5）
        val remaining = (ctx.queue.size - ctx.index).coerceAtLeast(0)
        updateState {
            it.copy(
                status = MigrationStatus.PAUSED,
                pauseReason = reason,
                currentFileName = "",
                currentFileBytes = 0,
                currentFileTotalBytes = 0,
                currentFileRetry = 0,
                currentStage = "",
                lastMessage = "${reason.description}；剩余 $remaining 个文件待迁移",
            )
        }
        logger.i(
            TAG_ENGINE,
            "会话已暂停（${reason.description}）：已处理 ${ctx.index}/${ctx.queue.size}，" +
                "当前文件保持未迁移状态，恢复后从第 ${ctx.index + 1} 个继续",
        )
        scheduleAutoResume(reason)
    }

    /** 无会话时的停止状态收尾 */
    private fun finishStopped() {
        sessionContext = null
        cancelAutoResume()
        updateState {
            it.copy(
                status = MigrationStatus.IDLE,
                pauseReason = PauseReason.NONE,
                currentFileName = "",
                currentFileBytes = 0,
                currentFileTotalBytes = 0,
                currentFileRetry = 0,
                currentStage = "",
                finishedAt = now(),
                lastMessage = "迁移已停止",
            )
        }
        logger.i(TAG_ENGINE, "迁移已停止")
    }

    /** 前置条件不满足时的暂停（不产生失败记录，符合「暂停不算失败」） */
    private fun applyPaused(reason: PauseReason, message: String) {
        updateState {
            it.copy(
                status = MigrationStatus.PAUSED,
                pauseReason = reason,
                currentFileName = "",
                currentFileBytes = 0,
                currentFileTotalBytes = 0,
                currentFileRetry = 0,
                currentStage = "",
                lastMessage = message,
            )
        }
        logger.w(TAG_ENGINE, "任务暂停（${reason.description}）：$message")
        scheduleAutoResume(reason)
    }

    // ================================================================ 自动恢复（网络/电量类暂停）

    /**
     * 自动恢复监视：仅对「可自动恢复」的暂停原因生效（网络/电量/NAS 暂不可达）。
     * 用户手动暂停不会被自动恢复，必须用户点继续。
     */
    private fun scheduleAutoResume(reason: PauseReason) {
        if (reason !in AUTO_RESUME_REASONS) return
        if (autoResumeJob?.isActive == true) return
        autoResumeJob = engineScope.launch {
            logger.i(TAG_ENGINE, "自动恢复监视已启动（每 ${AUTO_RESUME_INTERVAL_MS / 1000}s 检查网络/电量）")
            while (true) {
                delay(AUTO_RESUME_INTERVAL_MS)
                val snapshot = _state.value
                if (snapshot.status == MigrationStatus.IDLE ||
                    snapshot.status == MigrationStatus.COMPLETED ||
                    snapshot.status == MigrationStatus.FAILED
                ) {
                    logger.i(TAG_ENGINE, "自动恢复监视结束（状态=${snapshot.status}）")
                    break
                }
                if (snapshot.status != MigrationStatus.PAUSED) continue
                if (snapshot.pauseReason !in AUTO_RESUME_REASONS) continue
                if (!canAutoResume(snapshot.pauseReason)) continue
                logger.i(TAG_ENGINE, "条件已恢复（${snapshot.pauseReason.description}），自动继续迁移")
                resumeMigration()
            }
        }
    }

    private fun cancelAutoResume() {
        autoResumeJob?.cancel()
        autoResumeJob = null
    }

    /** 自动恢复前的条件复查（与 precheck 同口径，但不连接 NAS —— 由 Resume 流程负责连接） */
    private suspend fun canAutoResume(reason: PauseReason): Boolean {
        val config = nasConfigRepository.getActive() ?: return false
        if (!permissionGuard.hasMediaReadPermission() || !permissionGuard.hasDeletePermission()) {
            return false
        }
        val settings = try {
            settingsRepository.get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.w(TAG_ENGINE, "自动恢复：读取设置失败（${e.message}）")
            return false
        }
        val net = networkMonitor.snapshot()
        if (!net.isAvailable) return false
        if (settings.wifiOnly && !net.isWifi) return false
        val power = powerMonitor.snapshot()
        if (settings.lowBatteryPause && !power.isCharging &&
            power.percent in 1..settings.lowBatteryThreshold
        ) {
            return false
        }
        // 只有配置仍存在时才允许自动恢复（reason 仅用于日志可读性）
        logger.d(TAG_ENGINE, "自动恢复条件满足（原因=${reason.name}, config=${config.name}）")
        return true
    }

    // ================================================================ 单文件控制面

    /**
     * 会话内单文件控制面：
     *  - 中断检查基于原子标志（UI/服务线程可直接置位，无需等待命令被消费）；
     *  - 进度节流：≥300ms 或每 5% 才更新一次 StateFlow，避免高频进度回调把 Compose 重组/通知刷新打爆。
     */
    private inner class SessionProcessControl(
        private val ctx: SessionContext,
    ) : ProcessControl {

        private var lastProgressAtMs = 0L
        private var lastPercentBucket = -1

        override fun checkInterrupt(): InterruptSignal? {
            if (stopRequested.get()) return InterruptSignal.Stop
            if (pauseRequested.get()) return InterruptSignal.Pause(currentPauseReason())
            return null
        }

        /**
         * 上传遇到网络类失败时回调：若此刻网络规则已不满足，管线会把它升级为「暂停任务」，
         * 而不是记一条普通文件失败（阶段1 文档 2.2：网络中断（传输中）→ 立即暂停任务）。
         */
        override fun networkPauseReason(): PauseReason? {
            val net = networkMonitor.snapshot()
            updateState { it.copy(isNetworkAvailable = net.isAvailable, isWifi = net.isWifi) }
            if (!net.isAvailable) return PauseReason.NETWORK_LOST
            if (ctx.settings.wifiOnly && !net.isWifi) return PauseReason.WIFI_REQUIRED
            return null
        }

        override fun onStage(stage: String) {
            updateState { it.copy(currentStage = stage) }
        }

        override fun onProgress(writtenBytes: Long, totalBytes: Long) {
            val nowMs = SystemClock.elapsedRealtime()
            val total = if (totalBytes > 0) totalBytes else 0L
            val percentBucket = if (total > 0) ((writtenBytes * 100) / total / 5).toInt() else -1
            val finished = total > 0 && writtenBytes >= total
            // 节流：未到 300ms 且未跨过 5% 档位时不更新（上传完成/100% 时刻必更新）
            if (!finished && nowMs - lastProgressAtMs < PROGRESS_MIN_INTERVAL_MS &&
                percentBucket == lastPercentBucket
            ) {
                return
            }
            lastProgressAtMs = nowMs
            lastPercentBucket = percentBucket
            updateState {
                it.copy(
                    currentFileBytes = writtenBytes,
                    currentFileTotalBytes = if (total > 0) total else it.currentFileTotalBytes,
                )
            }
        }

        override fun onRetry(retryCount: Int) {
            updateState { it.copy(currentFileRetry = retryCount, currentFileBytes = 0) }
        }
    }

    // ================================================================ 工具

    /** 每次上传都重新 getContentResolver().openInputStream（支持重试/重算 MD5 重新开流） */
    private fun createUploadSource(source: MigrationSource): UploadSource =
        UploadSource(displayName = source.displayName, size = source.size) {
            appContext.contentResolver.openInputStream(source.uri)
                ?: throw FileNotFoundException("无法打开本地文件：${source.uri}")
        }

    /** 断开并关闭 NAS 客户端（尽力而为，绝不抛异常打断会话收尾） */
    private suspend fun releaseClient(client: NasClient) {
        try {
            client.disconnect()
        } catch (e: CancellationException) {
            // 清理阶段已处于 NonCancellable；若仍收到取消信号则忽略以完成释放
        } catch (e: Throwable) {
            logger.w(TAG_ENGINE, "断开 NAS 连接失败（忽略）：${e.message}")
        }
        try {
            client.close()
        } catch (e: Throwable) {
            logger.w(TAG_ENGINE, "关闭 NAS 客户端失败（忽略）：${e.message}")
        }
    }

    private fun NasErrorType.toPauseReason(): PauseReason = when (this) {
        NasErrorType.AUTH_FAILED -> PauseReason.NAS_AUTH_FAILED
        NasErrorType.INSUFFICIENT_SPACE -> PauseReason.NAS_NO_SPACE
        NasErrorType.LOCAL_PERMISSION_DENIED -> PauseReason.STORAGE_PERMISSION_LOST
        NasErrorType.REMOTE_PERMISSION_DENIED,
        NasErrorType.REMOTE_NOT_FOUND -> PauseReason.NAS_UNREACHABLE
        else -> PauseReason.NAS_UNREACHABLE
    }

    /** 当前暂停原因（默认用户手动暂停；前台服务时长上限时由服务置为 FOREGROUND_LIMIT） */
    private fun currentPauseReason(): PauseReason =
        pauseReasonOverride.get() ?: PauseReason.USER

    private fun updateState(transform: (MigrationState) -> MigrationState) {
        _state.update { current ->
            transform(current).copy(lastUpdatedAt = System.currentTimeMillis())
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    // ================================================================ 内部类型

    /** 一次迁移会话的上下文（只在会话协程内使用；暂停时保留以便断点恢复） */
    private class SessionContext(
        var config: NasConfig,
        var settings: AppSettings,
        val queue: MutableList<MigrationSource>,
        var index: Int,
        var client: NasClient?,
    )

    /** 会话循环结束原因 */
    private sealed interface LoopOutcome {
        data object Completed : LoopOutcome
        data object Stopped : LoopOutcome
        data class Paused(val reason: PauseReason) : LoopOutcome
    }

    private companion object {
        const val TAG_ENGINE = Logger.TAG_ENGINE
        const val NAS_SPACE_SAFETY_BYTES = 50L * 1024 * 1024 // 50MB 安全余量
        const val PROGRESS_MIN_INTERVAL_MS = 300L
        const val AUTO_RESUME_INTERVAL_MS = 5_000L
        const val SHUTDOWN_TIMEOUT_MS = 5_000L

        /** 允许自动恢复的暂停原因（用户手动暂停必须由用户点击继续） */
        val AUTO_RESUME_REASONS = setOf(
            PauseReason.NETWORK_LOST,
            PauseReason.WIFI_REQUIRED,
            PauseReason.LOW_BATTERY,
            PauseReason.NAS_UNREACHABLE,
        )
    }
}
