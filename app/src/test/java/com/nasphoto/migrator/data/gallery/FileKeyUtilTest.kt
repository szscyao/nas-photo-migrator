package com.nasphoto.migrator.data.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileKeyUtil] 的纯 JVM 单元测试（不依赖 Android 运行时）。
 *
 * 验证断点续传 fileKey 的三条关键性质：
 *  1. 同样的元数据必须得到**完全一致**的 key（重启/差集去重的前提）；
 *  2. 任一参与字段（相对路径/文件名/大小/修改时间）变化都必须得到新 key
 *     （避免「内容变了还以为已迁移」导致误删/漏传）；
 *  3. MD5 实现正确（与标准向量一致，也用于可选的文件 MD5 完整性校验）。
 *
 * 注意：fileKey 必须使用 DATE_MODIFIED（秒）而不是 DATE_TAKEN —— 见 [GalleryRepositoryImpl.queryPending]。
 */
class FileKeyUtilTest {

    @Test
    fun `build is deterministic for identical metadata`() {
        val a = FileKeyUtil.build("DCIM/Camera/", "IMG_0001.jpg", 1024L, 1_700_000_000L)
        val b = FileKeyUtil.build("DCIM/Camera/", "IMG_0001.jpg", 1024L, 1_700_000_000L)

        assertEquals(a, b)
        assertEquals(32, a.length)
        assertTrue("fileKey 应为小写十六进制", a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `build changes when any component changes`() {
        val base = FileKeyUtil.build("DCIM/Camera/", "IMG_0001.jpg", 1024L, 1_700_000_000L)

        assertNotEquals(base, FileKeyUtil.build("DCIM/Camera/", "IMG_0002.jpg", 1024L, 1_700_000_000L))
        assertNotEquals(base, FileKeyUtil.build("DCIM/Camera/", "IMG_0001.jpg", 2048L, 1_700_000_000L))
        assertNotEquals(base, FileKeyUtil.build("DCIM/Camera/", "IMG_0001.jpg", 1024L, 1_700_000_001L))
        // 相册/目录变化（文件被移动）必须视为新文件，避免误删未上传文件
        assertNotEquals(base, FileKeyUtil.build("Pictures/", "IMG_0001.jpg", 1024L, 1_700_000_000L))
    }

    @Test
    fun `build tolerates null relative path and display name`() {
        // 少数 ROM 的 RELATIVE_PATH/DISPLAY_NAME 可能为 null，契约要求用空串参与计算，不能抛异常
        val key = FileKeyUtil.build(null, null, 0L, 0L)
        assertEquals(32, key.length)
        assertEquals(key, FileKeyUtil.build("", "", 0L, 0L))
    }

    @Test
    fun `md5Hex matches standard vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", FileKeyUtil.md5Hex(ByteArray(0)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", FileKeyUtil.md5Hex("abc".toByteArray(Charsets.UTF_8)))
        assertEquals(
            "9e107d9d372bb6826bd81d3542a419d6",
            FileKeyUtil.md5Hex("The quick brown fox jumps over the lazy dog".toByteArray(Charsets.UTF_8)),
        )
    }
}
