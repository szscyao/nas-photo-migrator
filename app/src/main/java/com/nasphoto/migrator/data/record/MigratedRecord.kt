package com.nasphoto.migrator.data.record

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「已完成迁移」记录表 —— **断点续传的唯一依据**。
 *
 * 写入时机（铁则 R5）：仅当「上传成功 → 完整性校验通过 → 本地删除成功（含二次确认）」三步
 * 全部完成后，引擎才写入本表。**删除失败不写本表**（只写失败记录），因此：
 *  - 不会出现「记录显示已完成、本地原图却还在」的假象；
 *  - 该文件下次启动仍会重新出现在待迁移队列（远端已有完整副本，会走「远程已存在且校验一致 →
 *    直接删除本地」分支，不会重复消耗上传带宽）。
 *
 * 任何失败分支都不写本表，因此重启后这些文件会重新出现在待迁移队列中（R4 断点续传的差集语义）。
 */
@Entity(
    tableName = "migrated_records",
    indices = [Index(value = ["migratedAt"])],
)
data class MigratedRecord(
    /** 复合唯一键：MD5(RELATIVE_PATH|DISPLAY_NAME|SIZE|DATE_MODIFIED) */
    @PrimaryKey val fileKey: String,
    val displayName: String,
    val uriString: String,
    val sizeBytes: Long,
    /** 远程完整路径 */
    val remotePath: String,
    val albumName: String,
    /** 迁移完成时间戳 */
    val migratedAt: Long,
    /** 本地原图是否已确认删除 */
    val deletedLocal: Boolean,
    /** 校验方式："SIZE" 或 "MD5" */
    val verification: String,
)
