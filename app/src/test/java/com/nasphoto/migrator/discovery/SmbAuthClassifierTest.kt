package com.nasphoto.migrator.discovery

import com.nasphoto.migrator.business.discovery.SmbAuthClassifier
import com.nasphoto.migrator.business.discovery.SmbBrowseException
import com.nasphoto.migrator.business.discovery.SmbFailureKind
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * SMB 失败分类器纯 JVM 单元测试（验收标准 7：认证异常识别）。
 *
 * 覆盖两类输入：
 *  1) NT Status 精确映射（0xC000006D / 0xC0000022 / 0xC00000CC / 0xC0000034 / 0xC00000B5 / 未知）；
 *  2) 消息关键字兜底（jcifs 的连接类错误往往只有一个通用状态码 + 英文消息）。
 *
 * 这些分类直接决定 UI 提示是「账号或密码不对」还是「设备不可达」，分类错了会
 * 让用户反复输密码却永远连不上，所以每个分支都要有测试锁定。
 */
class SmbAuthClassifierTest {

    // NT Status（有符号 int，与 jcifs.smb.NtStatus 一致）
    private val statusLogonFailure = 0xC000006D.toInt()
    private val statusWrongPassword = 0xC000006A.toInt()
    private val statusAccountDisabled = 0xC0000072.toInt()
    private val statusAccessDenied = 0xC0000022.toInt()
    private val statusBadNetworkName = 0xC00000CC.toInt()
    private val statusObjectNameNotFound = 0xC0000034.toInt()
    private val statusObjectPathNotFound = 0xC000003A.toInt()
    private val statusIoTimeout = 0xC00000B5.toInt()
    private val statusUnsuccessful = 0xC0000001.toInt()

    // ------------------------------------------------------------------
    // ① NT Status 精确映射
    // ------------------------------------------------------------------

    @Test
    fun `登录失败与密码错误映射为需要认证`() {
        assertEquals(
            SmbFailureKind.AUTH_REQUIRED,
            SmbAuthClassifier.classifyNtStatus(statusLogonFailure, null),
        )
        assertEquals(
            SmbFailureKind.AUTH_REQUIRED,
            SmbAuthClassifier.classifyNtStatus(statusWrongPassword, null),
        )
        assertEquals(
            SmbFailureKind.AUTH_REQUIRED,
            SmbAuthClassifier.classifyNtStatus(statusAccountDisabled, null),
        )
    }

    @Test
    fun `访问被拒绝映射为无权限`() {
        assertEquals(
            SmbFailureKind.ACCESS_DENIED,
            SmbAuthClassifier.classifyNtStatus(statusAccessDenied, null),
        )
    }

    @Test
    fun `网络名与对象不存在映射为共享不存在`() {
        assertEquals(
            SmbFailureKind.SHARE_NOT_FOUND,
            SmbAuthClassifier.classifyNtStatus(statusBadNetworkName, null),
        )
        assertEquals(
            SmbFailureKind.SHARE_NOT_FOUND,
            SmbAuthClassifier.classifyNtStatus(statusObjectNameNotFound, null),
        )
        assertEquals(
            SmbFailureKind.SHARE_NOT_FOUND,
            SmbAuthClassifier.classifyNtStatus(statusObjectPathNotFound, null),
        )
    }

    @Test
    fun `IO 超时状态映射为超时`() {
        assertEquals(
            SmbFailureKind.TIMEOUT,
            SmbAuthClassifier.classifyNtStatus(statusIoTimeout, null),
        )
    }

    @Test
    fun `未知状态码且无消息映射为未知`() {
        assertEquals(
            SmbFailureKind.UNKNOWN,
            SmbAuthClassifier.classifyNtStatus(0x12345678, null),
        )
        assertEquals(
            SmbFailureKind.UNKNOWN,
            SmbAuthClassifier.classifyNtStatus(statusUnsuccessful, null),
        )
    }

    // ------------------------------------------------------------------
    // ② 消息关键字兜底（状态码无法区分时）
    // ------------------------------------------------------------------

    @Test
    fun `消息兜底_Logon_failure`() {
        assertEquals(
            SmbFailureKind.AUTH_REQUIRED,
            SmbAuthClassifier.classifyNtStatus(
                statusUnsuccessful,
                "Logon failure: unknown user name or bad password.",
            ),
        )
    }

