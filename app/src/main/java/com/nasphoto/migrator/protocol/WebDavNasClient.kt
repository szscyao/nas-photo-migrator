package com.nasphoto.migrator.protocol

import com.nasphoto.migrator.data.config.NasConfig
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.xml.namespace.QName

/**
 * WebDAV 协议实现。
 *
 * 选型说明（与阶段1文档一致）：WebDAV 的「查询/建目录/空间」使用 sardine-android 0.9
 * （JitPack 发布，纯 Java WebDAV 门面，省去手写 PROPFIND Multi-Status 解析）。
 *
 * **为什么不直接用 sardine 上传**：
 *  - sardine-android 0.9 的 `Sardine` 接口只暴露 `put(url, byte[], ...)` / `put(url, File, ...)`，
 *    没有 `put(url, InputStream)`。用 byte[] 需要把整张照片读进内存（大视频直接 OOM），
 *    用 File 需要先复制一份到本地临时文件（迁移场景等于把存储占用翻倍，违背产品目标）。
 * 所以上传改为 **OkHttp 直连 PUT + 自定义 RequestBody 流式写**：
 *  - 全程流式，不落临时文件、不进内存；
 *  - `writeTo` 内按 64KB 块写入并回调进度，天然满足「逐张上传 + 实时进度」；
 *  - 仍然只走 WebDAV 标准方法（PUT/MKCOL/PROPFIND），与 sardine 使用同一 OkHttpClient。
 *
 * 铁则约束：本类**不删除任何用户远程文件**；唯一的 DELETE 是 testConnection 中清理本应用
 * 刚创建的 1 字节探针文件。真正的删除只针对本地原图（引擎在校验通过后执行）。
 */
class WebDavNasClient(private val config: NasConfig) : NasClient {

    override val protocol: NasProtocol = NasProtocol.WEBDAV

    override val configName: String = config.name

    override val remoteRootPath: String = config.remoteRootPath

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 读取超时用于等待服务器响应（大文件上传期间不会触发，OkHttp 写超时单独计算）
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // 不限总时长：一张大视频可能传很久
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    @Volatile
    private var sardine: Sardine? = null

