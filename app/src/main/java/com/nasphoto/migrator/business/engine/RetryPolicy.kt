package com.nasphoto.migrator.business.engine

import com.nasphoto.migrator.protocol.NasErrorType

/**
 * 上传重试策略（阶段1 设计决策 4：最多 3 次重试，指数退避 2s / 4s / 8s）。
 *
 * 重要边界（与铁则的关系）：
 *  - 重试只针对「瞬时网络类错误」（见 [NasErrorType.retryable]），认证失败/空间不足/权限不足
 *    这类全局性问题重试多少次都不会成功，必须立刻失败并让引擎暂停整个任务（[NasErrorType.shouldPauseTask]）；
 *  - 无论重试多少次，**本地文件都不删除**（R3）：删除只发生在「上传成功 + 校验通过」之后；
 *  - 每次重试都必须重新 [com.nasphoto.migrator.protocol.UploadSource.openStream]，
 *    因为 MediaStore 流不可 reset（见 UploadSource 的契约注释）。
 */
class RetryPolicy(
    /** 最大重试次数（不含首次尝试）→ 总尝试次数 = maxRetries + 1 = 4 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** 首次退避基数（毫秒） */
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    /** 退避倍数 */
    val backoffMultiplier: Long = DEFAULT_MULTIPLIER,
    /** 单次退避上限（毫秒） */
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {

    /** 总尝试次数上限（1 次首发 + N 次重试） */
    val maxAttempts: Int get() = maxRetries + 1

    /**
     * 第 [retryIndex] 次重试（1 起）之前的等待时间：
     * 1 → 2s，2 → 4s，3 → 8s（与阶段1 文档一致的指数退避）。
     */
    fun delayForRetry(retryIndex: Int): Long {
        if (retryIndex <= 0) return 0L
        var delay = initialDelayMs
        repeat(retryIndex - 1) {
            delay *= backoffMultiplier
        }
        return delay.coerceAtMost(maxDelayMs)
    }

    /**
     * 该错误是否值得再试一次。
     * 已尝试次数 [attempt] 达到 [maxAttempts] 时返回 false（不再重试）。
     */
    fun shouldRetry(attempt: Int, errorType: NasErrorType): Boolean =
        attempt < maxAttempts && errorType.retryable

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_INITIAL_DELAY_MS = 2_000L
        const val DEFAULT_MULTIPLIER = 2L
        const val DEFAULT_MAX_DELAY_MS = 8_000L
    }
}
