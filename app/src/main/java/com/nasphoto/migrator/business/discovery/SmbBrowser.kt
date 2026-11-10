package com.nasphoto.migrator.business.discovery

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URLEncoder
import java.util.Collections
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 「网络邻居」SMB 只读浏览器（jcifs-ng 2.1.10 实现）。
 *
 * 设计要点：
 *  1) **严格只读**：只用 `list()` / `listFiles()` / `exists()` / 属性查询，
 *     **绝不调用** create / delete / rename / mkdir / 写文件；本类不参与迁移引擎闭环；
 *  2) **每次操作独立 CIFSContext**：jcifs 的上下文持有会话与连接池，长时间复用容易在
 *     页面切换/网络变化后拿到坏会话；这里每次方法调用创建一个、`finally` 必关；
 *     同时用 [activeContexts] 登记在途上下文，页面退出调用 [close] 时兜底关闭；
 *  3) **SMB2/SMB3 优先**：`minVersion=SMB202`、`maxVersion=SMB311`，禁用 SMB1
 *     （现代 NAS 默认关闭 SMB1，回落只会得到难以解释的协商错误）；
 *  4) **路径逐段 percent-encode**：jcifs 的 `SmbFile` 参数是 URL，共享名/目录名里的
 *     中文、空格、加号必须编码，否则 `MalformedURLException` 或连错路径（与
 *     `protocol/SmbNasClient` 的编码规则保持一致）；
 *  5) **设备名**：局域网通常没有反向 DNS（实测 getCanonicalHostName 等于 IP），
 *     因此**优先用 NetBIOS 节点状态查询**（`getNbtAllByAddress`，UDP 137）取
 *     0x00 工作站名 / 0x20 服务器名，失败再退化为反向 DNS，最终拿不到返回 null；
 *  6) **异常归一化**：所有失败转 [SmbBrowseException]（见 [SmbAuthClassifier]），
 *     UI 只按 [SmbFailureKind] 给「人话」提示，不暴露堆栈。
 *
 * 超时基线（任务单：局域网交互要"快"）：
 *  - 连接 3s、响应/读 5s（SMB 协商 + 目录列表足够）；
 *  - NetBIOS 查询 1.2s（UDP 137 不通时不能拖慢扫描列表）；
 *  - 反向 DNS 1.5s（放独立守护线程池 + Future 限时，避免阻塞协程线程池）。
 *
 * 线程：所有 suspend 方法内部 `withContext(Dispatchers.IO)`，绝不阻塞主线程。
 */
class JcifsSmbBrowser : SmbBrowser {

    /**
     * 在途 CIFSContext 登记表（线程安全）。
     * jcifs 的 `CIFSContext.close()` 不是"标记关闭即返回"的廉价操作，正常路径由各方法
     * finally 关闭；这里只用于 [close] 兜底（页面退出时可能有请求还在协商中）。
     */
    private val activeContexts: MutableSet<CIFSContext> =
        Collections.newSetFromMap(ConcurrentHashMap<CIFSContext, Boolean>())

    // =====================================================================
    // SmbBrowser 实现
    // =====================================================================