    @Test
    fun `消息兜底_Access_denied`() {
        assertEquals(
            SmbFailureKind.ACCESS_DENIED,
            SmbAuthClassifier.classifyNtStatus(
                statusUnsuccessful,
                "Access is denied.",
            ),
        )
    }

    @Test
    fun `消息兜底_network_name`() {
        assertEquals(
            SmbFailureKind.SHARE_NOT_FOUND,
            SmbAuthClassifier.classifyNtStatus(
                statusUnsuccessful,
                "The network name cannot be found.",
            ),
        )
    }

    @Test
    fun `消息兜底_timeout`() {
        assertEquals(
            SmbFailureKind.TIMEOUT,
            SmbAuthClassifier.classifyNtStatus(
                statusUnsuccessful,
                "The operation timed out.",
            ),
        )
    }

    @Test
    fun `消息兜底_failed_to_connect_映射为主机不可达`() {
        // 实测：192.168.1.1（445 开放但非 SMB 服务）jcifs 只给 "Failed to connect: ..."
        assertEquals(
            SmbFailureKind.HOST_UNREACHABLE,
            SmbAuthClassifier.classifyNtStatus(
                statusUnsuccessful,
                "Failed to connect: 0.0.0.0 <00>/192.168.1.1",
            ),
        )
    }

    // ------------------------------------------------------------------
    // ③ Throwable 类型映射
    // ------------------------------------------------------------------

    @Test
    fun `SmbAuthException 类型优先于 NT Status`() {
        // SmbAuthException 是 SmbException 的子类：即使状态码是 ACCESS_DENIED，
        // 也必须先按类型判为需要认证（否则 UI 会提示"无权限"而不是"输密码"）。
        // 说明：jcifs 的 SmbAuthException 构造器是包级私有（javap -p 核实），
        // 单元测试在另一个包，只能用反射构造一个真实实例。
        val exception = newSmbAuthException(statusAccessDenied)
        assertEquals(
            SmbFailureKind.AUTH_REQUIRED,
            SmbAuthClassifier.classify(exception),
        )
    }

    /** 反射构造 jcifs 包内私有的 SmbAuthException（仅测试用） */
    private fun newSmbAuthException(ntStatus: Int): SmbAuthException {
        val constructor = SmbAuthException::class.java
            .getDeclaredConstructor(Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(ntStatus)
    }

    @Test
    fun `SmbException 按 NT Status 分类`() {
        assertEquals(
            SmbFailureKind.AUTH_REQUIRED,
            SmbAuthClassifier.classify(SmbException(statusLogonFailure, true)),
        )
        assertEquals(
            SmbFailureKind.ACCESS_DENIED,
            SmbAuthClassifier.classify(SmbException(statusAccessDenied, true)),
        )
        assertEquals(
            SmbFailureKind.SHARE_NOT_FOUND,
            SmbAuthClassifier.classify(SmbException(statusBadNetworkName, true)),
        )
        assertEquals(
            SmbFailureKind.TIMEOUT,
            SmbAuthClassifier.classify(SmbException(statusIoTimeout, true)),
        )
    }

    @Test
    fun `JDK 网络异常映射`() {
        assertEquals(
            SmbFailureKind.TIMEOUT,
            SmbAuthClassifier.classify(SocketTimeoutException("connect timed out")),
        )
        assertEquals(
            SmbFailureKind.HOST_UNREACHABLE,
            SmbAuthClassifier.classify(ConnectException("Connection refused")),
        )
        assertEquals(
            SmbFailureKind.HOST_UNREACHABLE,
            SmbAuthClassifier.classify(UnknownHostException("192.168.1.99")),
        )
    }

    @Test
    fun `IO 异常按消息兜底`() {
        assertEquals(
            SmbFailureKind.HOST_UNREACHABLE,
            SmbAuthClassifier.classify(IOException("Failed to connect: 192.168.1.1")),
        )
        assertEquals(
            SmbFailureKind.AUTH_REQUIRED,
            SmbAuthClassifier.classify(RuntimeException("Logon failure")),
        )
        assertEquals(
            SmbFailureKind.UNKNOWN,
            SmbAuthClassifier.classify(RuntimeException("something odd")),
        )
    }

    @Test
    fun `SmbBrowseException 保持已有分类`() {
        assertEquals(
            SmbFailureKind.SHARE_NOT_FOUND,
            SmbAuthClassifier.classify(
                SmbBrowseException(SmbFailureKind.SHARE_NOT_FOUND, "共享不存在"),
            ),
        )
    }
}
