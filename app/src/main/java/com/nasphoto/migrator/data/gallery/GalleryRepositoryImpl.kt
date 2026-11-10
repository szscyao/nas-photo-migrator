package com.nasphoto.migrator.data.gallery

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.nasphoto.migrator.data.config.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册数据仓库实现（**严格 Scoped Storage**）。
 *
 * 所有媒体访问都通过 MediaStore 集合 Uri + [ContentResolver]，绝不使用裸文件路径：
 *  - 查询：`content://media/external/images/media` / `.../video/media`；
 *  - 删除：`content://media/external/.../media/<_ID>` 直接 `ContentResolver.delete`。
 *
 * 删除授权（阶段1关键决策）：
 *  - API 29：依赖 `WRITE_EXTERNAL_STORAGE`；
 *  - API 30+：依赖 `MANAGE_EXTERNAL_STORAGE`（一次性「所有文件访问」授权），授权后静默删除、无弹窗；
 *  - 权限申请由 UI/引擎负责，本层只**如实上报**：缺少权限时返回 `Failed(permissionMissing = true)`，
 *    由引擎暂停任务并引导用户授权（R2：未删除成功的文件绝不出现在「已完成」中）。
 *
 * 断点续传（为什么要和 migratedFileKeys 做差集）：
 *  MediaStore `_ID` 在媒体索引重建后会变化，无法作为稳定标识；而 `MigrationSource.fileKey`
 *  由 `MD5(RELATIVE_PATH|DISPLAY_NAME|SIZE|DATE_MODIFIED)` 生成，是内容/位置稳定的复合键。
 *  重启或进程被杀后，重查 MediaStore → 重新计算 fileKey → 与 Room 中已完成记录差集，
 *  就能精确得到「还剩哪些没迁移」，既不重复上传，也不会漏删（详见 [queryPending]）。
 */
class GalleryRepositoryImpl(context: Context) : GalleryRepository {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    // ==================== 相册列表 ====================

    override suspend fun listAlbums(): List<AlbumInfo> = withContext(Dispatchers.IO) {
        MediaStoreHelper.queryAlbums(resolver).map { aggregate ->
            AlbumInfo(
                albumId = aggregate.albumId,
                albumName = aggregate.albumName,
                itemCount = aggregate.itemCount,
                totalBytes = aggregate.totalBytes,
            )
        }
    }

    // ==================== 待迁移队列 ====================

    /**
     * 查询待迁移队列（图片 + 视频）。
     *
     * 流程：
     *  1. 构造筛选条件（albumScope / selectedAlbumIds，另排除 PENDING、回收站条目）；
     *  2. 分别查询 Images、Video 两个集合（RELATIVE_PATH 为 API 29+ 列，minSdk=29 可直接用）；
     *  3. 合并后**内存排序**：DATE_TAKEN 优先，为 0 时回退 DATE_MODIFIED，保证跨集合口径统一；
     *  4. 为每行生成 fileKey（**必须用 DATE_MODIFIED**，与 FileKeyUtil 契约一致），
     *     再与 [migratedFileKeys] 做差集 —— 这就是断点续传的「去重」实现；
     *  5. 过滤掉没有文件名/大小为 0 的异常行（没有大小基准就无完整性校验，不能删）。
     */
    override suspend fun queryPending(
        settings: AppSettings,
        migratedFileKeys: Set<String>,
    ): List<MigrationSource> = withContext(Dispatchers.IO) {
        val selection = MediaStoreHelper.buildSelection(settings)
        if (selection.skip) {
            // 「仅选中相册」但没有选中任何相册：语义上就是没有待迁移文件
            return@withContext emptyList()
        }

        // 两个集合分别查询后合并（MediaStore 没有跨 Images/Video 的统一「媒体项」视图）
        val rows = ArrayList<MediaStoreHelper.MediaRow>()
        for (kind in MediaKind.values()) {
            rows += MediaStoreHelper.queryMediaRows(resolver, kind, selection)
        }

        // 统一排序（图片用 DATE_TAKEN/DATE_MODIFIED；视频回退 DATE_MODIFIED）
        MediaStoreHelper.sortRows(rows, settings.sortOrder)

        val pending = ArrayList<MigrationSource>(rows.size)
        for (row in rows) {
            // 没有文件名或大小为 0 的行无法参与「上传 + 大小校验 + 删除」闭环，直接跳过
            if (row.displayName.isBlank() || row.size <= 0L) continue
            val source = row.toMigrationSource()
            // 断点续传：已完成（已上传+已校验+已删除）的 fileKey 不再进入队列
            if (source.fileKey in migratedFileKeys) continue
            pending += source
        }
        pending
    }