    /**
     * 匿名/带凭据探测：列共享 + 取设备名。
     *
     * 语义（按任务单 2.1/2.3 与实测）：
     *  - 匿名被拒（`SmbAuthException` / ACCESS_DENIED）→ **不抛异常**，返回 needsAuth=true，
     *    设备名仍尽量带上（NetBIOS 查询不需要认证，UX 更好）；
     *  - 主机不可达 / 445 开放但不是 SMB → 抛 `SmbBrowseException(HOST_UNREACHABLE)`，
     *    UI 据此提示"这台设备不是 SMB 服务/连接超时"，而不是误导用户输密码。
     */
    override suspend fun probe(
        ip: String,
        credentials: SmbCredentials,
    ): SmbProbeResult = withContext(Dispatchers.IO) {
        val host = ip.trim()
        val ctx = createContextOrThrow(credentials, "探测 SMB 主机")

        try {
            val shares = try {
                listSharesInternal(ctx, host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val kind = SmbAuthClassifier.classify(e)
                if (kind == SmbFailureKind.AUTH_REQUIRED || kind == SmbFailureKind.ACCESS_DENIED) {
                    // 端口开放但匿名被拒：这是「需要账号密码」，属于正常探测结论而非错误
                    return@withContext SmbProbeResult(
                        hostName = bestEffortHostName(ctx, host),
                        shares = emptyList(),
                        needsAuth = true,
                    )
                }
                throw browseException(kind, "探测 SMB 主机失败（$host）", e)
            }

            SmbProbeResult(
                hostName = bestEffortHostName(ctx, host),
                shares = shares,
                needsAuth = false,
            )
        } finally {
            closeContext(ctx)
        }
    }

    /**
     * 列举共享：`SmbFile("smb://ip:445/").list()`（jcifs 对 server 根节点做共享枚举）。
     *
     * 注意：jcifs-ng 的共享枚举在 SMB2 下走 MSRPC `srvsvc`（`\PIPE\srvsvc`），
     * 部分 NAS 会要求认证或直接拒绝匿名枚举 → AUTH_REQUIRED / ACCESS_DENIED。
     * 返回的共享名去掉结尾 "/"，并隐藏 `$` 结尾的管理共享（IPC$、C$、ADMIN$ 等）。
     */
    override suspend fun listShares(
        ip: String,
        credentials: SmbCredentials,
    ): List<String> = withContext(Dispatchers.IO) {
        val host = ip.trim()
        val ctx = createContextOrThrow(credentials, "列举 SMB 共享")
        try {
            listSharesInternal(ctx, host)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SmbBrowseException) {
            throw e
        } catch (e: Throwable) {
            throw browseException(
                SmbAuthClassifier.classify(e),
                "无法列出 SMB 共享（$host）",
                e,
            )
        } finally {
            closeContext(ctx)
        }
    }

    /**
     * 列目录（只读）。
     *
     * 目录 URL 必须以 "/" 结尾（jcifs 用结尾斜杠区分"目录"语义）：
     * `smb://ip:445/<share>/<path……>/`，共享名与每一级目录名都逐段编码。
     * 单个目录项读取失败只跳过该项，不影响整个列表（"单条失败跳过"）。
     */
    override suspend fun listDirectory(
        ip: String,
        share: String,
        path: String,
        credentials: SmbCredentials,
    ): List<SmbEntry> = withContext(Dispatchers.IO) {
        val host = ip.trim()
        val shareName = share.trim().trim('/')
        if (shareName.isEmpty()) {
            throw SmbBrowseException(SmbFailureKind.SHARE_NOT_FOUND, "未指定共享名（ip=$host）")
        }
        val relativePath = normalizeRelativePath(path)
        val ctx = createContextOrThrow(credentials, "浏览 SMB 目录")

        try {
            SmbFile(directoryUrl(host, shareName, relativePath), ctx).use { dir ->
                val displayPath = "smb://$host/$shareName" + if (relativePath.isEmpty()) "" else "/$relativePath"
                if (!dir.exists()) {
                    throw SmbBrowseException(SmbFailureKind.SHARE_NOT_FOUND, "共享或目录不存在：$displayPath")
                }
                if (!dir.isDirectory) {
                    throw SmbBrowseException(SmbFailureKind.SHARE_NOT_FOUND, "目标不是目录：$displayPath")
                }

                val files = dir.listFiles() ?: return@withContext emptyList()
                files.asSequence()
                    .mapNotNull { file -> readEntry(file) }
                    // 目录在前、同类按名称不区分大小写排序（与系统文件管理器体验一致）
                    .sortedWith(
                        compareByDescending<SmbEntry> { it.isDirectory }
                            .thenBy { it.name.lowercase() },
                    )
                    .toList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SmbBrowseException) {
            throw e
        } catch (e: Throwable) {
            throw browseException(
                SmbAuthClassifier.classify(e),
                "浏览 SMB 目录失败（smb://$host/$shareName/${relativePath}）",
                e,
            )
        } finally {
            closeContext(ctx)
        }
    }

    /**
     * 尽力获取设备名：NetBIOS 优先，其次反向 DNS；拿不到返回 null（UI 回退显示 IP）。
     * 该方法**只返回 null、不抛网络异常**（CancellationException 除外）。
     */
    override suspend fun hostName(
        ip: String,
        credentials: SmbCredentials,
    ): String? = withContext(Dispatchers.IO) {
        val host = ip.trim()
        val ctx = try {
            createContext(credentials)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // jcifs 上下文都建不出来（极少见）：只做 DNS 兜底，不影响调用方
            return@withContext reverseDnsHostName(host)
        }

        try {
            bestEffortHostName(ctx, host)
        } finally {
            closeContext(ctx)
        }
    }

    /**
     * 兜底释放：关闭所有仍在途的 CIFS 上下文（幂等、不抛异常）。
     * 正常路径每个方法都在 finally 里关闭自己的上下文，这里只处理页面退出时尚未结束的请求。
     */
    override fun close() {
        val snapshot = activeContexts.toList()
        activeContexts.clear()
        snapshot.forEach { ctx -> runCatching { ctx.close() } }
    }

    // =====================================================================
    // CIFS 上下文
    // =====================================================================

    /**
     * 创建带认证的 jcifs 上下文（**只创建对象，不做网络 IO**；登录在第一次操作时发生）。
     *
     * 用户名支持 `DOMAIN\user` 与 `user@domain`：jcifs 的
     * `NtlmPasswordAuthenticator(username, password)` 构造器会自行拆分域名（已 javap 核实）。
     */
    private fun createContext(credentials: SmbCredentials): CIFSContext {
        val props = Properties().apply {
            // SMB2.0.2 ~ SMB3.1.1；不协商 SMB1
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            // 浏览场景要"快"：连接 3s、响应/读 5s，超时即失败让 UI 尽快出结果
            setProperty("jcifs.smb.client.connTimeout", CONNECT_TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.responseTimeout", RESPONSE_TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.soTimeout", SO_TIMEOUT_MS.toString())
            // 家用局域网不需要 DFS 解析，关掉可减少无谓的域查询延时
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            // NetBIOS 节点状态查询（UDP 137）：必须短超时，否则设备名补全会拖慢列表。
            // 注意 jcifs 默认 retryCount=2 × retryTimeout=3000ms，实测不可达 IP 要 6s 才返回，
            // 所以重试次数/重试超时都要压到 1 次 × 1.2s（总 worst case ≈ NBT 1.2s + DNS 1.5s）。
            setProperty("jcifs.netbios.retryCount", "1")
            setProperty("jcifs.netbios.retryTimeout", NETBIOS_SO_TIMEOUT_MS.toString())
            setProperty("jcifs.netbios.soTimeout", NETBIOS_SO_TIMEOUT_MS.toString())
            setProperty("jcifs.netbios.cachePolicy", "0")
        }

        val base = BaseContext(PropertyConfiguration(props))
        val ctx = if (credentials.isAnonymous) {
            // 匿名/访客访问（NAS 开"允许匿名"时）
            base.withAnonymousCredentials()
        } else {
            base.withCredentials(
                NtlmPasswordAuthenticator(credentials.username.trim(), credentials.password),
            )
        }
        activeContexts.add(ctx)
        return ctx
    }

    private fun createContextOrThrow(credentials: SmbCredentials, action: String): CIFSContext =
        try {
            createContext(credentials)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw browseException(SmbAuthClassifier.classify(e), "$action：创建 SMB 连接失败", e)
        }

    private fun closeContext(ctx: CIFSContext) {
        activeContexts.remove(ctx)
        runCatching { ctx.close() }
    }

    // =====================================================================
    // 只读操作实现（同步阻塞，调用点都在 Dispatchers.IO 上）
    // =====================================================================

    /** 列举共享（内部同步实现，调用方负责 ctx 生命周期）。 */
    private fun listSharesInternal(ctx: CIFSContext, ip: String): List<String> {
        SmbFile(serverRootUrl(ip), ctx).use { root ->
            val names = root.list() ?: return emptyList()
            return names.asSequence()
                .map { it.trimEnd('/') }
                .filter { it.isNotBlank() }
                // 隐藏管理共享（IPC$ / C$ / ADMIN$ 等）：普通用户不应在"网络邻居"里看到
                .filterNot { it.endsWith("$") }
                .distinct()
                .sortedBy { it.lowercase() }
                .toList()
        }
    }

    /** 单个目录项读取：任何失败只返回 null（跳过该项），不影响整个目录列表。 */
    private fun readEntry(file: SmbFile): SmbEntry? = try {
        val name = file.name.trimEnd('/')
        if (name.isBlank()) {
            null
        } else {
            val isDirectory = file.isDirectory
            SmbEntry(
                name = name,
                isDirectory = isDirectory,
                sizeBytes = if (isDirectory) -1L else file.length(),
                // lastModified 在部分 NAS 上会失败，失败按 0 处理即可（UI 只做展示）
                lastModified = runCatching { file.lastModified() }.getOrDefault(0L),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    } finally {
        runCatching { file.close() }
    }

    // =====================================================================
    // 设备名：NetBIOS 优先，反向 DNS 兜底
    // =====================================================================

    /** 先 NetBIOS（不需要认证、LAN 内最快），拿不到再反向 DNS；全程不抛异常。 */
    private fun bestEffortHostName(ctx: CIFSContext, ip: String): String? {
        val netbios = try {
            queryNetbiosName(ctx, ip)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        if (!netbios.isNullOrBlank()) return netbios
        return reverseDnsHostName(ip)
    }

    /**
     * NetBIOS 节点状态查询：`ctx.nameServiceClient.getNbtAllByAddress(ip)`。
     *
     * 返回的是该 IP 在 NBT 中注册的所有名字（实测：工作站名 0x00、服务器名 0x20、
     * 信使名 0x03、浏览器服务名 0x1D 以及 WORKGROUP 组名等）。取舍规则：
     *  - 只要 0x00（工作站）/ 0x20（服务器）类型；
     *  - 跳过组地址（WORKGROUP、域组名）、`*`、`__MSBROWSE__`；
     *  - 0x20 优先（更"像"设备名），否则取第一个 0x00。
     */
    private fun queryNetbiosName(ctx: CIFSContext, ip: String): String? {
        val client = ctx.nameServiceClient ?: return null
        // 注意：UDP 137 不通时这里会按 jcifs.netbios.soTimeout 失败（已设为 1.2s）
        val addresses = client.getNbtAllByAddress(ip) ?: return null
        if (addresses.isEmpty()) return null

        val candidates = addresses.filter { addr ->
            val type = addr.nameType
            if (type != TYPE_WORKSTATION && type != TYPE_SERVER) {
                false
            } else {
                val rawName = addr.name.name.trim()
                if (rawName.isEmpty() || rawName == "*") {
                    false
                } else if (rawName.equals(NOISE_WORKGROUP, ignoreCase = true) ||
                    rawName.startsWith(NOISE_MASTER_BROWSER, ignoreCase = true)
                ) {
                    false
                } else {
                    // 组地址不是设备名（节点状态里 WORKGROUP 等组名同样可能是 0x00 类型）
                    !runCatching { addr.isGroupAddress(ctx) }.getOrDefault(false)
                }
            }
        }

        val preferred = candidates.firstOrNull { it.nameType == TYPE_SERVER }
            ?: candidates.firstOrNull()
        return preferred?.name?.name?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 反向 DNS（可选兜底）。用独立守护线程池 + Future 限时：
     * `canonicalHostName` 是阻塞的 DNS 调用（可能数秒），不能让它占住协程的 IO 线程。
     * 拿到的结果等于 IP 或仍是 IP 形式，说明没有 PTR 记录，按"无设备名"处理。
     */
    private fun reverseDnsHostName(ip: String): String? {
        val task = Callable {
            val address = InetAddress.getByName(ip)
            val canonical = address.canonicalHostName?.trim()?.trimEnd('.')
            when {
                canonical.isNullOrEmpty() -> null
                canonical == ip -> null
                // 部分 Android/DNS 组合会返回"反向 IP"而不是域名
                IpRangeUtil.isValidIpv4(canonical) -> null
                else -> canonical
            }
        }
        return try {
            val future = DNS_EXECUTOR.submit(task)
            try {
                future.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                null
            } catch (e: Throwable) {
                future.cancel(true)
                null
            }
        } catch (e: Throwable) {
            null
        }
    }

    // =====================================================================
    // URL / 路径编码
    // =====================================================================

    /** 服务器根 URL（共享枚举用，结尾必须 "/"） */
    private fun serverRootUrl(ip: String): String = "smb://$ip:$SMB_PORT/"

    /**
     * 目录 URL：`smb://ip:445/<share>/<seg1>/<seg2>/`（结尾必带 "/"）。
     * 共享名与目录名逐段 percent-encode（中文/空格/加号），与 `SmbNasClient` 的编码规则一致。
     */
    private fun directoryUrl(ip: String, share: String, normalizedPath: String): String {
        val sb = StringBuilder("smb://").append(ip).append(':').append(SMB_PORT)
        sb.append('/').append(encodeSegment(share))
        normalizedPath.split('/')
            .filter { it.isNotBlank() }
            .forEach { sb.append('/').append(encodeSegment(it)) }
        sb.append('/')
        return sb.toString()
    }

    /** 归一化共享内相对路径：统一斜杠、去掉首尾斜杠、过滤 "." / ".."（只读浏览不应越级） */
    private fun normalizeRelativePath(path: String): String =
        path.trim()
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")

    /** 单段 URL 编码：URLEncoder 会把空格编成 "+"，URL 语义下必须换回 "%20"。 */
    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    // =====================================================================
    // 异常归一化
    // =====================================================================

    private fun browseException(
        kind: SmbFailureKind,
        action: String,
        cause: Throwable?,
    ): SmbBrowseException {
        val detail = cause?.message?.takeIf { it.isNotBlank() }
        val message = if (detail != null) "$action（${kind.name}）：$detail" else "$action（${kind.name}）"
        return SmbBrowseException(kind, message, cause)
    }

    private companion object {
        const val SMB_PORT = 445

        const val CONNECT_TIMEOUT_MS = 3_000
        const val RESPONSE_TIMEOUT_MS = 5_000
        const val SO_TIMEOUT_MS = 5_000

        /** NetBIOS 名称查询（UDP 137）超时：局域网 1.x 秒足够，不通就快速失败 */
        const val NETBIOS_SO_TIMEOUT_MS = 1_200

        /** 反向 DNS 限时（独立线程，避免阻塞 IO 线程池） */
        const val DNS_TIMEOUT_MS = 1_500L

        /** NetBIOS 名字类型：0x00 工作站名 / 0x20 服务器名 */
        const val TYPE_WORKSTATION = 0x00
        const val TYPE_SERVER = 0x20

        const val NOISE_WORKGROUP = "WORKGROUP"
        const val NOISE_MASTER_BROWSER = "__MSBROWSE__"

        /** 反向 DNS 专用守护线程池（懒加载，页面生命周期内复用；线程空闲自动回收） */
        val DNS_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "neighbor-reverse-dns").apply { isDaemon = true }
        }
    }
}
