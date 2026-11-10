package com.nasphoto.migrator.protocol

import com.nasphoto.migrator.data.config.NasConfig
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Properties

/**
 * SMB/CIFS 协议实现（基于 jcifs-ng 2.1.10，SMB2/SMB3 协商，禁用 SMB1）。
 *
 * 实现要点（踩坑记录）：
 *  1) **认证**：`NtlmPasswordAuthenticator(username, password)` 支持 "DOMAIN\\user"、"user@domain"
 *     两种写法（jcifs-ng 内部自行解析），因此不需要在 UI 层拆域；
 *  2) **协议版本**：通过 PropertyConfiguration 设置 `jcifs.smb.client.minVersion=SMB202`、
 *     `maxVersion=SMB311`，避免回落 SMB1（现代 NAS 默认关闭 SMB1，回落只会得到难懂的报错）；
 *  3) **路径编码**：SmbFile 的构造参数是 URL，包含中文/空格的文件名必须逐段 percent-encode，
 *     否则会抛 MalformedURLException。本类统一用 [encodedUrl] 拼装，对外仍暴露未编码路径；
 *  4) **剩余空间**：`SmbFile.getDiskFreeSpace()` 只对目录有效，用共享根目录查询；
 *  5) **异常**：jcifs 的 SmbAuthException / SmbException（getNtStatus）→ 归一化为 [NasException]，
 *     供引擎判断「暂停整个任务」还是「跳过当前文件」；
 *  6) **MD5**：SMB 无服务端哈希接口，只能读取远程文件流计算（仅在用户开启 MD5 校验时发生，
 *     属于局域网内可接受的额外流量）。
 *
 * 本类只负责「连接 / 查询 / 建目录 / 上传 / 读」；**不包含删除用户远程文件的逻辑**
 * （唯一的 DELETE 是 testConnection 里删除本应用刚创建的 1 字节探针文件）。
 * 真正的删除只针对本地原图，且由引擎在「校验通过」之后调用 MediaStore 执行（铁则 R2）。
 */
class SmbNasClient(private val config: NasConfig) : NasClient {

    override val protocol: NasProtocol = NasProtocol.SMB

    override val configName: String = config.name

    override val remoteRootPath: String = config.remoteRootPath

    @Volatile
    private var context: CIFSContext? = null

