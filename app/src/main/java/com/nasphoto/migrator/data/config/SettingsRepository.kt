package com.nasphoto.migrator.data.config

import kotlinx.coroutines.flow.Flow

/**
 * 应用设置仓库（明文 SharedPreferences 即可，设置项不含敏感信息）。
 */
interface SettingsRepository {

    val settingsFlow: Flow<AppSettings>

    suspend fun get(): AppSettings

    /** 全量保存 */
    suspend fun save(settings: AppSettings)

    /** 局部更新（在最新值基础上 transform 后保存） */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings
}
