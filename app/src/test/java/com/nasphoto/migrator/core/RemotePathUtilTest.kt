package com.nasphoto.migrator.core

import com.nasphoto.migrator.protocol.RemotePathUtil
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远程路径拼接的纯 JVM 单元测试。
 *
 * 目标路径格式必须与协议层预期一致：
 *   SMB:    smb://192.168.1.10:445/home/PhotoBackup/DCIM/Camera/IMG_0001.jpg
 *   WebDAV: https://nas.example.com:5006/dav/PhotoBackup/DCIM/Camera/IMG_0001.jpg
 */
class RemotePathUtilTest {

    @Test
    fun `join 自动处理多余斜杠`() {
        assertEquals(
            "smb://host:445/share/PhotoBackup/DCIM/Camera/a.jpg",
            RemotePathUtil.join("smb://host:445/share/PhotoBackup/", "/DCIM/Camera/", "/a.jpg"),
        )
    }

    @Test
    fun `join 忽略空白段`() {
        assertEquals(
            "https://nas:5006/dav/base/a.jpg",
            RemotePathUtil.join("https://nas:5006/dav/base", "", "  ", "a.jpg"),
        )
    }

    @Test
    fun `normalizeRelativeDir 处理 null 与反斜杠`() {
        assertEquals("", RemotePathUtil.normalizeRelativeDir(null))
        assertEquals("", RemotePathUtil.normalizeRelativeDir("   "))
        assertEquals("DCIM/Camera", RemotePathUtil.normalizeRelativeDir("DCIM/Camera/"))
        assertEquals("DCIM/Camera", RemotePathUtil.normalizeRelativeDir("\\DCIM\\Camera\\"))
    }

    @Test
    fun `fileName 取最后一段`() {
        assertEquals("a.jpg", RemotePathUtil.fileName("smb://host/share/a.jpg"))
    }
}
