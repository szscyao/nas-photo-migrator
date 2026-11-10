package com.nasphoto.migrator.business.engine

import com.nasphoto.migrator.business.model.MigrationPreview
import com.nasphoto.migrator.business.model.MigrationState
import kotlinx.coroutines.flow.StateFlow

/**
 * 迁移引擎对外接口（UI / 前台服务只依赖此接口）。
 *
 * 实现约束（铁则，任何优化都不得改动）：
 *  R1 单线程串行：内部唯一消费者协程 + 单文件循环，绝对禁止并发上传。
 *  R2 校验通过才删：删除本地只发生在完整性校验通过之后。
 *  R3 失败绝不删本地：任何失败分支只写失败记录并继续下一张。
 *  R4 断点续传：完成记录（fileKey）持久化，重启后差集续跑。
 *  R5 删完才进下一张：本地删除并二次确认成功后才进入下一轮。
 */
interface MigrationEngineApi {

    /** 引擎状态（UI + 通知栏唯一数据源） */
    val state: StateFlow<MigrationState>

    /** 计算开始前预览（待迁移数量/字节、图片/视频数） */
    suspend fun loadPreview(): MigrationPreview

    /** 开始迁移（异步投递命令，立即返回） */
    fun startMigration()

    /** 暂停（当前文件处理到安全点后暂停，不删除未校验文件） */
    fun pauseMigration()

    /** 继续 */
    fun resumeMigration()

    /** 停止（安全退出本次会话） */
    fun stopMigration()

    /** 释放资源（进程退出/服务销毁时调用，不做删除动作） */
    suspend fun shutdown()
}
