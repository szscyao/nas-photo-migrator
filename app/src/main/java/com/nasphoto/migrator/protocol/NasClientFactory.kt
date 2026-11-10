package com.nasphoto.migrator.protocol

import com.nasphoto.migrator.data.config.NasConfig

/**
 * NAS 客户端工厂：按配置的协议类型创建对应实现。
 *
 * 调用约定：
 *  - **同步返回**，不建立网络连接（连接由 `NasClient.connect()` / `testConnection()` 负责），
 *    因此可以安全地在主线程、ViewModel、引擎会话里调用；
 *  - 每次迁移会话创建一个客户端，会话结束调用 `disconnect()` / `close()` 释放连接池；
 *  - 协议差异全部隐藏在本包内，业务层只依赖 [NasClient] 接口。
 */
object NasClientFactory {

    fun create(config: NasConfig): NasClient = when (config.protocol) {
        NasProtocol.SMB -> SmbNasClient(config)
        NasProtocol.WEBDAV -> WebDavNasClient(config)
    }
}
