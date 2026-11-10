package com.nasphoto.migrator.data.record

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nasphoto.migrator.protocol.NasErrorType

/**
 * 「失败文件」记录表。
 *
 * 铁则 R3：上传/校验失败、网络中断的文件**绝不删除本地**，只在此表留痕，
 * 引擎随后自动处理下一张。异常管理页据此展示并可一键重试。
 *
 * 同一 fileKey 多次失败只保留一条记录（累加 attempts、刷新 failedAt），
 * 避免重复上传造成失败表爆炸。
 */
@Entity(
    tableName = "failure_records",
    indices = [Index(value = ["fileKey"], unique = true), Index(value = ["failedAt"])],
)
data class FailureRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileKey: String,
    val displayName: String,
    val uriString: String,
    val sizeBytes: Long,
    val remotePath: String,
    val albumName: String,
    /** 归一化失败原因 */
    val errorType: NasErrorType,
    /** 详细原因（含异常 message），用于排查 */
    val reason: String,
    /** 累计尝试次数（含自动重试） */
    val attempts: Int,
    val failedAt: Long,
)
