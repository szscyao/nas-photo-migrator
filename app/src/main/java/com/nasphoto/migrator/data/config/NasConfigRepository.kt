package com.nasphoto.migrator.data.config

import kotlinx.coroutines.flow.Flow

/**
 * NAS 多配置仓库。
 *
 * 持久化实现要求（阶段2）：
 *  - 使用 `androidx.security:security-crypto` 的 EncryptedSharedPreferences
 *    （MasterKey AES256-GCM，密钥由 Android Keystore 保护）；
 *  - 整个配置列表序列化为 JSON 存入该加密文件，密码因此不会以明文落盘；
 *  - 同时维护「当前激活配置 ID」。
 */
interface NasConfigRepository {

    /** 全部配置（含密码，供连接使用）。UI 展示前应调用 [NasConfig.sanitized]。 */
    val configsFlow: Flow<List<NasConfig>>

    /** 当前激活（迁移目标）配置，未设置时为 null */
    val activeConfigFlow: Flow<NasConfig?>

    suspend fun list(): List<NasConfig>

    suspend fun get(id: String): NasConfig?

    suspend fun getActive(): NasConfig?

    /** 新增或更新（按 id 去重） */
    suspend fun upsert(config: NasConfig)

    suspend fun delete(id: String)

    /** 设置激活配置；传 null 表示取消激活 */
    suspend fun setActive(id: String?)
}
