package com.nasphoto.migrator.data.record

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MigratedRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: MigratedRecord)

    @Query("SELECT EXISTS(SELECT 1 FROM migrated_records WHERE fileKey = :fileKey)")
    suspend fun exists(fileKey: String): Boolean

    @Query("SELECT fileKey FROM migrated_records")
    suspend fun allFileKeys(): List<String>

    @Query("SELECT * FROM migrated_records ORDER BY migratedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MigratedRecord>

    @Query("SELECT COUNT(*) FROM migrated_records")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM migrated_records")
    fun countFlow(): Flow<Int>

    /** 已释放空间 = 所有已完成文件的字节数之和 */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM migrated_records")
    fun freedBytesFlow(): Flow<Long>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM migrated_records")
    suspend fun freedBytes(): Long

    @Query("DELETE FROM migrated_records WHERE fileKey = :fileKey")
    suspend fun delete(fileKey: String)

    @Query("DELETE FROM migrated_records")
    suspend fun clearAll()
}
