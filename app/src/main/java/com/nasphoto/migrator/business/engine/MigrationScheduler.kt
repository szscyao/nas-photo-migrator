package com.nasphoto.migrator.business.engine

import com.nasphoto.migrator.business.log.Logger
import com.nasphoto.migrator.business.model.MigrationCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 迁移调度器 —— **铁则 R1（单线程串行）的物理落点**。
 *
 * 结构（阶段1 文档 2.3 的代码化）：
 *  - 一个 [Channel]<[MigrationCommand]>（Start/Pause/Resume/Stop），容量无限，UI 与前台服务可以
 *    随意投递，永不阻塞调用方；
 *  - **一个**命令消费者协程，独占消费该 Channel，命令因此天然串行；
 *  - **一个**会话协程（[launchSession] 原子防重入），单文件「上传 → 校验 → 删除 → 记录」闭环
 *    只在这个会话协程里执行；
 *  - 命令消费者与会话协程都运行在 `Dispatchers.IO.limitedParallelism(1)` 上：
 *    即使有人误写成并发，IO 池也只会分配**同一个**工作线程给它们，
 *    物理上不存在第二个文件处理协程（R1 无法被绕过）。
 *
 * 为什么不用 `Dispatchers.Default.limitedParallelism(1)`：
 *  - NAS 协议（jcifs-ng / sardine）是阻塞 API，必须跑在 IO 型线程池上；
 *  - 但通过 limitedParallelism(1) 把 IO 池「收窄成单线程」，同时满足「阻塞安全」与「物理串行」。
 */
class MigrationScheduler(
    private val logger: Logger,
    /** 命令处理器（由引擎提供；同一时间只会执行一个命令） */
    private val onCommand: suspend (MigrationCommand) -> Unit,
) {

    /**
     * 唯一执行线程：IO 弹性线程池中严格限制 1 个并发。
     * 命令消费者、会话循环、单文件管线全部跑在这里。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val serialDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val scope = CoroutineScope(
        SupervisorJob() + serialDispatcher + CoroutineName("nas-migration-scheduler"),
    )

    private val commandChannel = Channel<MigrationCommand>(capacity = Channel.UNLIMITED)

    /** 会话互斥：保证同一时间最多只有 1 个迁移会话协程 */
    private val sessionGuard = AtomicBoolean(false)

    @Volatile
    private var consumerJob: Job? = null

    @Volatile
    private var sessionJob: Job? = null

    /** 是否已有迁移会话在跑（供引擎判断幂等） */
    val isSessionActive: Boolean get() = sessionGuard.get()

    /** 启动唯一命令消费者（幂等：重复调用不会创建第二个消费者） */
    fun ensureConsumer() {
        synchronized(this) {
            if (consumerJob?.isActive == true) return
            logger.i(TAG, "启动命令消费者（单线程串行调度器）")
            consumerJob = scope.launch {
                consumeCommands()
            }
        }
    }

    /** 投递命令（非阻塞；Channel 无限容量，永不阻塞 UI/服务线程） */
    fun submit(command: MigrationCommand) {
        val result = commandChannel.trySend(command)
        if (result.isFailure) {
            logger.w(TAG, "命令投递失败（调度器已关闭）：$command")
        }
    }

    /**
     * 启动**唯一**迁移会话协程。
     *
     * @return true 表示成功启动；false 表示已有会话在运行（引擎据此忽略重复的 Start）。
     */
    fun launchSession(block: suspend () -> Unit): Boolean {
        if (!sessionGuard.compareAndSet(false, true)) {
            logger.w(TAG, "已存在活动迁移会话，拒绝重复启动（R1：同一时间只允许一个会话）")
            return false
        }
        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                logger.i(TAG, "迁移会话协程被取消")
                throw e
            } catch (e: Throwable) {
                // 兜底：会话内部已有完整 try/catch，这里只防止异常逃逸导致 SupervisorJob 之外的日志缺失
                logger.e(TAG, "迁移会话未捕获异常：${e.message}", e)
            } finally {
                sessionGuard.set(false)
                sessionJob = null
            }
        }
        sessionJob = job
        return true
    }

    /** 等待当前会话结束（shutdown 时用于有序释放 NAS 客户端） */
    suspend fun awaitSession() {
        sessionJob?.join()
    }

    /** 关闭调度器：关闭命令通道并取消整个作用域（会话会在 finally 中释放资源） */
    fun shutdown() {
        logger.i(TAG, "关闭调度器")
        commandChannel.close()
        consumerJob = null
        sessionJob = null
        scope.cancel()
    }

    /** 唯一命令消费者：for 循环串行消费，命令处理异常不会杀死消费者 */
    private suspend fun consumeCommands() {
        for (command in commandChannel) {
            try {
                logger.d(TAG, "消费命令：$command")
                onCommand(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.e(TAG, "处理命令 $command 失败：${e.message}", e)
            }
        }
        logger.i(TAG, "命令通道已关闭，消费者退出")
    }

    private companion object {
        const val TAG = Logger.TAG_ENGINE
    }
}
