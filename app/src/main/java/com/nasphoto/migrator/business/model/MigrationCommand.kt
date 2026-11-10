package com.nasphoto.migrator.business.model

/**
 * 引擎命令（UI / 通知栏动作 → 调度器命令队列）。
 *
 * 所有命令都投递到同一个 Channel，由唯一的消费者协程串行处理，
 * 保证「同一时间只有一个命令在改变引擎状态」（铁则 R1 的延伸）。
 */
sealed interface MigrationCommand {

    /** 开始迁移（从 MediaStore 重建待迁移队列） */
    data object Start : MigrationCommand

    /** 暂停（当前文件处理完/中断后不删除本地，保存断点） */
    data object Pause : MigrationCommand

    /** 继续（从当前文件重新开始，不重复上传已完成文件） */
    data object Resume : MigrationCommand

    /** 停止（结束本次会话，释放前台服务） */
    data object Stop : MigrationCommand
}

/**
 * 开始前的队列预览（首页统计卡片）。
 */
data class MigrationPreview(
    val totalCount: Int = 0,
    val totalBytes: Long = 0,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    /** 预计可释放空间 == totalBytes（删除成功后实际释放） */
    val estimatedFreeBytes: Long = 0,
    /** 已迁移过的数量（断点续传差集之后仍在队列中的文件数即 totalCount） */
    val alreadyMigratedCount: Int = 0,
)
