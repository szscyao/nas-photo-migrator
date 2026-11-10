package com.nasphoto.migrator.data.record

import kotlinx.coroutines.flow.Flow

/**
 * 迁移记录统一存取门面（Room 之上的薄封装）。
 *
 * 三张表的职责：
 *  - migrated_records：断点续传依据（只写成功完成闭环的文件）；
 *  - failure_records：失败留痕（本地文件一律保留）；
 *  - log_records：运行日志（纯本地）。
 *
 * 线程约定：所有 suspend 方法内部已切到 Room 的 IO 线程，调用方无需再切。
 */
interface MigrationStore {

    // ---------------- 已完成记录 ----------------

    /** 全量已完成 fileKey 集合（构建待迁移队列时做差集） */
    suspend fun migratedFileKeys(): Set<String>

    suspend fun isMigrated(fileKey: String): Boolean

    /** 写入一条已完成记录（REPLACE 语义，同一 fileKey 不会重复） */
    suspend fun recordMigrated(record: MigratedRecord)

    suspend fun migratedCount(): Int

    fun migratedCountFlow(): Flow<Int>

    /** 累计已释放空间（字节） */
    fun freedBytesFlow(): Flow<Long>

    suspend fun recentMigrated(limit: Int): List<MigratedRecord>

    /** 清空已完成记录（仅用于「重置迁移记录」这种显式操作，会重新扫描全部相册） */
    suspend fun clearMigrated()

    // ---------------- 失败记录 ----------------

    /** 同 fileKey 已存在则累加 attempts 并刷新原因，否则插入 */
    suspend fun recordFailure(record: FailureRecord)

    fun failuresFlow(): Flow<List<FailureRecord>>

    suspend fun failuresSnapshot(): List<FailureRecord>

    suspend fun removeFailure(id: Long)

    suspend fun removeFailureByFileKey(fileKey: String)

    suspend fun clearFailures()

    fun failureCountFlow(): Flow<Int>

    // ---------------- 日志 ----------------

    suspend fun log(level: LogLevel, tag: String, message: String)

    fun logsFlow(limit: Int = 500): Flow<List<LogRecord>>

    suspend fun allLogs(): List<LogRecord>

    suspend fun clearLogs()
}
