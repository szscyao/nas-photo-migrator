package com.nasphoto.migrator.protocol

/**
 * 远程路径拼接工具。
 *
 * 约定：
 *  - NAS 配置保存的是「迁移根目录」（如 SMB: smb://192.168.1.10:445/home/PhotoBackup，
 *    WebDAV: https://nas.example.com/dav/PhotoBackup）；
 *  - 具体文件路径 = 根目录 + MediaStore 的 RELATIVE_PATH（如 DCIM/Camera/）+ 文件名；
 *  - 本工具只做斜杠归一化，不做 URL 编码（SMB 由 jcifs 处理，WebDAV 由 sardine 处理）。
 */
object RemotePathUtil {

    /** 拼接任意段路径，自动去除多余斜杠；空段忽略。 */
    fun join(root: String, vararg parts: String): String {
        val sb = StringBuilder(root.trim().trimEnd('/'))
        for (part in parts) {
            val segment = part.trim().trim('/', '\\')
            if (segment.isEmpty()) continue
            sb.append('/').append(segment)
        }
        return sb.toString()
    }

    /**
     * 把 MediaStore RELATIVE_PATH（如 "DCIM/Camera/"）转成安全路径段，去掉设备相关的多余层级。
     * 保留原有目录结构，仅做斜杠归一。
     */
    fun normalizeRelativeDir(relativePath: String?): String {
        if (relativePath.isNullOrBlank()) return ""
        return relativePath.trim()
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    /** 取路径最后一段（文件名），用于日志展示。 */
    fun fileName(path: String): String = path.substringAfterLast('/')
}