    // ---------------------------------------------------------------------
    // 连接管理
    // ---------------------------------------------------------------------

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        try {
            val ctx = ensureContext()
            val shareRoot = SmbFile(encodedShareRoot(withTrailingSlash = true), ctx)
            try {
                if (!shareRoot.exists()) {
                    throw NasException(
                        NasErrorType.REMOTE_NOT_FOUND,
                        "SMB 共享不存在或当前账号无权访问：${config.smbShare}（host=${config.host}:${config.port}）",
                    )
                }
            } finally {
                shareRoot.closeQuietly()
            }
            // 目标目录可达性：不存在则创建，创建失败直接报权限问题（避免迁移到一半才发现路径写错）
            val targetDirUrl = encodedUrl(remoteRootPath, isDirectory = true)
            SmbFile(targetDirUrl, ctx).use { targetDir ->
                if (!targetDir.exists()) targetDir.mkdirs()
                if (!targetDir.exists()) {
                    throw NasException(
                        NasErrorType.REMOTE_PERMISSION_DENIED,
                        "SMB 目标目录不存在且无法创建：${config.remoteRootPath}",
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NasException) {
            throw e
        } catch (e: Throwable) {
            throw mapException(e, "连接 SMB 服务器失败")
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        context?.let { ctx ->
            runCatching { ctx.close() }
        }
        context = null
    }

    override fun close() {
        context?.let { ctx -> runCatching { ctx.close() } }
        context = null
    }

    /** 懒创建 CIFS 上下文（幂等，线程安全由 [synchronized] 保证） */
    @Synchronized
    private fun ensureContext(): CIFSContext {
        context?.let { return it }
        val ctx = createContext()
        context = ctx
        return ctx
    }

    /**
     * 创建带认证的 jcifs 上下文。
     * 注意：这里只创建对象，不做网络 IO（登录动作在第一次操作时才真正发生）。
     */
    private fun createContext(): CIFSContext {
        val props = Properties().apply {
            // SMB2.0.2 ~ SMB3.1.1；不协商 SMB1
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            // 连接/响应/读超时（毫秒）。大视频上传期间服务端可能长时间不返回响应包，
            // responseTimeout 设得偏大，避免把「正在传大文件」误判成超时。
            setProperty("jcifs.smb.client.connTimeout", CONN_TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.responseTimeout", RESPONSE_TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.soTimeout", SO_TIMEOUT_MS.toString())
            // 关闭 DFS 解析（家用 NAS 不需要，可避免不必要的域查找延时）
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            // 关闭 NetBIOS 名称缓存，IP 直连场景更稳定
            setProperty("jcifs.netbios.cachePolicy", "0")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val username = config.username.trim()
        return if (username.isEmpty()) {
            // 匿名/访客访问（部分 NAS 的家庭共享允许匿名只读/读写）
            base.withAnonymousCredentials()
        } else {
            base.withCredentials(NtlmPasswordAuthenticator(username, config.password))
        }
    }

    // ---------------------------------------------------------------------
    // 路径工具
    // ---------------------------------------------------------------------

    /** 共享根 URL（未编码 basePath），形如 smb://host:445/share */
    private fun encodedShareRoot(withTrailingSlash: Boolean): String {
        val host = config.host.trim()
        val share = config.smbShare.trim().trim('/')
        val sb = StringBuilder("smb://").append(host).append(':').append(config.port)
        if (share.isNotEmpty()) {
            sb.append('/').append(encodeSegment(share))
        }
        if (withTrailingSlash && !sb.endsWith("/")) sb.append('/')
        return sb.toString()
    }

    /**
     * 把对外的未编码远程路径转成 SmbFile 可用的 URL（逐段 encode）。
     *
     * @param isDirectory 目录需以 "/" 结尾（jcifs 以此区分文件/目录）
     */
    private fun encodedUrl(remotePath: String, isDirectory: Boolean = false): String {
        val sb = StringBuilder(encodedShareRoot(withTrailingSlash = false))
        val baseSegments = config.basePath.trim('/').split('/').filter { it.isNotBlank() }
        baseSegments.forEach { sb.append('/').append(encodeSegment(it)) }

        val suffixSegments = suffixOf(remotePath).split('/').filter { it.isNotBlank() }
        suffixSegments.forEach { sb.append('/').append(encodeSegment(it)) }

        if (isDirectory && !sb.endsWith("/")) sb.append('/')
        return sb.toString()
    }

    /**
     * 从「配置根目录的完整远程路径」中取出相对部分（如 DCIM/Camera/a.jpg）。
     * 引擎保证 remotePath 一定由 remoteRootPath 拼出；这里保留兜底逻辑防止异常输入。
     */
    private fun suffixOf(remotePath: String): String {
        val root = remoteRootPath.trimEnd('/')
        val normalized = remotePath.trim()
        if (normalized == root) return ""
        if (normalized.startsWith("$root/")) return normalized.substring(root.length + 1)

        // 兜底：从 share 名之后截断
        val share = config.smbShare.trim().trim('/')
        if (share.isNotEmpty()) {
            val index = normalized.indexOf("/$share/")
            if (index >= 0) return normalized.substring(index + share.length + 2)
        }
        return normalized
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    // ---------------------------------------------------------------------
    // 连接测试
    // ---------------------------------------------------------------------

    override suspend fun testConnection(): NasConnectionInfo = withContext(Dispatchers.IO) {
        try {
            val ctx = ensureContext()
            val shareRootUrl = encodedShareRoot(withTrailingSlash = true)

            // 1) 共享是否存在 / 是否可访问（认证失败通常在这里暴露）
            SmbFile(shareRootUrl, ctx).use { shareRoot ->
                if (!shareRoot.exists()) {
                    throw NasException(
                        NasErrorType.REMOTE_NOT_FOUND,
                        "SMB 共享不存在：${config.smbShare}",
                    )
                }
                if (!shareRoot.isDirectory) {
                    throw NasException(
                        NasErrorType.REMOTE_NOT_FOUND,
                        "配置的 SMB 共享不是一个目录：${config.smbShare}",
                    )
                }
            }

            // 2) 目标目录：不存在则尝试创建（顺便验证写权限）
            val targetDirUrl = encodedUrl(remoteRootPath, isDirectory = true)
            SmbFile(targetDirUrl, ctx).use { targetDir ->
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                if (!targetDir.exists() || !targetDir.isDirectory) {
                    throw NasException(
                        NasErrorType.REMOTE_PERMISSION_DENIED,
                        "NAS 目标目录不存在且无法创建：${config.remoteRootPath}",
                    )
                }
            }

            // 3) 可写探测：写入一个 1 字节临时文件后立即删除
            val probeUrl = RemotePathUtil.join(
                targetDirUrl,
                ".nas_migrator_write_test_${System.currentTimeMillis()}.tmp",
            )
            SmbFile(probeUrl, ctx).use { probe ->
                try {
                    probe.outputStream.use { out ->
                        out.write(WRITE_PROBE_BYTES)
                        out.flush()
                    }
                } finally {
                    runCatching { if (probe.exists()) probe.delete() }
                }
            }

            // 4) 剩余空间（失败不影响连接结论；部分 NAS 会返回 0 / -1，视为未知）
            val freeSpace = runCatching {
                SmbFile(shareRootUrl, ctx).use { it.diskFreeSpace }.takeIf { free -> free > 0L }
            }.getOrNull()

            NasConnectionInfo(
                protocol = NasProtocol.SMB,
                serverDescription = "SMB 共享=${config.smbShare}；目录=${config.basePath.ifBlank { "/" }}；SMB2/SMB3",
                remoteRootPath = remoteRootPath,
                writable = true,
                freeSpaceBytes = freeSpace,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: NasException) {
            throw e
        } catch (e: Throwable) {
            throw mapException(e, "SMB 连接测试失败")
        }
    }

    // ---------------------------------------------------------------------
    // 远程信息
    // ---------------------------------------------------------------------

    override suspend fun remoteFileInfo(remotePath: String): NasFileInfo? =
        withContext(Dispatchers.IO) {
            try {
                val ctx = ensureContext()
                SmbFile(encodedUrl(remotePath), ctx).use { file ->
                    if (!file.exists()) return@withContext null
                    val isDir = file.isDirectory
                    NasFileInfo(
                        remotePath = remotePath,
                        size = if (isDir) -1L else file.length(),
                        isDirectory = isDir,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SmbException) {
                throw mapSmbException(e, remotePath)
            } catch (e: Throwable) {
                throw mapException(e, "查询远程文件信息失败：$remotePath")
            }
        }

    override suspend fun ensureDirectory(remotePath: String): Unit = withContext(Dispatchers.IO) {
        try {
            val ctx = ensureContext()
            val dirUrl = encodedUrl(remotePath, isDirectory = true)
            SmbFile(dirUrl, ctx).use { dir ->
                if (!dir.exists()) {
                    // jcifs 的 mkdirs 会自动递归创建父目录
                    dir.mkdirs()
                }
                if (!dir.exists()) {
                    throw NasException(
                        NasErrorType.REMOTE_PERMISSION_DENIED,
                        "无法创建远程目录（可能无写权限）：$remotePath",
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NasException) {
            throw e
        } catch (e: Throwable) {
            throw mapException(e, "创建远程目录失败：$remotePath")
        }
    }

    // ---------------------------------------------------------------------
    // 上传（流式 + 进度）
    // ---------------------------------------------------------------------

    override suspend fun upload(
        remotePath: String,
        source: UploadSource,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        try {
            val ctx = ensureContext()
            val file = SmbFile(encodedUrl(remotePath), ctx)
            try {
                var written = 0L
                var lastReported = 0L
                val total = source.size
                // 先打开本地流：若本地文件已被删除/无法读取，此时远端尚未被 O_TRUNC 截断，
                // 不会在 NAS 上留下一个 0 字节残片。
                val localStream = source.openStream()
                try {
                    file.outputStream.use { out ->
                        localStream.use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read > 0) {
                                    out.write(buffer, 0, read)
                                    written += read
                                    // 进度节流：每 256KB 或传输结束时回调一次。
                                    // 说明：任务书要求「实时上报进度」，256KB 对视频仍足够细，
                                    // 同时避免每 64KB 回调一次带来的 UI/状态机抖动。
                                    if (written - lastReported >= PROGRESS_STEP_BYTES ||
                                        (total > 0 && written >= total)
                                    ) {
                                        lastReported = written
                                        onProgress(written, total)
                                    }
                                }
                            }
                        }
                        out.flush()
                    }
                } finally {
                    // 兜底：打开远端输出流失败时（无权限/连接断开），本地流也必须关闭，避免 MediaStore fd 泄漏
                    runCatching { localStream.close() }
                }
                onProgress(written, total)
                written
            } finally {
                file.closeQuietly()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NasException) {
            throw e
        } catch (e: Throwable) {
            // 业务层通过 onProgress 抛出的「暂停/停止」中断异常不是 IO 异常：
            // 必须原样抛出，否则暂停会被错误记成「上传失败」（R3 语义）。
            if (e !is java.io.IOException) throw e
            throw mapException(e, "上传失败：$remotePath")
        }
    }

    // ---------------------------------------------------------------------
    // 空间 / MD5
    // ---------------------------------------------------------------------

    override suspend fun freeSpaceBytes(): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = ensureContext()
            SmbFile(encodedShareRoot(withTrailingSlash = true), ctx).use { it.diskFreeSpace }
                .takeIf { free -> free > 0L } // 0 / -1 视为无法获取
        }.getOrNull()
    }

    override suspend fun remoteMd5(remotePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val ctx = ensureContext()
            SmbFile(encodedUrl(remotePath), ctx).use { file ->
                if (!file.exists() || file.isDirectory) return@withContext null
                val digest = MessageDigest.getInstance("MD5")
                file.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) digest.update(buffer, 0, read)
                    }
                }
                digest.digest().toHex()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // SMB 不存在「服务器不支持 MD5」的情况：读取失败是真实错误（网络/权限/文件消失），
            // 抛出交由引擎处理（引擎会记 WARN 并降级为 SIZE 校验，不会误删本地）。
            throw mapException(e, "读取远程 MD5 失败：$remotePath")
        }
    }

    // ---------------------------------------------------------------------
    // 异常归一化
    // ---------------------------------------------------------------------

    /** SmbException → NasException（按 NT Status 精确分类） */
    private fun mapSmbException(e: SmbException, path: String): NasException {
        val status = e.ntStatus
        val type = when {
            // SmbAuthException extends SmbException：必须先按类型判定，否则会被 NTStatus 覆盖成「权限不足」
            e is SmbAuthException -> NasErrorType.AUTH_FAILED
            status == NT_STATUS_OBJECT_NAME_NOT_FOUND -> NasErrorType.REMOTE_NOT_FOUND
            status == NT_STATUS_OBJECT_PATH_NOT_FOUND -> NasErrorType.REMOTE_NOT_FOUND
            status == NT_STATUS_ACCESS_DENIED -> NasErrorType.REMOTE_PERMISSION_DENIED
            status == NT_STATUS_DISK_FULL -> NasErrorType.INSUFFICIENT_SPACE
            status == NT_STATUS_LOGON_FAILURE -> NasErrorType.AUTH_FAILED
            status == NT_STATUS_OBJECT_NAME_COLLISION -> NasErrorType.REMOTE_CONFLICT
            status == NT_STATUS_IO_TIMEOUT -> NasErrorType.TIMEOUT
            else -> NasErrorType.CONNECTION_FAILED
        }
        return NasException(type, "SMB 操作失败（NTStatus=0x${Integer.toHexString(status)}）：$path / ${e.message}", e)
    }

    /** 通用异常归一化（区分认证/主机不可达/超时/权限） */
    private fun mapException(e: Throwable, prefix: String): NasException {
        if (e is NasException) return e
        val type = when (e) {
            is java.io.FileNotFoundException -> NasErrorType.LOCAL_READ_FAILED
            is SmbAuthException -> NasErrorType.AUTH_FAILED
            is SmbException -> {
                val status = e.ntStatus
                when {
                    status == NT_STATUS_ACCESS_DENIED -> NasErrorType.REMOTE_PERMISSION_DENIED
                    status == NT_STATUS_OBJECT_NAME_NOT_FOUND -> NasErrorType.REMOTE_NOT_FOUND
                    status == NT_STATUS_DISK_FULL -> NasErrorType.INSUFFICIENT_SPACE
                    status == NT_STATUS_LOGON_FAILURE -> NasErrorType.AUTH_FAILED
                    else -> NasErrorType.CONNECTION_FAILED
                }
            }
            is java.net.UnknownHostException -> NasErrorType.HOST_UNREACHABLE
            is java.net.ConnectException -> NasErrorType.CONNECTION_FAILED
            is java.net.SocketTimeoutException -> NasErrorType.TIMEOUT
            is java.io.InterruptedIOException -> NasErrorType.TIMEOUT
            is java.net.SocketException -> NasErrorType.CONNECTION_FAILED
            is javax.net.ssl.SSLException -> NasErrorType.CONNECTION_FAILED
            is java.net.MalformedURLException -> NasErrorType.PROTOCOL_UNSUPPORTED
            else -> NasErrorType.CONNECTION_FAILED
        }
        return NasException(type, "$prefix（${e.javaClass.simpleName}）：${e.message}", e)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024L
        const val CONN_TIMEOUT_MS = 15_000
        const val RESPONSE_TIMEOUT_MS = 300_000
        const val SO_TIMEOUT_MS = 300_000

        /** 通用 NT Status（负数形式，与 SmbException.getNtStatus() 返回值一致） */
        val NT_STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034.toInt()
        val NT_STATUS_OBJECT_PATH_NOT_FOUND = 0xC000003A.toInt()
        val NT_STATUS_ACCESS_DENIED = 0xC0000022.toInt()
        val NT_STATUS_DISK_FULL = 0xC000007F.toInt()
        val NT_STATUS_LOGON_FAILURE = 0xC000006D.toInt()
        val NT_STATUS_OBJECT_NAME_COLLISION = 0xC0000035.toInt()
        val NT_STATUS_IO_TIMEOUT = 0xC00000B5.toInt()

        val WRITE_PROBE_BYTES = byteArrayOf(0x4F, 0x4B) // "OK"
    }
}

/** SmbFile.close() 不声明异常，但内部可能抛 RuntimeException，统一吞掉避免影响主流程 */
private fun SmbFile.closeQuietly() {
    runCatching { close() }
}

/** 读取输入流并计算 MD5（保留给未来复用；当前 SMB 远程 MD5 使用内联实现） */
@Suppress("unused")
private fun InputStream.md5Hex(): String {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read > 0) digest.update(buffer, 0, read)
    }
    val sb = StringBuilder(32)
    for (b in digest.digest()) {
        val v = b.toInt() and 0xFF
        if (v < 0x10) sb.append('0')
        sb.append(Integer.toHexString(v))
    }
    return sb.toString()
}
