package com.nasphoto.migrator.neighbor

import com.nasphoto.migrator.data.neighbor.NeighborConfigFactory
import com.nasphoto.migrator.protocol.NasProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * [NeighborConfigFactory] 的纯 JVM 单元测试（不依赖 Android 运行时）。
 *
 * 覆盖需求 2.4「网络邻居选择结果 → 标准 NasConfig」的字段拼装约定：
 *  - id/protocol=SMB/host/port=445/username/password/useHttps=false 等字段映射；
 *  - 共享名、basePath 的首尾斜杠裁剪；
 *  - 设备名空/空白/null 时 name 回退 IP；
 *  - 匿名（空用户名密码）场景；
 *  - basePath 为空（共享根目录）时 remoteRootPath 形如 `smb://<ip>:445/<share>`。
 */
class NeighborConfigFactoryTest {

    @Test
    fun `create assembles standard SMB config fields`() {
        val config = NeighborConfigFactory.create(
            deviceIp = "192.168.1.10",
            deviceName = "群晖NAS",
            share = "/photo/",
            basePath = "/2024/旅行/",
            username = "user",
            password = "p@ss-word",
            id = "fixed-test-id",
            createdAt = 1_234_567_890L,
        )

        assertEquals("fixed-test-id", config.id)
        assertEquals("群晖NAS", config.name)
        assertEquals(NasProtocol.SMB, config.protocol)
        assertTrue(config.isSmb)
        assertEquals("192.168.1.10", config.host)
        assertEquals(445, config.port)
        assertEquals("user", config.username)
        assertEquals("p@ss-word", config.password)
        // 共享名 / 目录都去掉首尾斜杠
        assertEquals("photo", config.smbShare)
        assertEquals("2024/旅行", config.basePath)
        assertFalse(config.useHttps)
        assertEquals(1_234_567_890L, config.createdAt)
        assertEquals("smb://192.168.1.10:445/photo/2024/旅行", config.remoteRootPath)
    }

    @Test
    fun `blank device name falls back to trimmed ip`() {
        val blankName = NeighborConfigFactory.create(
            deviceIp = " 192.168.1.11 ",
            deviceName = "   ",
            share = "homes",
            basePath = "",
            username = "",
            password = "",
        )
        assertEquals("192.168.1.11", blankName.host)
        assertEquals("192.168.1.11", blankName.name)

        val emptyName = NeighborConfigFactory.create(
            deviceIp = "192.168.1.12",
            deviceName = "",
            share = "homes",
            basePath = "",
            username = "",
            password = "",
        )
        assertEquals("192.168.1.12", emptyName.name)
    }

    @Test
    fun `null device name falls back to ip`() {
        val config = NeighborConfigFactory.create(
            deviceIp = "192.168.1.13",
            deviceName = null,
            share = "share",
            basePath = "",
            username = "u",
            password = "p",
        )
        assertEquals("192.168.1.13", config.name)
        assertEquals(NasProtocol.SMB, config.protocol)
        assertFalse(config.useHttps)
    }

    @Test
    fun `empty base path means share root and remoteRootPath has no trailing slash`() {
        val config = NeighborConfigFactory.create(
            deviceIp = "192.168.1.20",
            deviceName = "DESKTOP-XXX",
            share = "home",
            basePath = "",
            username = "user",
            password = "pw",
        )
        assertEquals("", config.basePath)
        assertEquals("smb://192.168.1.20:445/home", config.remoteRootPath)
    }

    @Test
    fun `slashes on share and base path are trimmed on both ends`() {
        val config = NeighborConfigFactory.create(
            deviceIp = "192.168.1.30",
            deviceName = "NAS",
            share = "///photo///",
            basePath = "////",
            username = "",
            password = "",
        )
        assertEquals("photo", config.smbShare)
        assertEquals("", config.basePath)
        assertEquals("smb://192.168.1.30:445/photo", config.remoteRootPath)
    }

    @Test
    fun `anonymous credentials stay empty`() {
        val config = NeighborConfigFactory.create(
            deviceIp = "192.168.1.40",
            deviceName = null,
            share = "public",
            basePath = "shared",
            username = "  ",
            password = "",
        )
        // 用户名/密码允许为空（匿名共享）；用户名做 trim 后仍为空串
        assertEquals("", config.username)
        assertEquals("", config.password)
        assertEquals(NasProtocol.SMB, config.protocol)
        assertEquals(445, config.port)
        assertEquals("smb://192.168.1.40:445/public/shared", config.remoteRootPath)
    }

    @Test
    fun `default id is generated as unique uuid and createdAt defaults to now`() {
        val first = NeighborConfigFactory.create(
            deviceIp = "192.168.1.50",
            deviceName = "NAS-A",
            share = "s",
            basePath = "",
            username = "",
            password = "",
        )
        val second = NeighborConfigFactory.create(
            deviceIp = "192.168.1.51",
            deviceName = "NAS-B",
            share = "s",
            basePath = "",
            username = "",
            password = "",
        )

        // 不抛 IllegalArgumentException 即说明是合法 UUID
        UUID.fromString(first.id)
        UUID.fromString(second.id)
        assertNotEquals(first.id, second.id)
        assertTrue(first.createdAt > 0L)
        assertTrue(second.createdAt > 0L)
    }
}