    // ---------------------------------------------------------------------
    // 连接管理
    // ---------------------------------------------------------------------

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        try {
            val client = ensureSardine()
            // 用 PROPFIND 探活并校验认证（401 会在这里抛出）
            val rootUrl = encodedUrl(remoteRootPath, isDirectory = true)
            if (!client.exists(rootUrl)) {
                // 目标根目录尚未创建：逐级 MKCOL（这也是写权限的第一次验证）
                ensureDirectory(remoteRootPath)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NasException) {
            throw e
        } catch (e: SardineException) {
            throw mapSardineException(e, "WebDAV 连接失败")
        } catch (e: Throwable) {
            throw mapException(e, "WebDAV 连接失败")
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        sardine = null
        releaseHttpResources()
    }

    override fun close() {
        sardine = null
        releaseHttpResources()
    }

    /**
     * 释放连接资源（幂等）。
     * 注意：**不能** shutdown OkHttp dispatcher —— 否则同一实例再次 connect 时，
     * 复用的 httpClient 会抛 RejectedExecutionException（引擎虽然每会话新建客户端，
     * 但 UI「测试连接」后立刻迁移等场景可能出现复用，这里保持惰性安全）。
     */
    private fun releaseHttpResources() {
        runCatching {
            httpClient.connectionPool.evictAll()
            httpClient.cache?.close()
        }
    }

    @Synchronized
    private fun ensureSardine(): Sardine {
        sardine?.let { return it }
        val client = OkHttpSardine(httpClient)
        val username = config.username.trim()
        if (username.isNotEmpty()) {
            // 预认证：避免每个请求先收 401 再重发（SMB/WebDAV 双协议的 NAS 密码校验成本不低）
            client.setCredentials(username, config.password, true)
        }
        sardine = client
        return client
    }

    // ---------------------------------------------------------------------
    // 路径工具
    // ---------------------------------------------------------------------

    /** 协议 + 主机（含非默认端口），如 https://nas.example.com / http://192.168.1.10:5005 */
    private fun baseUrl(): String {
        val scheme = if (config.useHttps) "https" else "http"
        val host = config.host.trim()
        val defaultPort = if (config.useHttps) 443 else 80
        return if (config.port > 0 && config.port != defaultPort) {
            "$scheme://$host:${config.port}"
        } else {
            "$scheme://$host"
        }
    }

    /** 配置里 basePath 的编码段列表 */
    private fun baseSegments(): List<String> =
        config.basePath.trim('/').split('/').filter { it.isNotBlank() }.map { encodeSegment(it) }

    /**
     * 未编码远程路径 → 可请求的 URL（逐段 percent-encode）。
     */
    private fun encodedUrl(remotePath: String, isDirectory: Boolean = false): String {
        val sb = StringBuilder(baseUrl())
        baseSegments().forEach { sb.append('/').append(it) }
        suffixOf(remotePath).split('/').filter { it.isNotBlank() }.forEach {
            sb.append('/').append(encodeSegment(it))
        }
        if (isDirectory && !sb.endsWith("/")) sb.append('/')
        return sb.toString()
    }

    private fun suffixOf(remotePath: String): String {
        val root = remoteRootPath.trimEnd('/')
        val normalized = remotePath.trim()
        if (normalized == root) return ""
        if (normalized.startsWith("$root/")) return normalized.substring(root.length + 1)
        // 兜底：去掉 scheme://host[:port] 前缀
        val idx = normalized.indexOf("://")
        if (idx >= 0) {
            val afterScheme = normalized.substring(idx + 3)
            val slash = afterScheme.indexOf('/')
            if (slash >= 0) return afterScheme.substring(slash + 1)
        }
        return normalized.trimStart('/')
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    // ---------------------------------------------------------------------
    // 连接测试
    // ---------------------------------------------------------------------

    override suspend fun testConnection(): NasConnectionInfo = withContext(Dispatchers.IO) {
        try {
            val client = ensureSardine()
            // 1) 目标根目录：不存在则创建
            ensureDirectory(remoteRootPath)
            val rootUrl = encodedUrl(remoteRootPath, isDirectory = true)
            if (!client.exists(rootUrl)) {
                throw NasException(
                    NasErrorType.REMOTE_PERMISSION_DENIED,
                    "WebDAV 目标目录不存在且创建失败：$remoteRootPath",
                )
            }

            // 2) 可写探测：PUT 2 字节探针文件 → DELETE 自己创建的探针（不触碰用户文件）
            val probeUrl = encodedUrl(
                RemotePathUtil.join(remoteRootPath, ".nas_migrator_write_test_${System.currentTimeMillis()}.tmp"),
            )
            client.put(probeUrl, WRITE_PROBE_BYTES, "application/octet-stream")
            runCatching { client.delete(probeUrl) }

            // 3) 剩余空间（RFC 4331 quota，服务器不支持时返回 null，不影响结论）
            val freeSpace = runCatching { client.getQuota(rootUrl).quotaAvailableBytes }.getOrNull()

            NasConnectionInfo(
                protocol = NasProtocol.WEBDAV,
                serverDescription = "WebDAV ${baseUrl()}（${if (config.useHttps) "HTTPS" else "HTTP"}）",
                remoteRootPath = remoteRootPath,
                writable = true,
                freeSpaceBytes = freeSpace,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: NasException) {
            throw e
        } catch (e: SardineException) {
            throw mapSardineException(e, "WebDAV 连接测试失败")
        } catch (e: Throwable) {
            throw mapException(e, "WebDAV 连接测试失败")
        }
    }

    // ---------------------------------------------------------------------
    // 远程信息
    // ---------------------------------------------------------------------

    override suspend fun remoteFileInfo(remotePath: String): NasFileInfo? =
        withContext(Dispatchers.IO) {
            try {
                val client = ensureSardine()
                val url = encodedUrl(remotePath)
                if (!client.exists(url)) return@withContext null

                // PROPFIND depth=0：拿文件自身属性（size / isDirectory）
                val resources = client.list(url, 0)
                val resource = resources.firstOrNull { !it.isDirectory } ?: resources.firstOrNull()
                val isDirectory = resource?.isDirectory ?: false
                var size = resource?.contentLength ?: -1L
                if (!isDirectory && size < 0L) {
                    // 部分服务器 PROPFIND 不返回 getcontentlength：
                    // 降级链：HEAD Content-Length → Range GET(bytes=0-0) 解析 Content-Range 总长。
                    size = headContentLength(url) ?: rangeProbeSize(url) ?: -1L
                }
                NasFileInfo(
                    remotePath = remotePath,
                    size = if (isDirectory) -1L else size,
                    isDirectory = isDirectory,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SardineException) {
                if (e.statusCode == 404) return@withContext null
                throw mapSardineException(e, "查询远程文件信息失败：$remotePath")
            } catch (e: Throwable) {
                throw mapException(e, "查询远程文件信息失败：$remotePath")
            }
        }

    /**
     * Range GET（bytes=0-0）探测文件总大小：服务器若支持 Range 会返回 206 + Content-Range: bytes 0-0/TOTAL。
     * 只在 PROPFIND 与 HEAD 都拿不到大小时使用；不支持 Range 则返回 null（不做整文件下载，避免浪费带宽）。
     */
    private fun rangeProbeSize(url: String): Long? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .applyAuth()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 206) {
                response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
            } else {
                null
            }
        }
    }.getOrNull()

    /** HEAD 请求取 Content-Length（PROPFIND 不返回大小时的兜底） */
    private fun headContentLength(url: String): Long? = runCatching {
        val request = Request.Builder()
            .url(url)
            .head()
            .applyAuth()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.header("Content-Length")?.toLongOrNull()
        }
    }.getOrNull()

