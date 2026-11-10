package com.nasphoto.migrator.business.verify

import com.nasphoto.migrator.business.log.Logger
import com.nasphoto.migrator.business.model.VerificationMode
import com.nasphoto.migrator.protocol.NasClient
import com.nasphoto.migrator.protocol.NasErrorType
import com.nasphoto.migrator.protocol.NasFileInfo
import com.nasphoto.migrator.protocol.UploadSource
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

/** 校验结果 */
sealed interface VerifyResult {

    /** 校验通过（[mode] 为实际达成的校验强度） */
    data class Passed(
        val mode: VerificationMode,
        /** 降级/附加说明（例如服务器不支持 MD5 时降级为 SIZE） */
        val note: String? = null,
    ) : VerifyResult

    /** 校验失败 → 调用方必须记失败记录并保留本地文件（R2/R3） */
    data class Failed(
        val errorType: NasErrorType,
        val reason: String,
    ) : VerifyResult
}

/**
 * 完整性校验器（铁则 R2 的唯一执行点）。
 *
 * 为什么校验必须在「上传成功之后、删除本地之前」由独立组件完成：
 *  - 这是「校验通过才可删除」的物理落点：调用顺序由 [com.nasphoto.migrator.business.engine.FileProcessPipeline]
 *    强制为 upload → verify → delete，本类只负责给出 pass/fail，绝不触碰本地文件；
 *  - 最低要求是 **NAS 端 size == 本地 SIZE**（MediaStore 的 SIZE 即本地真实字节数）；
 *  - [com.nasphoto.migrator.data.config.AppSettings.verifyMd5] 开启时才做 MD5 双校验；
 *    服务器不支持远程 MD5（返回 null）时记 WARN 并降级为 SIZE 校验，避免「校验永远失败 → 永远不删除」
 *    把迁移卡死（阶段1 文档 3.4 与任务书步骤 6 明确要求降级）。
 */
class IntegrityVerifier(private val logger: Logger) {

    /**
     * 校验 NAS 端文件与本地源的一致性。
     *
     * @param client 已连接的 NAS 客户端
     * @param remotePath 远程完整路径
     * @param remoteInfo 调用方刚查询到的远程文件信息（避免重复查询；为 null 表示远程不存在）
     * @param source 本地数据源（用于读取本地真实大小与流式 MD5）
     * @param verifyMd5 是否开启 MD5 双校验
     */
    suspend fun verify(
        client: NasClient,
        remotePath: String,
        remoteInfo: NasFileInfo?,
        source: UploadSource,
        verifyMd5: Boolean,
    ): VerifyResult {
        // ---------- ① 大小校验：R2 的最低要求，任何设置下都必须通过 ----------
        if (remoteInfo == null) {
            return VerifyResult.Failed(
                errorType = NasErrorType.REMOTE_NOT_FOUND,
                reason = "校验失败：NAS 端未找到目标文件（$remotePath）",
            )
        }
        if (remoteInfo.isDirectory) {
            return VerifyResult.Failed(
                errorType = NasErrorType.VERIFY_SIZE_MISMATCH,
                reason = "校验失败：NAS 端目标路径是目录而非文件（$remotePath）",
            )
        }
        if (remoteInfo.size != source.size) {
            return VerifyResult.Failed(
                errorType = NasErrorType.VERIFY_SIZE_MISMATCH,
                reason = "校验失败：大小不一致（NAS=${remoteInfo.size} B，本地=${source.size} B）",
            )
        }
        logger.d(TAG, "大小校验通过：${source.displayName}（${source.size} B）")

        // 未开启 MD5 时到此即通过（默认路径）
        if (!verifyMd5) return VerifyResult.Passed(VerificationMode.SIZE)

        // ---------- ② MD5 校验（可选，逐字节比对内容） ----------
        val localMd5 = try {
            computeLocalMd5(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 本地读不出来（文件被占用/已被删除）→ 校验无法完成，按失败处理并保留本地
            return VerifyResult.Failed(
                errorType = NasErrorType.LOCAL_READ_FAILED,
                reason = "校验失败：无法读取本地文件计算 MD5（${e.message}）",
            )
        }

        val remoteMd5 = try {
            client.remoteMd5(remotePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 远程 MD5 读取异常不视为内容不一致：记录警告后降级为 SIZE 校验
            logger.w(TAG, "读取远程 MD5 失败（${e.message}），降级为仅大小校验：${source.displayName}")
            return VerifyResult.Passed(VerificationMode.SIZE, note = "远程 MD5 读取失败，已降级为 SIZE 校验")
        }

        if (remoteMd5.isNullOrBlank()) {
            // 服务器不支持远程 MD5（如部分 WebDAV 服务端）→ 降级为 SIZE 校验（任务书步骤 6）
            logger.w(TAG, "服务器不支持远程 MD5，降级为仅大小校验：${source.displayName}")
            return VerifyResult.Passed(VerificationMode.SIZE, note = "服务器不支持 MD5，已降级为 SIZE 校验")
        }

        return if (localMd5.equals(remoteMd5.trim(), ignoreCase = true)) {
            logger.d(TAG, "MD5 校验通过：${source.displayName}（$localMd5）")
            VerifyResult.Passed(VerificationMode.SIZE_AND_MD5)
        } else {
            VerifyResult.Failed(
                errorType = NasErrorType.VERIFY_MD5_MISMATCH,
                reason = "校验失败：MD5 不一致（本地=$localMd5，NAS=${remoteMd5.trim()}）",
            )
        }
    }

    /**
     * 流式计算本地文件 MD5。
     *
     * 注意：本方法是阻塞读（64KB 缓冲区），调用点位于引擎的 IO 串行调度器上，
     * 且只处理「当前这一张」文件 —— 与 R1「同一时间只处理一个文件」一致，不会并发读盘。
     */
    private fun computeLocalMd5(source: UploadSource): String {
        val digest = MessageDigest.getInstance("MD5")
        source.openStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
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

    private companion object {
        const val TAG = Logger.TAG_ENGINE
        const val BUFFER_SIZE = 64 * 1024
    }
}
