package com.nasphoto.migrator.business.model

import com.nasphoto.migrator.protocol.NasErrorType

/** 完整性校验方式 */
enum class VerificationMode(val description: String) {
    /** 仅校验 NAS 端文件大小 == 本地 SIZE（默认，铁则 R2 最低要求） */
    SIZE("大小校验"),

    /** 大小 + MD5 双校验（设置开启且服务器支持） */
    SIZE_AND_MD5("大小 + MD5 校验"),
}

/**
 * 单文件处理结果（一次「上传 → 校验 → 删除 → 记录」闭环的结果）。
 */
sealed interface FileProcessResult {

    val fileKey: String
    val displayName: String
    val sizeBytes: Long

    /** 完整成功：上传成功 + 校验通过 + 本地删除成功 + 已写入完成记录 */
    data class Success(
        override val fileKey: String,
        override val displayName: String,
        override val sizeBytes: Long,
        val remotePath: String,
        val verification: VerificationMode,
    ) : FileProcessResult

    /** NAS 端已存在且校验一致：跳过上传，直接删除本地（同样写完成记录） */
    data class AlreadyExisted(
        override val fileKey: String,
        override val displayName: String,
        override val sizeBytes: Long,
        val remotePath: String,
        val verification: VerificationMode,
    ) : FileProcessResult

    /**
     * 失败：上传失败 / 校验失败 / 网络中断等。
     * 本地文件**必须保留**，仅写失败记录（铁则 R3）。
     */
    data class Failed(
        override val fileKey: String,
        override val displayName: String,
        override val sizeBytes: Long,
        val remotePath: String,
        val errorType: NasErrorType,
        val reason: String,
        val attempts: Int,
        /** 是否因权限/NAS 空间等全局性问题需要暂停整个任务 */
        val shouldPauseTask: Boolean = false,
    ) : FileProcessResult

    /**
     * 上传与校验都成功，但本地删除失败：本地文件保留，写完成记录（避免重复上传）
     * 并记 ERROR 日志，供异常管理页提示用户手动处理。
     */
    data class DeleteFailed(
        override val fileKey: String,
        override val displayName: String,
        override val sizeBytes: Long,
        val remotePath: String,
        val reason: String,
    ) : FileProcessResult

    /** 跳过：本地文件已不存在 / 正在被系统占用等无需处理的情况 */
    data class Skipped(
        override val fileKey: String,
        override val displayName: String,
        override val sizeBytes: Long,
        val reason: String,
    ) : FileProcessResult
}
