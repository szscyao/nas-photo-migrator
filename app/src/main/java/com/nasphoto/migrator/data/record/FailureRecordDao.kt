package com.nasphoto.migrator.data.record

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FailureRecordDao {

    @Query("SELECT * FROM failure_records ORDER BY failedAt DESC")
    fun flowAll(): Flow<List<FailureRecord>>

    @Query("SELECT * FROM failure_records ORDER BY failedAt DESC")
    suspend fun all(): List<FailureRecord>

    @Query("SELECT * FROM failure_records WHERE fileKey = :fileKey LIMIT 1")
    suspend fun findByFileKey(fileKey: String): FailureRecord?

    @Insert
    suspend fun insert(record: FailureRecord): Long

    @Update
    suspend fun update(record: FailureRecord)

    @Query("DELETE FROM failure_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM failure_records WHERE fileKey = :fileKey")
    suspend fun deleteByFileKey(fileKey: String)

    @Query("DELETE FROM failure_records")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM failure_records")
    fun countFlow(): Flow<Int>
}
