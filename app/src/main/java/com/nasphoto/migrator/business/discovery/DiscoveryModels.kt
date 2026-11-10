package com.nasphoto.migrator.business.discovery

import kotlinx.coroutines.flow.Flow

/**
 * 网络邻居（局域网 SMB 主机发现 / 浏览）—— 领域模型与接口契约。
 *
 * 本文件是「网络邻居向导」增量功能的公共契约，**不含任何 Android 实现**，
 * 便于实现方（SubnetScanner / JcifsSmbBrowser）与消费方（ui/neighbor）并行开发，
 * 也便于纯 JVM 单元测试（网段计算 / 失败分类）。
 *
 * 铁则约束：本功能只做**只读**的局域网发现与目录浏览，不参与迁移引擎的
 * 「上传 → 校验 → 删除本地」闭环，`business/engine/` 目录一行不改。
 */

// ============================================================================
// 网段与扫描
// ============================================================================

/**
 * 待扫描的 IPv4 网段（起止地址均可由用户手动编辑，用于跨网段/固定网段场景）。
 *
 * 自动识别时为 /24 的可用主机范围（如 `192.168.1.1` ~ `192.168.1.254`）。
 */
data class IpRange(
    val startIp: String,
    val endIp: String,
) {
    /** 展示用，如「192.168.1.1 - 192.168.1.254」 */
    val display: String get() = "$startIp - $endIp"
}

/**
 * 扫描事件流（冷流：collect 时开始扫描；取消 collect 会真正中断未完成的 Socket 探测）。
 */
sealed interface ScanEvent {

    /** 开始扫描（total = 实际要探测的地址数，已排除本机 IP） */
    data class Started(val range: IpRange, val total: Int) : ScanEvent

    /** 进度（scanned 已完成数 / total 总数；openCount 当前已发现 445 开放主机数） */
    data class Progress(val scanned: Int, val total: Int, val openCount: Int) : ScanEvent

    /** 发现一台 445 端口开放的 SMB 主机（先展示 IP，设备名随后异步补全） */
    data class HostFound(val host: SmbHost) : ScanEvent

    /** 扫描结束（超时截断也算结束；hosts 为本次发现的主机） */
    data class Finished(
        val scanned: Int,
        val total: Int,
        val hosts: List<SmbHost>,
        val elapsedMs: Long,
    ) : ScanEvent

    /** 无法开始扫描（如未连接 WiFi、网段无效） */
    data class Failed(val message: String) : ScanEvent
}

/**
 * 网络邻居列表中的一台 SMB 主机。
 *
 * [fromSaved] = true 表示来自「我的设备」（上次扫描到或连接过），先展示、不等扫描。
 */
data class SmbHost(
    val ip: String,
    val hostName: String? = null,
    /** 匿名可列举到的共享数量（拿不到为 null） */
    val shareCount: Int? = null,
    /** 445 开放但匿名访问被拒（提示「需要账号密码」） */
    val needsAuth: Boolean = false,
    val fromSaved: Boolean = false,
    val lastUsedAt: Long = 0L,
) {
    /** 列表展示名：设备名优先，否则 IP */
    val displayName: String
        get() = hostName?.trim()?.takeIf { it.isNotEmpty() } ?: ip
}

/** 远程目录项（文件只展示，不进入） */
data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = -1L,
    val lastModified: Long = 0L,
)

/** SMB 凭据（内存态）。落盘由 `data/neighbor` 的加密仓库负责，禁止明文/日志。 */
data class SmbCredentials(
    val username: String,
    val password: String,
) {
    val isAnonymous: Boolean get() = username.isBlank()

    companion object {
        /** 匿名访问（NAS 开启访客/无需密码时） */
        val ANONYMOUS = SmbCredentials(username = "", password = "")
    }
}

/** 匿名探测结果 */
data class SmbProbeResult(
    val hostName: String?,
    /** 匿名可见的共享名列表（可能为空） */
    val shares: List<String>,
    /** true = 匿名被拒，需要账号密码 */
    val needsAuth: Boolean,
)

/**
 * 浏览失败分类（UI 负责翻译成「人话」，禁止把异常堆栈丢给用户）。
 */
