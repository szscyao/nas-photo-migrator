package com.nasphoto.migrator.data.neighbor

import com.nasphoto.migrator.data.config.NasConfig
import com.nasphoto.migrator.protocol.NasProtocol
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 网络邻居（局域网发现向导）的数据模型与仓库契约。
 *
 * 说明：
 *  - 本包只服务于「网络邻居」增量功能，**不修改**既有 `data/config`、`data/record` 任何文件；
 *  - 「我的设备」使用**独立的 Room 数据库**（NeighborDatabase），避免影响既有迁移记录库的 schema 版本；
 *  - 凭据落盘复用 `data/config/EncryptedPrefsFactory`（EncryptedSharedPreferences + Keystore），
 *    绝不使用明文 SharedPreferences，绝不写日志。
 */

// ============================================================================
// 我的设备（收藏）
// ============================================================================

/**
 * 「我的设备」条目：扫描到过或成功连接过的 SMB 主机，保证再次打开网络邻居时**先立即展示**。
 */
data class SavedDevice(
    /** 主键：IP（局域网内唯一） */
    val ip: String,
    /** 设备名（NetBIOS/DNS），拿不到时回退展示 IP */
    val hostName: String? = null,
    /** 上次探测到的共享数量（可为空） */
    val shareCount: Int? = null,
    /** 上次使用时间（毫秒时间戳），列表按此倒序 */
    val lastUsedAt: Long = System.currentTimeMillis(),
)

/** 「我的设备」仓库（Room 实现，协程/Flow 接口） */
interface SavedDeviceRepository {

    /** 已保存设备（按 lastUsedAt 倒序），先展示它、再等扫描结果 */
    val devicesFlow: Flow<List<SavedDevice>>

    suspend fun list(): List<SavedDevice>

    /** 新增或更新（按 ip 去重） */
    suspend fun upsert(device: SavedDevice)

    /** 更新使用时间（连接成功/生成配置时调用） */
    suspend fun touch(ip: String, hostName: String? = null)

    suspend fun delete(ip: String)

    suspend fun clear()
}

// ============================================================================
// 凭据（加密存储）
// ============================================================================

/**
 * 一台主机的 SMB 凭据。
 *
 * 落盘要求：EncryptedSharedPreferences（AES256-GCM/SIV，Keystore 主密钥），
 * 任何日志、失败记录、UI 快照中都不得包含 [password] 明文。
 */
data class NeighborCredentials(
    val host: String,
    val username: String,
    val password: String,
) {
    val isAnonymous: Boolean get() = username.isBlank()
}

/** 邻居凭据仓库（加密存储；Keystore 不可用时降级为内存模式，绝不回退明文） */
interface NeighborCredentialStore {

    suspend fun get(host: String): NeighborCredentials?

    suspend fun save(credentials: NeighborCredentials)

    suspend fun remove(host: String)
}

// ============================================================================
// 选择结果 → 标准 NasConfig
// ============================================================================

/**
 * 把「网络邻居向导」的选择结果转换成标准 [NasConfig]（**纯函数**，可单元测试）。
 *
 * 字段规范（任务单 2.4）：
 *  - id：UUID
 *  - name：设备名优先，空则回退 IP
 *  - protocol：SMB
 *  - host：所选设备 IP；port：445
 *  - username/password：输入值（匿名可为空串）
 *  - smbShare：所选共享名（去掉首尾斜杠）
 *  - basePath：所选目录相对共享根的路径（根目录为空串；去掉首尾斜杠）
 *  - useHttps：false
 */
object NeighborConfigFactory {

    fun create(
        deviceIp: String,
        deviceName: String?,
        share: String,
        basePath: String,
        username: String,
        password: String,
        port: Int = 445,
        id: String = UUID.randomUUID().toString(),
        createdAt: Long = System.currentTimeMillis(),
    ): NasConfig {
        val host = deviceIp.trim()
        val name = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: host
        return NasConfig(
            id = id,
            name = name,
            protocol = NasProtocol.SMB,
            host = host,
            port = port,
            username = username.trim(),
            password = password,
            smbShare = share.trim().trim('/'),
            basePath = basePath.trim().trim('/'),
            useHttps = false,
            createdAt = createdAt,
        )
    }
}