    override suspend fun ensureDirectory(remotePath: String): Unit = withContext(Dispatchers.IO) {
        try {
            val client = ensureSardine()
            val segments = baseSegments() + suffixOf(remotePath)
                .split('/')
                .filter { it.isNotBlank() }
                .map { encodeSegment(it) }

            var url = baseUrl()
            for (segment in segments) {
                url = "$url/$segment"
                val dirUrl = "$url/"
                try {
                    // MKCOL：已存在时不同服务器分别返回 405 / 301 / 409，均视为「已存在」
                    client.createDirectory(dirUrl)
                } catch (e: SardineException) {
                    when (e.statusCode) {
                        301, 302, 405, 409 -> Unit // 已存在
                        else -> throw mapSardineException(e, "创建 WebDAV 目录失败：$dirUrl")
                    }
                }
            }

            val targetUrl = encodedUrl(remotePath, isDirectory = true)
            if (!client.exists(targetUrl)) {
                throw NasException(
                    NasErrorType.REMOTE_PERMISSION_DENIED,
                    "WebDAV 目录创建后仍不可访问：$remotePath",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NasException) {
            throw e
        } catch (e: SardineException) {
            throw mapSardineException(e, "创建 WebDAV 目录失败：$remotePath")
        } catch (e: Throwable) {
            throw mapException(e, "创建 WebDAV 目录失败：$remotePath")
        }
    }

    // ---------------------------------------------------------------------
    // 上传（OkHttp 流式 PUT + 进度）
    // ---------------------------------------------------------------------

    override suspend fun upload(
        remotePath: String,
        source: UploadSource,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        try {
            val url = encodedUrl(remotePath)
            val total = source.size
            // 实际写入字节数（OkHttp 可能因重试多次调用 writeTo，取最后一次的完整计数）
            val writtenCounter = AtomicLong(0L)
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = contentTypeFor(source.displayName)

                override fun contentLength(): Long = total

                override fun writeTo(sink: BufferedSink) {
                    var written = 0L
                    var lastReported = 0L
                    source.openStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                sink.write(buffer, 0, read)
                                written += read
                                if (written - lastReported >= PROGRESS_STEP_BYTES ||
                                    (total > 0 && written >= total)
                                ) {
                                    lastReported = written
                                    writtenCounter.set(written)
                                    onProgress(written, total)
                                }
                            }
                        }
                    }
                    writtenCounter.set(written)
                    onProgress(written, total)
                }
            }

