package com.nasphoto.migrator.business.engine

import com.nasphoto.migrator.business.log.Logger
import com.nasphoto.migrator.business.model.FileProcessResult
import com.nasphoto.migrator.business.model.VerificationMode
import com.nasphoto.migrator.business.verify.IntegrityVerifier
import com.nasphoto.migrator.business.verify.VerifyResult
import com.nasphoto.migrator.data.config.AppSettings
import com.nasphoto.migrator.data.config.NasConfig
import com.nasphoto.migrator.data.gallery.DeleteResult
import com.nasphoto.migrator.data.gallery.GalleryRepository
import com.nasphoto.migrator.data.gallery.MigrationSource
import com.nasphoto.migrator.data.record.FailureRecord
import com.nasphoto.migrator.data.record.MigratedRecord
import com.nasphoto.migrator.data.record.MigrationStore
import com.nasphoto.migrator.protocol.NasClient
import com.nasphoto.migrator.protocol.NasErrorType
import com.nasphoto.migrator.protocol.NasFileInfo
import com.nasphoto.migrator.protocol.RemotePathUtil
import com.nasphoto.migrator.protocol.UploadSource
import com.nasphoto.migrator.protocol.toNasErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 单文件处理管线 —— 铁则 R2 / R3 / R5 的**唯一**执行点。
 *
 * 严格顺序（每一行注释都对应任务书的编号，任何优化都不得调整顺序）：
 *  1. 本地文件存在性检查
 *  2. 计算远程路径 + ensureDirectory
 *  3. 远程已存在检查（大小一致 → 校验 → 删本地；大小不一致 → REMOTE_CONFLICT；不存在 → 上传）
 *  4. 流式上传（带节流进度回调）
 *  5. 上传失败 → 指数退避重试 3 次（2s/4s/8s，共最多 4 次尝试）→ 仍失败只写失败记录
 *  6. 上传成功后完整性校验（size 必须一致；开启 MD5 时再比对 MD5，服务器不支持则降级 SIZE）
 *  7. 校验失败 → 失败记录 + 保留本地（绝不删除）
 *  8. 校验通过 → 删除本地 → 二次确认 → 写完成记录 → 返回结果（只有到这里引擎才进下一张）
 *  9. 任何安全点检测到暂停/停止 → 抛 [ProcessInterruptedException]，不写完成记录、不删本地
 *
 * 并发安全说明（R1）：
 *  - 本类的 [process] 只会在引擎**唯一**的会话协程中被调用，且该协程运行在
 *    `Dispatchers.IO.limitedParallelism(1)` 上，物理上不存在第二个执行者；
 *  - 本类不持有任何可变共享状态（全部为构造注入的只读依赖 + 方法内局部变量），
 *    即使将来有人误并发调用也不会互相污染计数器；
 *  - 删除动作只出现在 [completeAfterVerification] 内部，而它只能从「校验通过」分支进入，
 *    因此「未校验就删除」在类型/控制流上都不可能发生。
 */
