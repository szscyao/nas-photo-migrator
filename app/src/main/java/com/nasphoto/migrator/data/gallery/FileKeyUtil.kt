package com.nasphoto.migrator.data.gallery

import java.security.MessageDigest

/**
 * 断点续传唯一标识（fileKey）生成器。
 *
 * 为什么不用 MediaStore `_ID`：
 *  - 设备索引重建 / 恢复出厂 / 换手机后 `_ID` 会变化，导致重复上传；
 *  - 本工具要求「重启/中断后从断点继续、不重复上传」，必须使用内容稳定的复合键。
 *
 * 复合键 = MD5(RELATIVE_PATH + "|" + DISPLAY_NAME + "|" + SIZE + "|" + DATE_MODIFIED)
 *  - 同目录同名但内容被替换（SIZE/DATE_MODIFIED 变化）→ 视为新文件，会重新迁移，安全；
 *  - 文件被移动到其它相册（RELATIVE_PATH 变化）→ 视为新文件重新迁移，安全（不会误删未上传文件）。
 *
 * 该函数必须保持**纯 JVM 实现**（不依赖 Android API），便于单元测试与跨版本一致。
 */
object FileKeyUtil {

    fun build(
        relativePath: String?,
        displayName: String?,
        size: Long,
        dateModified: Long,
    ): String {
        val raw = "${relativePath.orEmpty()}|${displayName.orEmpty()}|$size|$dateModified"
        return md5Hex(raw.toByteArray(Charsets.UTF_8))
    }

    /** 计算字节数组的 MD5（小写十六进制），也用于 MD5 完整性校验。 */
    fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(bytes)
        return hash.toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }
}
