package com.nasphoto.migrator.business.model

/**
 * 迁移引擎对外状态快照（UI 与通知栏的唯一数据源）。
 *
 * 由引擎以 `StateFlow<MigrationState>` 推送；UI 只读，不直接修改。
 */
data class MigrationState(
    val status: MigrationStatus = MigrationStatus.IDLE,
    val pauseReason: PauseReason = PauseReason.NONE,

    /** 本次会话待迁移文件总数（含已处理） */
    val totalCount: Int = 0,
    /** 已处理（成功 + 失败 + 跳过） */
    val processedCount: Int = 0,
    /** 成功：上传 + 校验 + 本地删除 全部完成 */
    val successCount: Int = 0,
    /** 失败（本地文件已保留） */
    val failedCount: Int = 0,
    /** 跳过（NAS 端已存在且校验一致 / 记录显示已完成 / 本地文件已消失） */
    val skippedCount: Int = 0,

    /** 待迁移总字节（预计可释放空间） */
    val totalBytes: Long = 0,
    /** 已释放空间（删除成功文件的字节数之和） */
    val freedBytes: Long = 0,

    /** 当前正在处理的文件名 */
    val currentFileName: String = "",
    /** 当前文件已上传字节 */
    val currentFileBytes: Long = 0,
    /** 当前文件总字节 */
    val currentFileTotalBytes: Long = 0,
    /** 当前文件已重试次数 */
    val currentFileRetry: Int = 0,
    /** 当前单文件所处阶段文案：检查/上传/校验/删除/记录 */
    val currentStage: String = "",

    /** 最近一条状态说明（首页展示） */
    val lastMessage: String = "",
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,

    /** 当前迁移目标配置名 */
    val activeConfigName: String = "",

    /** 本次会话累计失败数（与历史失败表区分） */
    val sessionFailedCount: Int = 0,

    /** NAS 剩余空间（字节），可获得时展示 */
    val nasFreeSpaceBytes: Long? = null,

    val isNetworkAvailable: Boolean = true,
    val isWifi: Boolean = true,
    val batteryPercent: Int = -1,
    val isCharging: Boolean = false,

    val lastUpdatedAt: Long = 0L,
) {

    /** 当前文件进度 0f~1f */
    val currentFileProgress: Float
        get() {
            if (currentFileTotalBytes <= 0L) return 0f
            return (currentFileBytes.toFloat() / currentFileTotalBytes.toFloat()).coerceIn(0f, 1f)
        }

    /** 整体进度 0f~1f（按文件数） */
    val overallProgress: Float
        get() {
            if (totalCount <= 0) return 0f
            return (processedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
        }

    val remainingCount: Int get() = (totalCount - processedCount).coerceAtLeast(0)

    /** 剩余预计释放空间 */
    val remainingBytes: Long get() = (totalBytes - freedBytes).coerceAtLeast(0L)
}