// internal：管线是引擎的实现细节，且方法签名使用内部类型 ProcessControl；
// 不对外暴露可避免 Kotlin「public 声明暴露 internal 类型」的编译错误，也防止 UI 层绕过引擎直接调管线。
internal class FileProcessPipeline(
    private val galleryRepository: GalleryRepository,
    private val migrationStore: MigrationStore,
    private val logger: Logger,
    private val verifier: IntegrityVerifier,
    private val retryPolicy: RetryPolicy,
) {

    /**
     * 处理单个文件，返回闭环结果。
     *
     * @param source MediaStore 元数据（含稳定 fileKey）
     * @param config 当前激活的 NAS 配置（远程根路径来源）
     * @param client 已连接的 NAS 客户端（由引擎会话持有，全程复用）
     * @param uploadSource 可重复开流的本地数据源（重试/重算 MD5 时需要重新打开）
     * @param settings 应用设置（MD5 开关等）
     * @param control 引擎提供的进度/中断控制面
     */
    suspend fun process(
        source: MigrationSource,
        config: NasConfig,
        client: NasClient,
        uploadSource: UploadSource,
        settings: AppSettings,
        control: ProcessControl,
    ): FileProcessResult {
        // 远程路径 = 配置根目录 + MediaStore RELATIVE_PATH（保留相册目录结构）+ 文件名
        val remoteDir = RemotePathUtil.join(config.remoteRootPath, source.remoteRelativeDir)
        val remotePath = RemotePathUtil.join(
            config.remoteRootPath,
            source.remoteRelativeDir,
            source.displayName,
        )
        logger.d(
            TAG,
            "单文件开始：${source.displayName} | 本地=${source.size} B | 远程=$remotePath | " +
                "fileKey=${source.fileKey}",
        )

        // ==================== 步骤 1：本地文件存在性检查 ====================
        // 文件可能已被用户手动删除（或索引已失效）：没有本地文件就没有「删除释放空间」的意义，
        // 直接 Skipped（不写完成记录、不写失败记录），继续下一张。
        control.onStage(MigrationStage.CHECK)
        val localExists = try {
            galleryRepository.localExists(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 查询本地是否存在本身失败（权限/MediaStore 异常）→ 失败流程，本地文件不动
            return fail(
                source = source,
                remotePath = remotePath,
                errorType = e.toNasErrorType(),
                reason = "检查本地文件是否存在失败：${e.message}",
                attempts = 0,
            )
        }
        if (!localExists) {
            logger.i(TAG, "跳过：本地文件已不存在（可能已被用户删除）${source.displayName}")
            return FileProcessResult.Skipped(
                fileKey = source.fileKey,
                displayName = source.displayName,
                sizeBytes = source.size,
                reason = "本地文件已不存在（可能已被用户删除）",
            )
        }
        // 安全点：暂停/停止在进入上传前立即生效
        control.interruptIfRequested()

        // ==================== 步骤 2：计算远程路径并确保目标目录存在 ====================
        control.onStage(MigrationStage.CHECK)
        try {
            // 递归创建「根目录/相册相对路径」，失败按 NasException 分类走失败流程
            client.ensureDirectory(remoteDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return fail(
                source = source,
                remotePath = remotePath,
                errorType = e.toNasErrorType(),
                reason = "创建远程目录失败（$remoteDir）：${e.message}",
                attempts = 0,
            )
        }
        control.interruptIfRequested()

        // ==================== 步骤 3：远程已存在检查 ====================
        // 作用：① 断点续传的兜底（上次已上传但本地未删，例如删除失败/进程被杀），
        //       ② 防止同名文件被静默覆盖造成「NAS 上不是这一张」。
        control.onStage(MigrationStage.CHECK)
        val existingInfo: NasFileInfo? = try {
            client.remoteFileInfo(remotePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return fail(
                source = source,
                remotePath = remotePath,
                errorType = e.toNasErrorType(),
                reason = "查询远程文件信息失败：${e.message}",
                attempts = 0,
            )
        }

        if (existingInfo != null && !existingInfo.isDirectory) {
            if (existingInfo.size == source.size) {
                // ---- 3a：存在且大小一致 → 先校验（MD5 可选），通过则跳过上传直接删本地 ----
                control.onStage(MigrationStage.VERIFY)
                val existingVerify = verifier.verify(
                    client = client,
                    remotePath = remotePath,
                    remoteInfo = existingInfo,
                    source = uploadSource,
                    verifyMd5 = settings.verifyMd5,
                )
                when (existingVerify) {
                    is VerifyResult.Passed -> {
                        logger.i(
                            TAG,
                            "NAS 端已存在且校验一致，跳过上传直接删除本地：${source.displayName}" +
                                "（${existingVerify.mode.description}）",
                        )
                        // 校验通过 → 走同一个「删除 → 记录」临界区（R2/R5 顺序不变）
                        return completeAfterVerification(
                            source = source,
                            remotePath = remotePath,
                            mode = existingVerify.mode,
                            alreadyExisted = true,
                            control = control,
                        )
                    }

                    is VerifyResult.Failed -> {
                        // ---- 3b：大小一致但 MD5 不一致（同名不同内容）→ 改造为覆盖上传后重新校验 ----
                        // 注意：此分支**不删除本地**，只有覆盖上传并重新校验通过后才删除。
                        logger.w(
                            TAG,
                            "NAS 端同名文件校验未通过（${existingVerify.errorType.name}），改为覆盖上传后重新校验：" +
                                source.displayName,
                        )
                    }
                }
            } else {
                // ---- 3c：存在但大小不一致 → 默认不覆盖、不删除 → REMOTE_CONFLICT 失败 ----
                // 这是数据安全分支：用户可能在 NAS 上有同名但内容不同的重要文件，
                // 静默覆盖会丢数据；删除本地更会让手机侧内容永失。因此只记失败，继续下一张。
                //
                // 唯一例外（保证断点续传不会被自己的残片永久卡死）：
                //   远程文件比本地小 + 失败表里存在「上次上传被中断/网络类失败」记录
                //   → 该文件几乎必然是本应用上次写了一半的残片，允许覆盖上传后重新校验。
                //   正常流程不会覆盖已存在的不同大小文件（本分支前面就是拦截），所以此例外不会误伤用户文件。
                val isPartialUploadResidue = existingInfo.size < source.size &&
                    hasInterruptedUploadRecord(source.fileKey)
                if (isPartialUploadResidue) {
                    logger.w(
                        TAG,
                        "检测到上次上传中断留下的不完整远程文件（NAS=${existingInfo.size} B < " +
                            "本地=${source.size} B），改为覆盖上传后重新校验：${source.displayName}",
                    )
                } else {
                    return fail(
                        source = source,
                        remotePath = remotePath,
                        errorType = NasErrorType.REMOTE_CONFLICT,
                        reason = "NAS 端存在同名但大小不一致的文件" +
                            "（NAS=${existingInfo.size} B，本地=${source.size} B），" +
                            "为避免误删/覆盖已跳过",
                        attempts = 0,
                    )
                }
            }
        }
        // 不存在（或上面 3b 改为覆盖上传）→ 进入上传
        control.interruptIfRequested()

        // ==================== 步骤 4 + 步骤 5：流式上传 + 失败自动重试 ====================
        // 重试策略：RetryPolicy 指数退避 2s/4s/8s（共 3 次重试 = 最多 4 次尝试）。
        // 每次重试前都重新 openStream（MediaStore 流不可 reset，见 UploadSource 契约）。
        control.onStage(MigrationStage.UPLOAD)
        var attempt = 0
        var uploaded = false
        var lastErrorType: NasErrorType = NasErrorType.UNKNOWN
        var lastErrorMessage = ""

        while (true) {
            // 安全点：每次（重）试之前都检查暂停/停止，保证「停止」不会再多传一份文件
            control.interruptIfRequested()
            attempt++
            control.onRetry(retryCount = attempt - 1)
            try {
                val written = client.upload(
                    remotePath = remotePath,
                    source = uploadSource,
                    onProgress = { bytesWritten, totalBytes ->
                        // 进度回调是最细粒度的安全点：暂停/停止时尽快中断本次上传。
                        // 这里抛出可识别异常，由下面的 catch 统一判定（即使被协议层包装成 NasException，
                        // catch 里还会再查一次 checkInterrupt()，见下）。
                        val signal = control.checkInterrupt()
                        if (signal != null) throw ProcessInterruptedException(signal)
                        control.onProgress(bytesWritten, totalBytes)
                    },
                )
                uploaded = true
                logger.i(
                    TAG,
                    "上传完成：${source.displayName} | 协议实际写入=$written B | " +
                        "本地=${source.size} B | 第 $attempt 次尝试",
                )
                break
            } catch (e: CancellationException) {
                // 协程取消（引擎 shutdown）必须原样抛出，不能被当成普通上传失败
                throw e
            } catch (e: Throwable) {
                // ① 优先判定「暂停/停止请求」：中断信号可能被协议层 catch(Exception) 包装成 NasException。
                //    这里先落一条 INTERRUPTED 失败记录作为「NAS 端可能残留不完整文件」的持久标记，
                //    供下次运行在 3c 分支判定是否允许覆盖上传（否则断点续传会被自己的残片卡死）；
                //    然后原样抛出可识别异常 —— 不写完成记录、不删除本地（R3/R5）。
                val interruptSignal = control.checkInterrupt()
                if (interruptSignal != null) {
                    markInterruptedUpload(
                        source = source,
                        remotePath = remotePath,
                        reason = "上传被暂停/停止中断（${e.message ?: "进度回调中断"}）",
                        attempts = attempt,
                    )
                    throw ProcessInterruptedException(interruptSignal)
                }

                lastErrorType = e.toNasErrorType()
                lastErrorMessage = e.message ?: lastErrorType.description
                logger.w(
                    TAG,
                    "上传第 $attempt 次失败（${lastErrorType.name}）：${source.displayName} | $lastErrorMessage",
                )

                // ② 传输过程中网络中断（阶段1 文档 2.2）：若此刻网络规则已不满足，
                //    → 暂停整个任务等待恢复（不把文件记为普通失败）；本地文件保留，远程残片下次覆盖。
                if (lastErrorType.retryable) {
                    val pauseReason = control.networkPauseReason()
                    if (pauseReason != null) {
                        markInterruptedUpload(
                            source = source,
                            remotePath = remotePath,
                            reason = "上传过程中网络中断（${lastErrorType.name}: $lastErrorMessage）",
                            attempts = attempt,
                        )
                        throw ProcessInterruptedException(InterruptSignal.Pause(pauseReason))
                    }
                }

                // 全局性错误（认证失败/空间不足/权限）→ 不重试，交给引擎暂停整个任务
                if (lastErrorType.shouldPauseTask) break
                // 重试次数用尽或错误类型不值得重试 → 失败收尾（本地文件保留，R3）
                if (!retryPolicy.shouldRetry(attempt, lastErrorType)) break

                val delayMs = retryPolicy.delayForRetry(attempt)
                control.onRetry(retryCount = attempt)
                logger.i(
                    TAG,
                    "第 $attempt 次失败，${delayMs}ms 后进行第 ${attempt + 1} 次尝试：" +
                        source.displayName,
                )
                // 退避期间挂起协程：不占用串行执行线程，也不影响暂停/停止命令的投递
                delay(delayMs)
            }
        }

        if (!uploaded) {
            // 步骤 5（收尾）：3 次重试后仍失败 → FailureRecord + 继续下一张（绝不删除本地）
            return fail(
                source = source,
                remotePath = remotePath,
                errorType = lastErrorType,
                reason = "上传失败（共尝试 $attempt 次）：$lastErrorMessage",
                attempts = attempt,
            )
        }

        // ==================== 步骤 6：上传成功后完整性校验 ====================
        // R2 的物理落点：只有下面的 verify 通过，才允许走到步骤 8 的 deleteLocal。
        control.onStage(MigrationStage.VERIFY)
        val uploadedInfo = try {
            // 重新查询 NAS 端真实大小（不信任上传返回值：协议可能写入少于预期）
            client.remoteFileInfo(remotePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return fail(
                source = source,
                remotePath = remotePath,
                errorType = e.toNasErrorType(),
                reason = "上传后查询远程文件信息失败：${e.message}",
                attempts = attempt,
            )
        }

        val verifyResult = verifier.verify(
            client = client,
            remotePath = remotePath,
            remoteInfo = uploadedInfo,
            source = uploadSource,
            verifyMd5 = settings.verifyMd5,
        )

        if (verifyResult is VerifyResult.Failed) {
            // ==================== 步骤 7：校验失败 → 本地文件必须保留 ====================
            return fail(
                source = source,
                remotePath = remotePath,
                errorType = verifyResult.errorType,
                reason = verifyResult.reason,
                attempts = attempt,
            )
        }

        val passed = verifyResult as VerifyResult.Passed
        val passedMode = passed.mode
        logger.i(
            TAG,
            "上传后校验通过：${source.displayName}（${passedMode.description}" +
                "${passed.note?.let { note -> "，$note" } ?: ""}）",
        )

        // ==================== 步骤 8：校验通过 → 删除本地 → 写记录 ====================
        // 从这里开始的「删除 → 二次确认 → 写完成记录 → 返回」是闭环临界区：
        // 内部不再插入暂停/停止中断点，避免出现「远程已成功、本地删到一半、记录未写」的三方不一致。
        // 暂停/停止会在下一次进入单文件处理前（会话循环顶部）生效。
        return completeAfterVerification(
            source = source,
            remotePath = remotePath,
            mode = passedMode,
            alreadyExisted = false,
            control = control,
        )
    }

    /**
     * 闭环收尾：删除本地 → 二次确认 → 写完成记录。
     *
     * 调用前提（且**仅有**这两个入口）：上传成功且校验通过，或远程已存在且校验通过。
     * 方法内部顺序严格为「删除 → 确认 → 记录」，任何一步失败都不会产生「已完成」假象：
     *  - 删除失败：不写 MigratedRecord（否则下次断点续传会认为已完成，本地原图永远残留且没有任何提示）；
     *  - 删除成功但记录写失败：仍返回成功（本地空间已释放），但记 ERROR 日志，下次会重复上传一次。
     */
    private suspend fun completeAfterVerification(
        source: MigrationSource,
        remotePath: String,
        mode: VerificationMode,
        alreadyExisted: Boolean,
        control: ProcessControl,
    ): FileProcessResult {
        // ---------- 步骤 8a：删除本地原图（铁则 R2：只会在校验通过后执行到这里） ----------
        control.onStage(MigrationStage.DELETE)
        val deleteResult = try {
            galleryRepository.deleteLocal(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DeleteResult.Failed(
                reason = e.message ?: "删除本地文件时发生异常",
                permissionMissing = e is SecurityException,
            )
        }

        when (deleteResult) {
            is DeleteResult.Success -> {
                // ---------- 步骤 8b：R5 二次确认 —— 删除返回成功后再查一次确实已不存在 ----------
                val stillExists = try {
                    galleryRepository.localExists(source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 确认查询失败时不否定已成功的删除结果（避免把已删除文件误记为失败）
                    logger.w(TAG, "删除后二次确认查询失败（按已删除处理）：${source.displayName} / ${e.message}")
                    false
                }
                if (stillExists) {
                    // 删除报告成功但文件仍在：按删除失败处理，不写完成记录（R5：删完才进下一张）
                    return deleteFailed(
                        source = source,
                        remotePath = remotePath,
                        reason = "删除返回成功，但二次确认时本地文件仍然存在（rows=${deleteResult.rows}）",
                        permissionMissing = false,
                    )
                }
                logger.i(TAG, "本地删除成功并二次确认（不存在）：${source.displayName}")
            }

            is DeleteResult.AlreadyGone -> {
                // 文件在此之前已被删除（例如用户手动删除）→ 空间已释放，按成功处理（R5 明确允许）
                logger.i(TAG, "本地文件已不存在（AlreadyGone，视为空间已释放）：${source.displayName} / ${deleteResult.reason}")
            }

            is DeleteResult.Failed -> {
                // ---------- 步骤 8c：删除失败 → 不写 MigratedRecord，写 FailureRecord + ERROR 日志 ----------
                return deleteFailed(
                    source = source,
                    remotePath = remotePath,
                    reason = deleteResult.reason,
                    permissionMissing = deleteResult.permissionMissing,
                )
            }
        }

        // ---------- 步骤 8d：写完成记录（R4 断点续传的唯一依据） ----------
        // 顺序不可颠倒：必须是「本地删除确认成功」之后才写，否则重启会跳过该文件、本地原图永久残留。
        control.onStage(MigrationStage.RECORD)
        val record = MigratedRecord(
            fileKey = source.fileKey,
            displayName = source.displayName,
            uriString = source.uri.toString(),
            sizeBytes = source.size,
            remotePath = remotePath,
            albumName = source.albumName,
            migratedAt = System.currentTimeMillis(),
            deletedLocal = true,
            verification = mode.name,
        )
        try {
            migrationStore.recordMigrated(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 记录写失败不会造成数据丢失（远端有完整副本、本地已删除），
            // 但下次启动会重复上传该文件，必须留 ERROR 供排查。
            logger.e(TAG, "写入完成记录失败（下次启动会重复上传该文件）：${source.displayName}", e)
        }

        // 成功后清理该文件的历史失败记录：避免异常管理页残留已解决的旧问题
        try {
            migrationStore.removeFailureByFileKey(source.fileKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.w(TAG, "清理历史失败记录失败（不影响迁移结果）：${source.displayName} / ${e.message}")
        }

        logger.i(
            TAG,
            "单文件闭环完成：${source.displayName} | 远程=$remotePath | 校验=${mode.name} | " +
                "本地已删除 | 释放=${source.size} B",
        )

        return if (alreadyExisted) {
            FileProcessResult.AlreadyExisted(
                fileKey = source.fileKey,
                displayName = source.displayName,
                sizeBytes = source.size,
                remotePath = remotePath,
                verification = mode,
            )
        } else {
            FileProcessResult.Success(
                fileKey = source.fileKey,
                displayName = source.displayName,
                sizeBytes = source.size,
                remotePath = remotePath,
                verification = mode,
            )
        }
    }

    /** 删除失败收尾：本地保留、不写完成记录、只写失败记录 + ERROR 日志（R3/R5） */
    private suspend fun deleteFailed(
        source: MigrationSource,
        remotePath: String,
        reason: String,
        permissionMissing: Boolean,
    ): FileProcessResult.DeleteFailed {
        val errorType = if (permissionMissing) {
            NasErrorType.LOCAL_PERMISSION_DENIED
        } else {
            NasErrorType.LOCAL_DELETE_FAILED
        }
        logger.e(TAG, "本地删除失败（本地文件保留）：${source.displayName} / $reason")
        recordFailureQuietly(
            source = source,
            remotePath = remotePath,
            errorType = errorType,
            reason = reason,
            attempts = 1,
        )
        return FileProcessResult.DeleteFailed(
            fileKey = source.fileKey,
            displayName = source.displayName,
            sizeBytes = source.size,
            remotePath = remotePath,
            reason = reason,
        )
    }

    /** 失败收尾：写 FailureRecord + 日志，返回 Failed（本地文件一律保留，R3） */
    private suspend fun fail(
        source: MigrationSource,
        remotePath: String,
        errorType: NasErrorType,
        reason: String,
        attempts: Int,
    ): FileProcessResult.Failed {
        logger.e(TAG, "迁移失败 [${errorType.name}] ${source.displayName}：$reason（本地文件已保留）")
        recordFailureQuietly(
            source = source,
            remotePath = remotePath,
            errorType = errorType,
            reason = reason,
            attempts = attempts,
        )
        return FileProcessResult.Failed(
            fileKey = source.fileKey,
            displayName = source.displayName,
            sizeBytes = source.size,
            remotePath = remotePath,
            errorType = errorType,
            reason = reason,
            attempts = attempts.coerceAtLeast(1),
            shouldPauseTask = errorType.shouldPauseTask,
        )
    }

    /**
     * 失败表里是否存在「上次上传被中断/网络类失败」记录。
     *
     * 用途：3c 分支判断「NAS 端同名但更小的文件」是否为本应用上次写了一半的残片。
     * 为什么要持久化这个标记：上传中断（暂停/断网/进程被杀）后远程可能留下不完整文件，
     * 下次启动若直接按「大小不一致 → 冲突」处理，这个文件将永远无法迁移（断点续传被自己的残片卡死）。
     */
    private suspend fun hasInterruptedUploadRecord(fileKey: String): Boolean = try {
        migrationStore.failuresSnapshot().any { record ->
            record.fileKey == fileKey && record.errorType in PARTIAL_UPLOAD_ERROR_TYPES
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // 查询失败时按「非残片」处理（保守：宁可报冲突也不误覆盖用户文件）
        logger.w(TAG, "查询失败记录失败（按非残片处理）：${e.message}")
        false
    }

    /**
     * 记录「上传被中断」标记（**不是最终失败**，Lead 已批准的残片处理方案）：
     *  - 用途：上传被暂停/停止/网络中断时，NAS 端可能留下不完整文件；写一条 INTERRUPTED 记录，
     *    供下次运行在 3c 分支判定「远程同名且比本地小」是否为本应用残片（是则允许覆盖上传）；
     *  - 日志以 `[中断标记]` 前缀区分于 `[迁移失败]`，异常管理页/日志页可据此解释「这不是内容失败」；
     *  - 覆盖上传成功并通过校验后，[completeAfterVerification] 里的
     *    `migrationStore.removeFailureByFileKey(fileKey)` 会清除该标记，不会残留到成功之后。
     */
    private suspend fun markInterruptedUpload(
        source: MigrationSource,
        remotePath: String,
        reason: String,
        attempts: Int,
    ) {
        logger.w(
            TAG,
            "[中断标记] ${source.displayName}：$reason；NAS 端可能有残片，下次将覆盖上传（非最终失败）",
        )
        recordFailureQuietly(
            source = source,
            remotePath = remotePath,
            errorType = NasErrorType.INTERRUPTED,
            reason = "$reason；任务中断导致，NAS 端可能残留不完整文件，下次将覆盖上传（非内容校验失败）",
            attempts = attempts,
        )
    }

    /**
     * 写失败记录（尽力而为）：
     * 写记录本身失败也**绝不能**改变「本地文件保留」的结论（R3），所以这里吞掉异常只记日志。
     */
    private suspend fun recordFailureQuietly(
        source: MigrationSource,
        remotePath: String,
        errorType: NasErrorType,
        reason: String,
        attempts: Int,
    ) {
        try {
            migrationStore.recordFailure(
                FailureRecord(
                    fileKey = source.fileKey,
                    displayName = source.displayName,
                    uriString = source.uri.toString(),
                    sizeBytes = source.size,
                    remotePath = remotePath,
                    albumName = source.albumName,
                    errorType = errorType,
                    reason = reason,
                    attempts = attempts.coerceAtLeast(1),
                    failedAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e(TAG, "写入失败记录失败（不影响「本地保留」结论）：${source.displayName} / ${e.message}")
        }
    }

    private companion object {
        const val TAG = Logger.TAG_ENGINE

        /** 可能意味着「NAS 端留有上传残片」的失败类型（用于 3c 分支的覆盖上传判定） */
        val PARTIAL_UPLOAD_ERROR_TYPES = setOf(
            NasErrorType.INTERRUPTED,
            NasErrorType.CONNECTION_FAILED,
            NasErrorType.TIMEOUT,
            NasErrorType.HOST_UNREACHABLE,
            NasErrorType.UNKNOWN,
        )
    }
}