    /**
     * 与 [queryPending] 同口径的统计。
     *
     * 直接复用 [queryPending] 的结果再聚合，避免维护两套筛选/排序逻辑导致
     * 「预览数量」和「实际迁移数量」不一致（首页展示必须和真实队列完全一致）。
     */
    override suspend fun pendingStats(
        settings: AppSettings,
        migratedFileKeys: Set<String>,
    ): GalleryStats = withContext(Dispatchers.IO) {
        val pending = queryPending(settings, migratedFileKeys)
        var imageCount = 0
        var videoCount = 0
        var totalBytes = 0L
        for (item in pending) {
            when (item.mediaKind) {
                MediaKind.IMAGE -> imageCount += 1
                MediaKind.VIDEO -> videoCount += 1
            }
            totalBytes += item.size
        }
        GalleryStats(
            itemCount = pending.size,
            imageCount = imageCount,
            videoCount = videoCount,
            totalBytes = totalBytes,
        )
    }

    // ==================== 删除本地原图 ====================

    /**
     * 删除本地原图（只在「上传成功 + 完整性校验通过」之后由引擎调用，R2 铁则）。
     *
     * 结果语义：
     *  - `rows > 0` → [DeleteResult.Success]；
     *  - `rows == 0` 且 URI 已查不到 → [DeleteResult.AlreadyGone]（用户已在系统相册手动删除，空间已释放）；
     *  - `rows == 0` 但仍能查到 → [DeleteResult.Failed]（系统未真正删除，绝不能记为完成）；
     *  - `SecurityException / RecoverableSecurityException` → [DeleteResult.Failed] 且
     *    `permissionMissing = true`（引擎应暂停并引导「所有文件访问」授权）；
     *  - 其它异常 → [DeleteResult.Failed]（保留本地文件，记失败表继续下一张，R3 铁则）。
     */
    override suspend fun deleteLocal(source: MigrationSource): DeleteResult = withContext(Dispatchers.IO) {
        try {
            val rows = resolver.delete(source.uri, null, null)
            when {
                rows > 0 -> DeleteResult.Success(rows)
                !existsQuietly(source.uri) -> DeleteResult.AlreadyGone(
                    "MediaStore 中已不存在该媒体（可能已被用户手动删除），视为空间已释放"
                )
                else -> DeleteResult.Failed(
                    "ContentResolver.delete 返回 0 行，但媒体仍存在（可能被系统策略拒绝）"
                )
            }
        } catch (e: RecoverableSecurityException) {
            // API 29+：系统要求用户确认才会授予该媒体项的删除权限（未获 MANAGE_EXTERNAL_STORAGE 时出现）
            DeleteResult.Failed(
                reason = "删除被系统拦截，需要用户授权（所有文件访问/系统确认）：${e.message}",
                permissionMissing = true,
            )
        } catch (e: SecurityException) {
            // 权限丢失（READ/WRITE/MANAGE_EXTERNAL_STORAGE 任一缺失）
            DeleteResult.Failed(
                reason = "删除被系统拒绝（权限缺失）：${e.message}",
                permissionMissing = true,
            )
        } catch (e: Exception) {
            DeleteResult.Failed(
                reason = "删除失败：${e.javaClass.simpleName}: ${e.message}",
                permissionMissing = false,
            )
        }
    }

    // ==================== 存在性判断 ====================

    /**
     * 该 URI 是否仍然存在。
     *
     * **异常语义**：查询失败（如权限被回收）时原样抛出，由引擎/UI 决定如何处理 ——
     * 引擎在 [FileProcessPipeline] 里把异常归类为「检查本地文件失败」并保留本地文件，
     * 不会把它误判为「文件已不存在」而跳过迁移。
     */
    override suspend fun localExists(source: MigrationSource): Boolean = withContext(Dispatchers.IO) {
        resolver.query(source.uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { cursor ->
            cursor.moveToFirst()
        } ?: false
    }

    /**
     * 删除流程内部的「静默」存在性判断：cursor 非空且 moveToFirst() 成功 = 仍存在。
     * 只用于 `delete` 返回 0 行后的二次确认，查询异常按「不存在」处理并记录日志；
     * 这种情况下删除本身通常也已抛出 SecurityException 走权限失败分支，不会误判成功。
     */
    private fun existsQuietly(uri: Uri): Boolean {
        return try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "判断媒体是否存在失败：uri=$uri", e)
            false
        }
    }

    private companion object {
        private const val TAG = "GalleryRepository"
    }
}
