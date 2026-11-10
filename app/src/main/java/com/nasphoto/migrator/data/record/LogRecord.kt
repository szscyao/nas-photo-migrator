package com.nasphoto.migrator.data.record

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 日志级别 */
enum class LogLevel(val label: String) {
    DEBUG("调试"),
    INFO("信息"),
    WARN("警告"),
    ERROR("错误"),
}

/**
 * 本地日志表（纯本地，不上报任何服务器）。
 *
 * 引擎在每个关键节点写入（开始任务、单文件开始、上传完成、校验结果、删除结果、失败原因、
 * 暂停/恢复/停止），日志页按时间倒序展示，可导出为文本文件分享。
 */
@Entity(
    tableName = "log_records",
    indices = [Index(value = ["timestamp"])],
)
data class LogRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)
