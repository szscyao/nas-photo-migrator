package com.nasphoto.migrator.data.neighbor

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 「我的设备」DAO（Room）。
 *
 * 设计要点：
 *  - 列表统一 `ORDER BY lastUsedAt DESC`：最近连接/使用的设备排最前，符合「打开就在」的产品诉求；
 *  - [upsert] 用 `REPLACE` 实现按 ip 去重（Room 的 REPLACE = 存在则整行覆盖）；
 *  - [touch] 只更新时间戳（可选同时补设备名），避免连接成功时整行覆盖丢字段；
 *    `COALESCE(:hostName, hostName)` 表示传 null 时保留原设备名。
 */
@Dao
interface SavedDeviceDao {

    /** 已保存设备流（按上次使用时间倒序），给 UI 直接 collect */
    @Query("SELECT * FROM saved_devices ORDER BY lastUsedAt DESC")
    fun flowAll(): Flow<List<SavedDeviceEntity>>

    /** 一次性读取（按上次使用时间倒序） */
    @Query("SELECT * FROM saved_devices ORDER BY lastUsedAt DESC")
    suspend fun all(): List<SavedDeviceEntity>

    /** 按 IP 查单条（不存在返回 null） */
    @Query("SELECT * FROM saved_devices WHERE ip = :ip LIMIT 1")
    suspend fun findByIp(ip: String): SavedDeviceEntity?

    /** 新增或更新（同 ip 整行覆盖） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: SavedDeviceEntity)

    /** 更新上次使用时间；hostName 传 null 时保留旧设备名。返回受影响行数（0 = 该 IP 尚未入库）。 */
    @Query(
        "UPDATE saved_devices SET lastUsedAt = :at, hostName = COALESCE(:hostName, hostName) " +
            "WHERE ip = :ip"
    )
    suspend fun touch(ip: String, at: Long, hostName: String?): Int

    /** 删除单台设备（按 IP） */
    @Query("DELETE FROM saved_devices WHERE ip = :ip")
    suspend fun deleteByIp(ip: String)

    /** 清空「我的设备」（仅用于用户显式操作；扫描结果可随时重建） */
    @Query("DELETE FROM saved_devices")
    suspend fun clearAll()
}
