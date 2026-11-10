package com.nasphoto.migrator.data.neighbor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [SavedDeviceRepository] 的 Room 实现（「我的设备」表）。
 *
 * 线程模型：Flow 由 Room 在后台查询并推送（无需额外切换线程）；
 * 所有 suspend 方法统一 `withContext(Dispatchers.IO)`，绝不在主线程碰数据库。
 *
 * 数据语义：
 *  - [upsert] 按 ip 覆盖整行，`lastUsedAt` 使用调用方模型里的值（由业务层决定“使用时间”）；
 *  - [touch] 只刷新时间戳（可顺带补设备名），设备尚未入库时兜底插入一条，
 *    保证「成功连接过但没经过扫描列表」的设备也会出现在「我的设备」里。
 */
class RoomSavedDeviceRepository(context: Context) : SavedDeviceRepository {

    private val dao: SavedDeviceDao = NeighborDatabase.get(context.applicationContext).savedDeviceDao()

    override val devicesFlow: Flow<List<SavedDevice>> =
        dao.flowAll().map { entities -> entities.map { it.toModel() } }

    override suspend fun list(): List<SavedDevice> = withContext(Dispatchers.IO) {
        dao.all().map { it.toModel() }
    }

    override suspend fun upsert(device: SavedDevice) {
        withContext(Dispatchers.IO) {
            val entity = device.toEntity()
            if (entity.ip.isEmpty()) {
                Log.w(TAG, "upsert: ip 为空，忽略该设备")
                return@withContext
            }
            dao.upsert(entity)
        }
    }

    override suspend fun touch(ip: String, hostName: String?) {
        withContext(Dispatchers.IO) {
            val normalizedIp = ip.trim()
            if (normalizedIp.isEmpty()) return@withContext
            // 空字符串设备名视为“没有新名字”，传 null 让 SQL 的 COALESCE 保留旧值
            val normalizedName = hostName?.trim()?.takeIf { it.isNotEmpty() }
            val now = System.currentTimeMillis()
            val affected = dao.touch(normalizedIp, now, normalizedName)
            if (affected == 0) {
                // 该 IP 之前没入库（例如直接输入 IP 连接、没走扫描列表）：补插一条
                dao.upsert(
                    SavedDeviceEntity(
                        ip = normalizedIp,
                        hostName = normalizedName,
                        shareCount = null,
                        lastUsedAt = now,
                    )
                )
            }
        }
    }

    override suspend fun delete(ip: String) {
        withContext(Dispatchers.IO) {
            val normalizedIp = ip.trim()
            if (normalizedIp.isEmpty()) return@withContext
            dao.deleteByIp(normalizedIp)
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { dao.clearAll() }
    }

    // ---------------- entity ↔ model 映射 ----------------

    private fun SavedDeviceEntity.toModel(): SavedDevice = SavedDevice(
        ip = ip,
        hostName = hostName?.takeIf { it.isNotBlank() },
        shareCount = shareCount,
        lastUsedAt = lastUsedAt,
    )

    private fun SavedDevice.toEntity(): SavedDeviceEntity = SavedDeviceEntity(
        ip = ip.trim(),
        hostName = hostName?.trim()?.takeIf { it.isNotEmpty() },
        shareCount = shareCount,
        // upsert 的 lastUsedAt 取 model 值（契约要求），不在仓库里偷偷改成 now
        lastUsedAt = lastUsedAt,
    )

    private companion object {
        private const val TAG = "SavedDeviceRepository"
    }
}
