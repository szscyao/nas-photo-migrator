package com.nasphoto.migrator.protocol

/**
 * 支持的 NAS 协议类型。
 *
 * 阶段1技术选型（锁定）：
 *  - [SMB] 使用 jcifs-ng（SMB2/SMB3），依赖 eu.agno3.jcifs:jcifs-ng:2.1.10
 *  - [WEBDAV] 使用 sardine-android，依赖 com.github.thegrizzlylabs:sardine-android:0.9（JitPack）
 */
enum class NasProtocol(
    val displayName: String,
    /** 默认端口：SMB 445；WebDAV 根据 http/https 自动选择 80/443 */
    val defaultPort: Int,
) {
    SMB("SMB / CIFS", 445),
    WEBDAV("WebDAV", 80),
    ;

    /** WebDAV 是否使用 https 时推荐端口 */
    fun recommendedPort(useHttps: Boolean = false): Int = when (this) {
        SMB -> 445
        WEBDAV -> if (useHttps) 443 else 80
    }
}
