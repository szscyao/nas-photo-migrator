package com.nasphoto.migrator.data.gallery

import android.net.Uri

/** 媒体类型 */
enum class MediaKind(val displayName: String) {
    IMAGE("图片"),
    VIDEO("视频"),
}

/**
 * 待迁移媒体项（来自 MediaStore 查询结果 + 计算出的稳定 fileKey）。
 *
 * 注意：本类只保存**元数据 + URI**，绝不保存裸文件路径；删除/读取一律通过
 * `ContentResolver` + `Uri` 完成，满足分区存储（Scoped Storage）硬约束。
 */
data class MigrationSource(
    /** 断点续传唯一标识：MD5(RELATIVE_PATH|DISPLAY_NAME|SIZE|DATE_MODIFIED) */
    val fileKey: String,
    /** MediaStore URI（content://media/external/images/media/xxx） */
    val uri: Uri,
    val displayName: String,
    /** 字节数（MediaStore SIZE 列），校验基准 */
    val size: Long,
    /** 相对路径，如 DCIM/Camera/ */
    val relativePath: String,
    val dateTakenMillis: Long,
    val mimeType: String,
    val mediaKind: MediaKind,
    val albumId: String,
    val albumName: String,
) {
    /** 远程目标目录（相对迁移根目录），如 DCIM/Camera */
    val remoteRelativeDir: String
        get() = com.nasphoto.migrator.protocol.RemotePathUtil.normalizeRelativeDir(relativePath)
}

/** 相册（bucket）信息 */
data class AlbumInfo(
    val albumId: String,
    val albumName: String,
    val itemCount: Int,
    val totalBytes: Long,
)

/** 相册统计（首页「待迁移」概览） */
data class GalleryStats(
    val itemCount: Int = 0,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    val totalBytes: Long = 0,
)

/**
 * 本地删除结果。
 *
 * 铁则 R2/R5：只有 [Success]/[AlreadyGone] 才能让引擎把该 fileKey 记为「已完成」并处理下一张；
 * [Failed] 必须保留本地文件并记录失败原因。
 */
sealed interface DeleteResult {

    /** 删除成功（rows 为 ContentResolver.delete 返回行数，通常为 1） */
    data class Success(val rows: Int) : DeleteResult

    /** 文件已经不存在（被用户在系统相册里手动删掉），视为「空间已释放」，可按成功处理 */
    data class AlreadyGone(val reason: String) : DeleteResult

    /** 删除失败：保留本地文件，记入失败列表/日志 */
    data class Failed(
        val reason: String,
        /** 是否因为权限缺失导致（需要暂停任务并引导授权） */
        val permissionMissing: Boolean = false,
    ) : DeleteResult
}