            val request = Request.Builder()
                .url(url)
                .put(body)
                .applyAuth()
                .header("X-Expected-Entity-Length", total.toString())
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw httpError(
                        code = response.code,
                        url = url,
                        detail = response.body?.string()?.take(500),
                    )
                }
            }
            // 返回实际写入字节数（而不是声明大小），便于引擎日志定位「写入少于预期」的协议问题
            writtenCounter.get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NasException) {
            throw e
        } catch (e: Throwable) {
            // 业务层通过 onProgress 抛出的「暂停/停止」中断异常不是 IO 异常：
            // 必须原样抛出，否则暂停会被错误记成「上传失败」（R3 语义）。
            if (e !is java.io.IOException) throw e
            throw mapException(e, "WebDAV 上传失败：$remotePath")
        }
    }

    // ---------------------------------------------------------------------
    // 空间 / MD5
    // ---------------------------------------------------------------------

    override suspend fun freeSpaceBytes(): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val client = ensureSardine()
            val rootUrl = encodedUrl(remoteRootPath, isDirectory = true)
            // 注意：服务器不支持 RFC4331 quota 时 sardine 返回 Long.MAX_VALUE（不是 null），
            // 直接拿去和文件大小比较会得到荒谬结论，必须视为「未知」。
            client.getQuota(rootUrl).quotaAvailableBytes
                .takeIf { it > 0L && it != Long.MAX_VALUE }
        }.getOrNull()
    }

    /**
     * WebDAV 没有标准 MD5 接口：
     *  - Nextcloud / ownCloud 会在 PROPFIND 中返回 `<nc:getcontentmd5>` 自定义属性，这里尝试读取；
     *  - 其它服务器返回 null，引擎会自动降级为「仅大小校验」并记日志（阶段1决策 3）。
     */
    override suspend fun remoteMd5(remotePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val client = ensureSardine()
            val url = encodedUrl(remotePath)
            // 服务器只会回显「请求过的属性」，因此 Nextcloud(nc:) 与 ownCloud(oc:) 两个命名空间都要请求
            val resources = client.propfind(url, 0, CONTENT_MD5_QNAMES)
            val resource = resources.firstOrNull()
            val value = CONTENT_MD5_QNAMES.firstNotNullOfOrNull { qn ->
                resource?.customPropsNS?.get(qn)
            } ?: resource?.customProps?.get("getcontentmd5")
            value?.trim()?.lowercase()?.takeIf { it.length == 32 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 服务器不支持自定义属性 / 返回 404：降级为 SIZE 校验，不是错误
            null
        }
    }

    // ---------------------------------------------------------------------
    // 异常归一化
    // ---------------------------------------------------------------------

    /** 给请求加上预认证 Basic Auth 头（OkHttp 直连 PUT/HEAD 时使用） */
    private fun Request.Builder.applyAuth(): Request.Builder {
        val username = config.username.trim()
        if (username.isEmpty()) return this
        return header("Authorization", Credentials.basic(username, config.password))
    }

    private fun mapSardineException(e: SardineException, prefix: String): NasException =
        httpError(e.statusCode, prefix, e.responsePhrase ?: e.message)

    private fun httpError(code: Int, url: String, detail: String?): NasException {
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
        val type = when (code) {
            401 -> NasErrorType.AUTH_FAILED
            403 -> NasErrorType.REMOTE_PERMISSION_DENIED
            404 -> NasErrorType.REMOTE_NOT_FOUND
            405 -> NasErrorType.PROTOCOL_UNSUPPORTED
            409 -> NasErrorType.REMOTE_CONFLICT
            423 -> NasErrorType.PROTOCOL_UNSUPPORTED
            507 -> NasErrorType.INSUFFICIENT_SPACE
            in 500..599 -> NasErrorType.CONNECTION_FAILED
            else -> NasErrorType.UNKNOWN
        }
        return NasException(type, "WebDAV HTTP $code（$url）$suffix")
    }

    private fun mapException(e: Throwable, prefix: String): NasException {
        if (e is NasException) return e
        val type = when (e) {
            is java.io.FileNotFoundException -> NasErrorType.LOCAL_READ_FAILED
            is java.net.UnknownHostException -> NasErrorType.HOST_UNREACHABLE
            is java.net.ConnectException -> NasErrorType.CONNECTION_FAILED
            is java.net.SocketTimeoutException -> NasErrorType.TIMEOUT
            is java.io.InterruptedIOException -> NasErrorType.TIMEOUT
            is javax.net.ssl.SSLException -> NasErrorType.CONNECTION_FAILED
            is java.net.SocketException -> NasErrorType.CONNECTION_FAILED
            is java.io.IOException -> NasErrorType.CONNECTION_FAILED
            else -> NasErrorType.UNKNOWN
        }
        return NasException(type, "$prefix（${e.javaClass.simpleName}）：${e.message}", e)
    }

    /** 按扩展名推断 Content-Type（WebDAV 服务端对图片/视频的 Content-Type 有要求） */
    private fun contentTypeFor(fileName: String): MediaType? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "bmp" -> "image/bmp"
            "dng" -> "image/x-adobe-dng"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
        return mime.toMediaTypeOrNull()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024L

        /** Nextcloud / ownCloud 的 MD5 自定义属性（两个命名空间都要请求） */
        val CONTENT_MD5_QNAMES: Set<QName> = setOf(
            QName("http://nextcloud.org/ns", "getcontentmd5"),
            QName("http://owncloud.org/ns", "getcontentmd5"),
        )

        val WRITE_PROBE_BYTES = byteArrayOf(0x4F, 0x4B) // "OK"
    }
}
