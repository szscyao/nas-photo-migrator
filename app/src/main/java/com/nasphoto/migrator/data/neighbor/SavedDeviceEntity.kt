package com.nasphoto.migrator.data.neighbor

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 「我的设备」Room 实体（表名 `saved_devices`）。
 *
 * 存在的意义（需求 2.5）：用户再次打开「网络邻居」时**先立即展示已保存设备**，
 * 不必等待 15 秒的全网段扫描；扫描/连接成功后再由仓库补充刷新。
 *
 * 字段与契约模型 [SavedDevice] 一一对应：
 *  - [ip] 主键：局域网内 IP 唯一，直接作为去重键（同 IP 设备重新扫描到就 REPLACE 覆盖）；
 *  - [hostName] 设备名（NetBIOS/mDNS 拿不到时为 null，UI 回退显示 IP）；
 *  - [shareCount] 上次探测到的共享数量（未知为 null）；
 *  - [lastUsedAt] 上次使用时间（毫秒时间戳），列表按此倒序。
 *
 * 注意：这是**独立数据库**（NeighborDatabase），不要与既有迁移记录库 MigratorDatabase 混用。
 */
@Entity(tableName = "saved_devices")
data class SavedDeviceEntity(
    @PrimaryKey val ip: String,
    val hostName: String?,
    val shareCount: Int?,
    val lastUsedAt: Long,
)
