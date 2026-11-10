package com.nasphoto.migrator.data.config

import com.nasphoto.migrator.protocol.NasProtocol

/**
 * 一套 NAS 连接配置（可保存多套，同一时间只有一套处于激活/迁移目标状态）。
 *
 * 安全约定：
 *  - [password] 只存在于内存与本仓库的加密存储中（EncryptedSharedPreferences + Android Keystore）；
 *  - 任何写入日志/失败记录的代码都必须使用 [sanitized] 后的副本。
 */
data class NasConfig(
    /** 唯一 ID（UUID） */
    val id: String,
    /** 配置显示名，如「家里群晖」 */
    val name: String,
    val protocol: NasProtocol,
    /** 主机/IP，如 192.168.1.10 或 nas.example.com */
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** SMB 共享名（SMB 必填，如 home）；WebDAV 忽略 */
    val smbShare: String = "",
    /** 迁移根目录（相对共享或绝对路径），如 PhotoBackup 或 /PhotoBackup */
    val basePath: String = "",
    /** WebDAV 是否使用 HTTPS（SMB 忽略） */
    val useHttps: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {

    val isSmb: Boolean get() = protocol == NasProtocol.SMB
    val isWebDav: Boolean get() = protocol == NasProtocol.WEBDAV

    /**
     * 迁移根目录的完整远程路径（协议内部形式）：
     *  - SMB:    smb://host:port/share/basePath
     *  - WebDAV: https://host:port/basePath
     */
    val remoteRootPath: String
        get() {
            val base = basePath.trim().trim('/')
            return when (protocol) {
                NasProtocol.SMB -> buildString {
                    append("smb://").append(host.trim()).append(':').append(port)
                    append('/').append(smbShare.trim().trim('/'))
                    if (base.isNotEmpty()) append('/').append(base)
                }
                NasProtocol.WEBDAV -> buildString {
                    append(if (useHttps) "https" else "http").append("://").append(host.trim())
                    val defaultPort = if (useHttps) 443 else 80
                    if (port > 0 && port != defaultPort) append(':').append(port)
                    if (base.isNotEmpty()) append('/').append(base)
                }
            }
        }

    /** 日志/UI 展示用的脱敏副本 */
    fun sanitized(): NasConfig = copy(password = "")

    /** 配置页/首页展示用的一句话摘要（不含密码） */
    fun summary(): String = when (protocol) {
        NasProtocol.SMB -> "smb://$host:$port/$smbShare/$basePath"
        NasProtocol.WEBDAV -> "${if (useHttps) "https" else "http"}://$host:$port/$basePath"
    }

    companion object {
        /** 新建配置的默认骨架（配置页「新增」时使用） */
        fun draft(protocol: NasProtocol = NasProtocol.SMB): NasConfig {
            val id = java.util.UUID.randomUUID().toString()
            return NasConfig(
                id = id,
                name = "我的 NAS",
                protocol = protocol,
                host = "",
                port = protocol.recommendedPort(false),
                username = "",
                password = "",
                smbShare = "",
                basePath = "PhotoBackup",
                useHttps = false,
            )
        }
    }
}
