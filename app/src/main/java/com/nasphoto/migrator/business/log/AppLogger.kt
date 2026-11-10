package com.nasphoto.migrator.business.log

import android.util.Log
import com.nasphoto.migrator.data.record.LogLevel
import com.nasphoto.migrator.data.record.MigrationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * [Logger] 的默认实现：**fire-and-forget** 落库 + `android.util.Log` 镜像。
 *
 * 为什么必须 fire-and-forget（这是 R1/R2/R5 的性能前提，不是可选优化）：
 *  - 迁移主线程（单文件闭环协程，物理上只有一条）在每个关键节点都要写日志；
 *    如果这里 `runBlocking` 或直接 `suspend` 等待 Room 写完，磁盘 IO 会成为上传/校验/删除的
 *    一部分，拖慢「校验通过 → 删除本地」的关键路径，还会让暂停/停止响应变迟钝；
 *  - 因此调用方只做两件事：① 同步写一条 `android.util.Log`（内存操作，纳秒级，异常可观测）；
 *    ② 把日志投进无阻塞 Channel，立刻返回；
 *  - 真正写 Room 的是本类内部**唯一**的日志消费协程（独立于迁移主线程），
 *    Room 写失败也只会落到 logcat，绝不影响迁移闭环的成败判定。
 *
 * 容量策略：Channel 有界 + DROP_OLDEST。日志永远不能成为「背压」把迁移主线程卡住；
 * 极端情况下（例如日志表被大量查询拖慢）丢弃最旧日志是可接受的，迁移数据的正确性不受影响。
 */
class AppLogger(
    private val store: MigrationStore,
    scope: CoroutineScope,
) : Logger {

    /** 待落库日志（有界；满了丢最旧，保证生产者永不阻塞） */
    private val queue = Channel<Entry>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        // 唯一的日志消费协程：与迁移主线程完全解耦（迁移主线程在 IO.limitedParallelism(1) 上）
        scope.launch(Dispatchers.IO) {
            for (entry in queue) {
                try {
                    store.log(entry.level, entry.tag, entry.message)
                } catch (e: CancellationException) {
                    // 协程取消必须原样抛出，不能被吞（否则作用域无法正常结束）
                    throw e
                } catch (e: Throwable) {
                    // 落库失败只镜像到 logcat，绝不让日志链路反向影响迁移闭环
                    Log.w(TAG, "日志落库失败：${entry.tag} / ${e.message}")
                }
            }
        }
    }

    override fun d(tag: String, message: String) {
        Log.d(tag, message)
        enqueue(LogLevel.DEBUG, tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
        enqueue(LogLevel.INFO, tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
        enqueue(LogLevel.WARN, tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
        // 异常堆栈随日志一起落库（截断到合理长度，避免 SQLite 单行过大）
        val detail = if (throwable == null) {
            message
        } else {
            val stack = Log.getStackTraceString(throwable)
            "$message | ${throwable.javaClass.simpleName}: ${throwable.message}\n" +
                stack.take(MAX_STACK_CHARS)
        }
        enqueue(LogLevel.ERROR, tag, detail)
    }

    /** 纯内存投递，绝不阻塞调用方（迁移主线程可安全高频调用） */
    private fun enqueue(level: LogLevel, tag: String, message: String) {
        val result = queue.trySend(Entry(level, tag, message.take(MAX_MESSAGE_CHARS)))
        if (result.isFailure) {
            // Channel 已关闭（进程退出中）或异常状态：忽略，绝不影响迁移
            Log.w(TAG, "日志投递失败（队列已关闭），丢弃：$tag / $message")
        }
    }

    private data class Entry(val level: LogLevel, val tag: String, val message: String)

    private companion object {
        const val TAG = "AppLogger"
        const val QUEUE_CAPACITY = 1024
        const val MAX_MESSAGE_CHARS = 4000
        const val MAX_STACK_CHARS = 4000
    }
}
