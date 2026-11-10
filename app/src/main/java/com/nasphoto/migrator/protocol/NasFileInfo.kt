package com.nasphoto.migrator.protocol

/**
 * NAS 端文件元信息。[size] 为 NAS 侧文件的真实字节数，是「完整性校验」的第一个依据（铁则 R2）。
 */
data class NasFileInfo(
    /** 远程完整路径（协议内部路径，如 smb://host/share/dir/a.jpg 或 https://host/dav/dir/a.jpg） */
    val remotePath: String,
    /** 文件字节数（目录为 -1） */
    val size: Long,
    val isDirectory: Boolean = false,
)

/**
 * 连接测试结果，用于 NAS 配置页展示（不抛异常的成功路径）。
 */
data class NasConnectionInfo(
    val protocol: NasProtocol,
    /** 服务器/共享描述，如 SMB 的 share 列表或 WebDAV 的 server 头 */
    val serverDescription: String?,
    /** 迁移目标根路径（客户端内部真实 URL 形式） */
    val remoteRootPath: String,
    /** 根路径是否可写（上传前预检） */
    val writable: Boolean,
    /** 远程可用空间（字节），无法获取时为 null */
    val freeSpaceBytes: Long? = null,
)
