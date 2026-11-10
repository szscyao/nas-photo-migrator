package com.nasphoto.migrator.discovery

import com.nasphoto.migrator.business.discovery.IpRange
import com.nasphoto.migrator.business.discovery.IpRangeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网段计算与扫描地址生成的纯 JVM 单元测试（验收标准 7：网段计算 / IP 列表生成）。
 *
 * 这些逻辑直接决定「本网段全扫描」会不会扫错网段、会不会把自己的 IP 也扫进去，
 * 因此必须与 Android 运行时解耦、可重复验证。
 */
class IpRangeUtilTest {

    // ---------------- IPv4 解析 / 格式化 ----------------

    @Test
    fun `解析合法 IPv4`() {
        assertEquals(0xC0A80101.toInt(), IpRangeUtil.parseIpv4("192.168.1.1"))
        assertEquals(0, IpRangeUtil.parseIpv4("0.0.0.0"))
        assertEquals(-1, IpRangeUtil.parseIpv4("255.255.255.255"))
    }

    @Test
    fun `拒绝非法 IPv4`() {
        assertNull(IpRangeUtil.parseIpv4(null))
        assertNull(IpRangeUtil.parseIpv4(""))
        assertNull(IpRangeUtil.parseIpv4("192.168.1"))
        assertNull(IpRangeUtil.parseIpv4("192.168.1.1.1"))
        assertNull(IpRangeUtil.parseIpv4("192.168.1.256"))
        assertNull(IpRangeUtil.parseIpv4("192.168.01.1"))
        assertNull(IpRangeUtil.parseIpv4("192.168.-1.1"))
        assertNull(IpRangeUtil.parseIpv4("abc"))
        assertFalse(IpRangeUtil.isValidIpv4("192.168.1.999"))
    }

    @Test
    fun `格式化与解析互逆`() {
        val samples = listOf("0.0.0.0", "10.0.0.1", "192.168.1.48", "255.255.255.254", "8.8.8.8")
        for (ip in samples) {
            val parsed = IpRangeUtil.parseIpv4(ip)!!
            assertEquals(ip, IpRangeUtil.formatIpv4(parsed))
        }
    }

    // ---------------- /24 推导 ----------------

    @Test
    fun `由本机 IP 推导 24 网段可用主机范围`() {
        val range = IpRangeUtil.fromLocalCidr24("192.168.1.48")
        assertNotNull(range)
        assertEquals("192.168.1.1", range!!.startIp)
        assertEquals("192.168.1.254", range.endIp)
        assertEquals(254, IpRangeUtil.count(range))

        val range2 = IpRangeUtil.fromLocalCidr24("10.0.5.200")
        assertEquals("10.0.5.1", range2!!.startIp)
        assertEquals("10.0.5.254", range2.endIp)
    }

    @Test
    fun `非法本机 IP 无法推导网段`() {
        assertNull(IpRangeUtil.fromLocalCidr24(null))
        assertNull(IpRangeUtil.fromLocalCidr24("not-an-ip"))
    }

    // ---------------- 扫描地址列表生成 ----------------

    @Test
    fun `生成扫描列表时排除本机 IP`() {
        val range = IpRangeUtil.fromLocalCidr24("192.168.1.48")!!
        val targets = IpRangeUtil.expand(range, excludeIps = setOf("192.168.1.48"))
        assertEquals(253, targets.size)
        assertFalse(targets.contains("192.168.1.48"))
        assertTrue(targets.contains("192.168.1.1"))
        assertTrue(targets.contains("192.168.1.254"))
        // 顺序必须连续递增（扫描进度与 UI 展示依赖顺序）
        assertEquals("192.168.1.1", targets.first())
        assertEquals("192.168.1.254", targets.last())
    }

    @Test
    fun `多个本机地址全部排除`() {
        val range = IpRange("192.168.1.1", "192.168.1.10")
        val targets = IpRangeUtil.expand(range, excludeIps = setOf("192.168.1.2", "192.168.1.5"))
        assertEquals(listOf("192.168.1.1", "192.168.1.3", "192.168.1.4", "192.168.1.6",
            "192.168.1.7", "192.168.1.8", "192.168.1.9", "192.168.1.10"), targets)
    }

    @Test
    fun `地址数量上限保护`() {
        val huge = IpRange("10.0.0.1", "10.255.255.254")
        val targets = IpRangeUtil.expand(huge, maxCount = 1024)
        assertEquals(1024, targets.size)
        assertEquals("10.0.0.1", targets.first())
    }

    @Test
    fun `起始大于结束时返回空列表`() {
        val targets = IpRangeUtil.expand(IpRange("192.168.1.254", "192.168.1.1"))
        assertTrue(targets.isEmpty())
    }

    // ---------------- 手动网段校验 ----------------

    @Test
    fun `手动网段合法时返回 null`() {
        assertNull(IpRangeUtil.validateManualRange("192.168.1.1", "192.168.1.254"))
        assertNull(IpRangeUtil.validateManualRange("192.168.1.100", "192.168.1.100"))
    }

    @Test
    fun `手动网段非法时返回人话错误`() {
        assertNotNull(IpRangeUtil.validateManualRange("192.168.1", "192.168.1.254"))
        assertNotNull(IpRangeUtil.validateManualRange("192.168.1.1", "192.168.1.999"))
        assertEquals(
            "起始 IP 不能大于结束 IP",
            IpRangeUtil.validateManualRange("192.168.1.200", "192.168.1.10"),
        )
        assertEquals(
            "扫描范围过大（最多 1024 个地址），请缩小网段",
            IpRangeUtil.validateManualRange("10.0.0.1", "10.255.255.254"),
        )
    }
}
