package com.nasphoto.migrator.data.record

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [MigrationStore] 的 Room 实现。
 */
class MigrationStoreImpl(context: Context) : MigrationStore {

    private val db = MigratorDatabase.get(context.applicationContext)
    private val migratedDao = db.migratedRecordDao()
    private val failureDao = db.failureRecordDao()
    private val logDao = db.logRecordDao()

    // ---------------- 已完成记录 ----------------

    override suspend fun migratedFileKeys(): Set<String> = migratedDao.allFileKeys().toHashSet()

    override suspend fun isMigrated(fileKey: String): Boolean = migratedDao.exists(fileKey)

    override suspend fun recordMigrated(record: MigratedRecord) = migratedDao.upsert(record)

    override suspend fun migratedCount(): Int = migratedDao.count()

    override fun migratedCountFlow(): Flow<Int> = migratedDao.countFlow()

    override fun freedBytesFlow(): Flow<Long> = migratedDao.freedBytesFlow()

    override suspend fun recentMigrated(limit: Int): List<MigratedRecord> = migratedDao.recent(limit)

    override suspend fun clearMigrated() = migratedDao.clearAll()

    // ---------------- 失败记录 ----------------

    override suspend fun recordFailure(record: FailureRecord) {
        val existing = failureDao.findByFileKey(record.fileKey)
        if (existing == null) {
            failureDao.insert(record)
        } else {
            failureDao.update(
                existing.copy(
                    errorType = record.errorType,
                    reason = record.reason,
                    attempts = existing.attempts + record.attempts.coerceAtLeast(1),
                    failedAt = record.failedAt,
                    remotePath = record.remotePath,
                    sizeBytes = record.sizeBytes,
                )
            )
        }
    }

    override fun failuresFlow(): Flow<List<FailureRecord>> = failureDao.flowAll()

    override suspend fun failuresSnapshot(): List<FailureRecord> = failureDao.all()

    override suspend fun removeFailure(id: Long) = failureDao.deleteById(id)

    override suspend fun removeFailureByFileKey(fileKey: String) = failureDao.deleteByFileKey(fileKey)

    override suspend fun clearFailures() = failureDao.clearAll()

    override fun failureCountFlow(): Flow<Int> = failureDao.countFlow()

    // ---------------- 日志 ----------------

    override suspend fun log(level: LogLevel, tag: String, message: String) {
        logDao.insert(
            LogRecord(
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
            )
        )
        // 日志表自我限流：超过上限时裁剪，避免长期运行耗尽存储
        val count = logDao.count()
        if (count > MAX_LOG_ROWS) {
            logDao.trimTo(MAX_LOG_ROWS - TRIM_BATCH)
        }
    }

    override fun logsFlow(limit: Int): Flow<List<LogRecord>> = logDao.flowRecent(limit)

    override suspend fun allLogs(): List<LogRecord> = logDao.all()

    override suspend fun clearLogs() = logDao.clearAll()

    private companion object {
        const val MAX_LOG_ROWS = 5000
        const val TRIM_BATCH = 1000
    }
}