enum class SmbFailureKind {
    /** 需要账号密码（SmbAuthException / NT_STATUS_LOGON_FAILURE） */
    AUTH_REQUIRED,

    /** 已登录但无权限（访问共享被拒） */
    ACCESS_DENIED,

    /** 主机不可达 / 445 拒绝连接 */
    HOST_UNREACHABLE,

    /** 共享或目录不存在 */
    SHARE_NOT_FOUND,

    /** 超时 */
    TIMEOUT,

    /** 其它（协议不支持、未知） */
    UNKNOWN,
}

/** 浏览层统一异常（由 SmbBrowser 实现抛出，UI 按 [kind] 提示） */
class SmbBrowseException(
    val kind: SmbFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

// ============================================================================
// 接口
// ============================================================================

/**
 * 本网段 SMB 主机扫描器（/24 全扫描版）。
 *
 * 实现要求（任务单 2.1）：
 *  - 网段自动识别：从当前 WiFi 的 `LinkProperties` 读本机 IPv4，推导 /24；
 *  - 对每个 IP 做 **TCP 445 端口探测**（Socket connect）；
 *  - 并发 32、单 IP 连接超时 800ms、整体上限 15 秒；
 *  - 实时候选进度，取消必须真正中断未完成探测（关闭 Socket）；
 *  - 仅 WiFi 环境允许扫描。
 */
interface NeighborScanner {

    /** 手机当前是否连接 WiFi（移动网络下不允许扫描） */
    fun isWifiConnected(): Boolean

    /** 从当前 WiFi 推导本机所在 /24 网段；无法推导（无 WiFi / 非 IPv4）时返回 null */
    fun detectLocalRange(): IpRange?

    /** 本机在当前 WiFi 下的 IPv4 地址列表（用于从扫描列表中排除本机） */
    fun localIpv4Addresses(): Set<String>

    /**
     * 扫描指定网段。
     *
     * @param concurrency 并发探测数（默认 32）
     * @param connectTimeoutMs 单 IP 连接超时（默认 800ms）
     * @param totalTimeoutMs 整轮扫描上限（默认 15s，到点后不再派发新探测，返回已发现结果）
     */
    fun scan(
        range: IpRange,
        concurrency: Int = DEFAULT_CONCURRENCY,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    ): Flow<ScanEvent>

    companion object {
        const val SMB_PORT = 445
        const val DEFAULT_CONCURRENCY = 32
        const val DEFAULT_CONNECT_TIMEOUT_MS = 800
        const val DEFAULT_TOTAL_TIMEOUT_MS = 15_000L
    }
}

/**
 * SMB 只读浏览接口（jcifs-ng 实现）。
 *
 * 严格只读：只允许 list / probe，**禁止**任何上传、删除、重命名、建目录操作。
 * 所有方法必须在 IO 线程执行（实现内部 `withContext(Dispatchers.IO)`），失败抛 [SmbBrowseException]。
 */
interface SmbBrowser {

    /** 匿名（或带凭据）探测：设备名 + 共享列表 + 是否需要认证 */
    suspend fun probe(
        ip: String,
        credentials: SmbCredentials = SmbCredentials.ANONYMOUS,
    ): SmbProbeResult

    /** 列举共享文件夹（`smb://ip:445/`）。需要认证且未提供时抛 AUTH_REQUIRED */
    suspend fun listShares(
        ip: String,
        credentials: SmbCredentials = SmbCredentials.ANONYMOUS,
    ): List<String>

    /**
     * 列目录。
     *
     * @param path 共享内相对路径；"" 或 "/" 表示共享根目录；支持多级（如 "DCIM/Camera"）
     */
    suspend fun listDirectory(
        ip: String,
        share: String,
        path: String = "",
        credentials: SmbCredentials = SmbCredentials.ANONYMOUS,
    ): List<SmbEntry>

    /** 尝试获取设备名（NetBIOS/DNS 名）；拿不到返回 null（UI 回退显示 IP） */
    suspend fun hostName(
        ip: String,
        credentials: SmbCredentials = SmbCredentials.ANONYMOUS,
    ): String?

    /** 释放 jcifs 上下文/连接（幂等，不抛异常） */
    fun close()
}
