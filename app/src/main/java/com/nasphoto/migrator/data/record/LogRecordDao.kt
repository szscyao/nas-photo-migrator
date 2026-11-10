package com.nasphoto.migrator.data.record

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogRecordDao {

    @Insert
    suspend fun insert(record: LogRecord): Long

    @Query("SELECT * FROM log_records ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun flowRecent(limit: Int): Flow<List<LogRecord>>

    @Query("SELECT * FROM log_records ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<LogRecord>

    @Query("SELECT * FROM log_records ORDER BY timestamp ASC, id ASC")
    suspend fun all(): List<LogRecord>

    @Query("SELECT COUNT(*) FROM log_records")
    suspend fun count(): Int

    @Query("DELETE FROM log_records")
    suspend fun clearAll()

    /** 只保留最近 [keep] 条，防止日志表无限增长 */
    @Query("DELETE FROM log_records WHERE id NOT IN (SELECT id FROM log_records ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
