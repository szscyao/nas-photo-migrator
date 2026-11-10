package com.nasphoto.migrator.core

import com.nasphoto.migrator.data.gallery.FileKeyUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续传 fileKey 的纯 JVM 单元测试（不依赖 Android 运行时）。
 *
 * 这些断言直接对应铁则 R4（断点续传）：只要 fileKey 稳定，重启后就能正确差集出「未完成」的文件。
 */
class FileKeyUtilTest {

    @Test
    fun `fileKey 对同一文件稳定不变`() {
        val a = FileKeyUtil.build("DCIM/Camera/", "IMG_0001.jpg", 1024L, 1_700_000_000_000L)
        val b = FileKeyUtil.build("DCIM/Camera/", "IMG_0001.jpg", 1024L, 1_700_000_000_000L)
        assertEquals(a, b)
        assertEquals(32, a.length) // MD5 十六进制
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test
    fun `内容变化（size 或 mtime）会产生不同 fileKey`() {
        val base = FileKeyUtil.build("DCIM/Camera/", "IMG_0001.jpg", 1024L, 1_700_000_000_000L)
        val sizeChanged = FileKeyUtil.build("DCIM/Camera/", "IMG_0001.jpg", 2048L, 1_700_000_000_000L)
        val timeChanged = FileKeyUtil.build("DCIM/Camera/", "IMG_0001.jpg", 1024L, 1_700_000_001_000L)
        val pathChanged = FileKeyUtil.build("Pictures/", "IMG_0001.jpg", 1024L, 1_700_000_000_000L)
        val nameChanged = FileKeyUtil.build("DCIM/Camera/", "IMG_0002.jpg", 1024L, 1_700_000_000_000L)

        assertNotEquals(base, sizeChanged)
        assertNotEquals(base, timeChanged)
        assertNotEquals(base, pathChanged)
        assertNotEquals(base, nameChanged)
    }

    @Test
    fun `空值与 null 输入不会崩溃且结果稳定`() {
        val a = FileKeyUtil.build(null, null, 0L, 0L)
        val b = FileKeyUtil.build("", "", 0L, 0L)
        assertEquals(a, b)
    }

    @Test
    fun `md5Hex 计算与已知向量一致`() {
        // "abc" 的 MD5 = 900150983cd24fb0d6963f7d28e17f72
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            FileKeyUtil.md5Hex("abc".toByteArray(Charsets.UTF_8)),
        )
        // 空串 MD5 = d41d8cd98f00b204e9800998ecf8427e
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            FileKeyUtil.md5Hex(ByteArray(0)),
        )
    }
}
