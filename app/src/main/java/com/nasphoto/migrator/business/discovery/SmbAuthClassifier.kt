package com.nasphoto.migrator.business.discovery

import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * SMB 异常 → [SmbFailureKind] 的**纯函数分类器**（不联网、不持有状态，便于纯 JVM 单测）。
 *
 * 为什么需要它：
 *  - jcifs-ng 把 SMB 协议错误统一包装成 [SmbException]，真正的语义藏在 `getNtStatus()` 里；
 *    UI 不能用异常类型/堆栈给用户提示，必须落成有限枚举（[SmbFailureKind]）；
 *  - 实测数据（Lead 在本机局域网 192.168.1.0/24 用 jcifs-ng 2.1.10）：
 *      * 192.168.1.37 / .111 匿名列共享 → `SmbAuthException: Logon failure: unknown user name
 *        or bad password.` → 必须是 AUTH_REQUIRED（probe 返回 needsAuth=true，不抛异常）；
 *      * 192.168.1.1 的 445 开放但不是 SMB 服务 → `SmbException: Failed to connect: ...`
 *        → HOST_UNREACHABLE（不能当成「需要密码」，否则 UI 会误导用户）；
 *      * 反向 DNS 在局域网通常无 PTR 记录，所以设备名不能只依赖 DNS（见 JcifsSmbBrowser）。
 *
 * 分类优先级（有意设计）：
 *  1) 异常**类型**优先（[SmbAuthException] 是 [SmbException] 的子类，必须先判类型，
 *     否则 NT Status 会把登录失败误判成「无权限」）；
 *  2) [SmbException] 按 NT Status 精确映射；
 *  3) 非 SMB 异常按 JDK 异常类型（超时/主机不可达/连接拒绝）；
 *  4) 最后才用**消息关键字兜底**（jcifs 有些连接类错误只给出 `Failed to connect: ...` 文案，
 *     NT Status 是通用值，单靠状态码无法区分「需要密码」与「主机不是 SMB」）。
 */
object SmbAuthClassifier {

    // ---------------------------------------------------------------------
    // 常用 NT Status 常量（值 = 有符号 32 位；与 jcifs.smb.NtStatus 一致）
    // ---------------------------------------------------------------------

    /** STATUS_LOGON_FAILURE：用户名或密码错误（最常见） */
    private val NT_STATUS_LOGON_FAILURE = 0xC000006D.toInt()

    /** STATUS_WRONG_PASSWORD：密码错误 */
    private val NT_STATUS_WRONG_PASSWORD = 0xC000006A.toInt()

    /** STATUS_PASSWORD_EXPIRED：密码已过期 */
    private val NT_STATUS_PASSWORD_EXPIRED = 0xC0000071.toInt()

    /** STATUS_ACCOUNT_DISABLED：账号被禁用 */
    private val NT_STATUS_ACCOUNT_DISABLED = 0xC0000072.toInt()

    /** STATUS_ACCOUNT_LOCKED_OUT：账号被锁定 */
    private val NT_STATUS_ACCOUNT_LOCKED_OUT = 0xC0000234.toInt()

    /** STATUS_ACCESS_DENIED：已认证但无权限 */
    private val NT_STATUS_ACCESS_DENIED = 0xC0000022.toInt()

    /** STATUS_NETWORK_ACCESS_DENIED：网络登录被拒 */
    private val NT_STATUS_NETWORK_ACCESS_DENIED = 0xC00000CA.toInt()

    /** STATUS_LOGON_TYPE_NOT_GRANTED：未授予该登录类型（账号/策略层面被拒） */
    private val NT_STATUS_LOGON_TYPE_NOT_GRANTED = 0xC000015B.toInt()

    /** STATUS_BAD_NETWORK_NAME：共享名不存在（smb://ip/不存在的共享） */
    private val NT_STATUS_BAD_NETWORK_NAME = 0xC00000CC.toInt()

    /** STATUS_OBJECT_NAME_NOT_FOUND：对象不存在 */
    private val NT_STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034.toInt()

    /** STATUS_OBJECT_PATH_NOT_FOUND：目录路径不存在 */
    private val NT_STATUS_OBJECT_PATH_NOT_FOUND = 0xC000003A.toInt()

    /** STATUS_NETWORK_PATH_NOT_FOUND：网络路径不存在 */
    private val NT_STATUS_NETWORK_PATH_NOT_FOUND = 0xC000003B.toInt()

    /** STATUS_NO_SUCH_FILE：文件不存在 */
    private val NT_STATUS_NO_SUCH_FILE = 0xC000000F.toInt()

    /** STATUS_NETWORK_NAME_DELETED：共享已被删除 */
    private val NT_STATUS_NETWORK_NAME_DELETED = 0xC00000C9.toInt()

    /** STATUS_IO_TIMEOUT：I/O 超时（task-5 指定的 0xC00000B5） */
    private val NT_STATUS_IO_TIMEOUT = 0xC00000B5.toInt()

    /** STATUS_HOST_UNREACHABLE：主机不可达 */
    private val NT_STATUS_HOST_UNREACHABLE = 0xC000023C.toInt()

    /** STATUS_NETWORK_UNREACHABLE：网络不可达 */
    private val NT_STATUS_NETWORK_UNREACHABLE = 0xC000023D.toInt()

    /** STATUS_CONNECTION_REFUSED：445 端口拒绝连接 */
    private val NT_STATUS_CONNECTION_REFUSED = 0xC0000236.toInt()

