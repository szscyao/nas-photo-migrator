package com.nasphoto.migrator.protocol

import java.io.Closeable

/**
 * NAS 协议统一接口（SMB / WebDAV 双实现的抽象）。
 *
 * 设计约束：
 *  - 所有方法都声明为 suspend，实现内部必须切到 IO 线程（协议 SDK 是阻塞 API）；
 *  - 所有异常必须归一化为 [NasException]（携带 [NasErrorType]）；
 *  - 本接口**不含任何删除远程文件/批量逻辑**：迁移链路的删除只针对「本地原图」，
 *    远程只做「上传 + 校验」，符合铁则 R2（校验通过才删本地）。
 */
interface NasClient : Closeable {

    val protocol: NasProtocol

    /** 当前连接的配置名，用于日志 */
    val configName: String

    /** 迁移目标根路径（协议内部真实 URL） */
    val remoteRootPath: String

    /**
     * 建立连接（SMB 为登录 + 树连接，WebDAV 为一次 PROPFIND 探活）。
     * 失败抛 [NasException]。
     */
    suspend fun connect()

    /** 主动断开连接（幂等，不抛异常）。 */
    suspend fun disconnect()

    /**
     * 连接测试：验证主机可达、认证通过、目标目录存在或可创建，并尽量返回远程剩余空间。
     * 失败抛 [NasException]。
     */
    suspend fun testConnection(): NasConnectionInfo

    /**
     * 查询远程文件信息。
     * @return 文件不存在时返回 null；存在时返回 [NasFileInfo]（目录 isDirectory=true）
     */
    suspend fun remoteFileInfo(remotePath: String): NasFileInfo?

    /** 远程文件是否存在（默认实现基于 [remoteFileInfo]） */
    suspend fun remoteExists(remotePath: String): Boolean = remoteFileInfo(remotePath) != null

    /**
     * 确保远程目录存在（递归创建）。
     * 失败抛 [NasException]（权限/空间/连接类）。
     */
    suspend fun ensureDirectory(remotePath: String)

    /**
     * 流式上传单个文件（覆盖同名文件）。
     *
     * 注意：本方法只负责「上传」，**不负责校验与删除**。校验与删除由
     * [com.nasphoto.migrator.business.verify.IntegrityVerifier] 与迁移管线按铁则执行。
     *
     * @param remotePath 远程完整路径
     * @param source 本地数据源（可重复打开流以支持重试）
     * @param onProgress 已写入字节数 / 总字节数 回调（在 IO 线程回调，调用方自行切线程/节流）
     * @return 实际写入的字节数
     */
    suspend fun upload(
        remotePath: String,
        source: UploadSource,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ): Long

    /**
     * 远程剩余空间（字节）。SMB 走 diskFreeSpace；WebDAV 无标准接口，无法获取时返回 null。
     */
    suspend fun freeSpaceBytes(): Long?

    /**
     * 远程文件 MD5（小写十六进制 32 位）。
     *  - SMB：通过读取远程文件流计算；
     *  - WebDAV：优先读服务器属性（如 Nextcloud 的 getcontentmd5），不支持则返回 null；
     * 返回 null 表示「服务器不支持 MD5 校验」，引擎将退回仅大小校验并记录日志。
     */
    suspend fun remoteMd5(remotePath: String): String?
}
