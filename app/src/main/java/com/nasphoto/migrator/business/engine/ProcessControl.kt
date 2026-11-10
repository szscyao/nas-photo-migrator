package com.nasphoto.migrator.business.engine

import com.nasphoto.migrator.business.model.PauseReason

/**
 * 单文件处理阶段文案（推送到 [com.nasphoto.migrator.business.model.MigrationState.currentStage]
 * 后由 UI / 通知栏展示）。
 */
object MigrationStage {
    const val CHECK = "检查"
    const val UPLOAD = "上传"
    const val VERIFY = "校验"
    const val DELETE = "删除本地"
    const val RECORD = "写入记录"
}

/**
 * 中断信号：单文件处理过程中检测到「暂停 / 停止」请求。
 *
 * 为什么不直接用协程取消：
 *  - 暂停/停止需要「安全退出并保留断点」，而协程取消是整条会话生命周期的终止；
 *  - 暂停后要保留队列位置、要把 state 置成 PAUSED 并安排自动恢复，语义比取消更细；
 *  - 用普通异常在单文件管线内向上传播，能精确控制「哪一步之后不再继续」，
 *    保证被中断的文件既不写完成记录、也不删除本地（R3/R5）。
 */
sealed interface InterruptSignal {

    /** 暂停请求（用户手动 / 网络 / 电量 / 权限 / NAS 空间） */
    data class Pause(val reason: PauseReason) : InterruptSignal

    /** 停止请求（用户结束本次会话） */
    data object Stop : InterruptSignal
}

/**
 * 单文件处理被中断的**可识别异常**。
 *
 * 只有管线内部能抛出它；引擎在会话循环里捕获后回到安全点（不写完成记录、不删本地、不推进队列）。
 * 注意它不是 [kotlinx.coroutines.CancellationException]，因此不会被协程框架吞掉，
 * 也不会被当作任务取消处理 —— 暂停后仍要继续使用同一个会话协程。
 */
class ProcessInterruptedException(
    val signal: InterruptSignal,
) : Exception(describeInterrupt(signal))

/** 生成中断异常的可读描述（顶层函数：避免在 super 构造参数里访问尚未初始化的属性） */
private fun describeInterrupt(signal: InterruptSignal): String = when (signal) {
    is InterruptSignal.Pause -> "单文件处理被中断（暂停：${signal.reason.description}）"
    InterruptSignal.Stop -> "单文件处理被中断（用户停止）"
}

/**
 * 引擎提供给单文件管线的控制面（进度上报 + 中断检查）。
 *
 * 实现方是迁移引擎的会话上下文；管线只通过这个接口与外界交互，便于保证：
 *  - 进度更新节流（≥300ms 或每 5%）由引擎实现，管线不关心 UI 刷新频率；
 *  - 中断检查只发生在管线显式调用的安全点（上传进度回调、每次重试前、各阶段之间），
 *    绝不在「校验通过 → 删除本地」的临界区内插入中断点（避免删到一半的状态）。
 */
internal interface ProcessControl {

    /** 返回非 null 表示已收到暂停/停止请求，管线应立即抛出 [ProcessInterruptedException] */
    fun checkInterrupt(): InterruptSignal?

    /**
     * 上传遇到网络类失败时查询网络规则：
     * 返回非 null 表示「此刻网络已不满足迁移规则」，引擎应暂停任务等待恢复
     * （阶段1 文档 2.2：网络中断（传输中）→ 立即暂停任务，而不是把文件记为失败）。
     */
    fun networkPauseReason(): PauseReason?

    /** 上报当前单文件所处阶段（检查/上传/校验/删除/记录） */
    fun onStage(stage: String)

    /** 上报上传进度（字节）；调用频率由此接口的实现方节流 */
    fun onProgress(writtenBytes: Long, totalBytes: Long)

    /** 上报「当前文件已重试次数」 */
    fun onRetry(retryCount: Int)
}

/** 安全点：若已请求暂停/停止则立即抛出可识别异常（不删除本地、不写完成记录） */
internal fun ProcessControl.interruptIfRequested() {
    val signal = checkInterrupt() ?: return
    throw ProcessInterruptedException(signal)
}