    // ---------------------------------------------------------------------
    // 对外分类入口
    // ---------------------------------------------------------------------

    /**
     * 把任意异常归一化为 UI 可展示的失败分类。
     *
     * 注意：传入 [SmbBrowseException] 时原样返回其 kind（实现类内部已经分类过的异常，
     * 再分类一次不应该改变语义）。
     */
    fun classify(t: Throwable): SmbFailureKind = when {
        // 已经是浏览层异常：保留原分类
        t is SmbBrowseException -> t.kind
        // 认证异常是 SmbException 的子类，必须先判类型（否则会被 NT Status 覆盖）
        t is SmbAuthException -> SmbFailureKind.AUTH_REQUIRED
        t is SmbException -> classifyNtStatus(t.ntStatus, t.message)
        // JDK 网络异常：注意 SocketTimeoutException / ConnectException 的继承关系，顺序不能乱
        t is SocketTimeoutException -> SmbFailureKind.TIMEOUT
        t is InterruptedIOException -> SmbFailureKind.TIMEOUT
        t is UnknownHostException -> SmbFailureKind.HOST_UNREACHABLE
        t is NoRouteToHostException -> SmbFailureKind.HOST_UNREACHABLE
        t is ConnectException -> SmbFailureKind.HOST_UNREACHABLE
        t is SocketException -> SmbFailureKind.HOST_UNREACHABLE
        // 其余 IO 异常（如 jcifs.CIFSException 包装的协商失败）只能靠消息兜底
        t is IOException -> classifyByMessage(t.message)
        else -> classifyByMessage(t.message)
    }

    /**
     * 按 NT Status + 消息兜底进行分类。
     *
     * @param ntStatus [SmbException.getNtStatus] 的返回值（有符号 int）
     * @param message  异常消息，可为 null；仅在状态码无法精确判定时用于关键字兜底
     */
    fun classifyNtStatus(ntStatus: Int, message: String?): SmbFailureKind = when (ntStatus) {
        NT_STATUS_LOGON_FAILURE,
        NT_STATUS_WRONG_PASSWORD,
        NT_STATUS_PASSWORD_EXPIRED,
        NT_STATUS_ACCOUNT_DISABLED,
        NT_STATUS_ACCOUNT_LOCKED_OUT,
        -> SmbFailureKind.AUTH_REQUIRED

        NT_STATUS_ACCESS_DENIED,
        NT_STATUS_NETWORK_ACCESS_DENIED,
        NT_STATUS_LOGON_TYPE_NOT_GRANTED,
        -> SmbFailureKind.ACCESS_DENIED

        NT_STATUS_BAD_NETWORK_NAME,
        NT_STATUS_OBJECT_NAME_NOT_FOUND,
        NT_STATUS_OBJECT_PATH_NOT_FOUND,
        NT_STATUS_NETWORK_PATH_NOT_FOUND,
        NT_STATUS_NO_SUCH_FILE,
        NT_STATUS_NETWORK_NAME_DELETED,
        -> SmbFailureKind.SHARE_NOT_FOUND

        NT_STATUS_IO_TIMEOUT -> SmbFailureKind.TIMEOUT

        NT_STATUS_HOST_UNREACHABLE,
        NT_STATUS_NETWORK_UNREACHABLE,
        NT_STATUS_CONNECTION_REFUSED,
        -> SmbFailureKind.HOST_UNREACHABLE

        // 其余状态码（含 STATUS_UNSUCCESSFUL 等通用值）：消息关键字兜底，仍无法判断 → UNKNOWN
        else -> classifyByMessage(message)
    }

    // ---------------------------------------------------------------------
    // 消息关键字兜底
    // ---------------------------------------------------------------------

    /**
     * jcifs 的英文提示词（小写匹配）。顺序有意：认证 → 权限 → 超时 → 主机不可达 → 共享不存在。
     *
     * 「failed to connect」必须排在「not found」之前：实测 192.168.1.1（445 开放但非 SMB）
     * 只给 `Failed to connect: ...`，若先匹配 not found 会误判成共享不存在。
     */
    private fun classifyByMessage(message: String?): SmbFailureKind {
        val text = message?.lowercase() ?: return SmbFailureKind.UNKNOWN
        if (text.isBlank()) return SmbFailureKind.UNKNOWN

        fun hasAny(vararg keys: String): Boolean = keys.any { it in text }

        return when {
            hasAny(
                "logon failure", "logon_failure", "unknown user name", "bad password",
                "wrong password", "authentication failed", "authentication error",
                "logon denied", "invalid credentials", "not logged on",
                "账号", "密码", "用户名", "需要认证", "需要账号",
            ) -> SmbFailureKind.AUTH_REQUIRED

            hasAny(
                "access denied", "access_denied", "access is denied", "permission denied",
                "拒绝访问", "无权限", "没有权限",
            ) -> SmbFailureKind.ACCESS_DENIED

            hasAny("timed out", "timeout", "time out", "超时") -> SmbFailureKind.TIMEOUT

            hasAny(
                "failed to connect", "connection refused", "connection reset",
                "host is down", "host unreachable", "network is unreachable",
                "no route to host", "unreachable", "refused",
                "无法连接", "连接失败", "连接被拒绝", "不可达",
            ) -> SmbFailureKind.HOST_UNREACHABLE

            hasAny(
                "bad_network_name", "network name", "network path", "path not found",
                "not found", "does not exist", "不存在", "找不到",
            ) -> SmbFailureKind.SHARE_NOT_FOUND

            else -> SmbFailureKind.UNKNOWN
        }
    }
}
