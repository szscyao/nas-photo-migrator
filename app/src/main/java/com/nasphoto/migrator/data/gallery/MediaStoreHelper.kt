package com.nasphoto.migrator.data.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.nasphoto.migrator.data.config.AlbumScope
import com.nasphoto.migrator.data.config.AppSettings
import com.nasphoto.migrator.data.config.MediaSortOrder

/**
 * MediaStore 查询辅助（**严格 Scoped Storage**）。
 *
 * 硬约束（阶段1架构 + 平台要求）：
 *  - 只通过 `MediaStore` 集合 Uri + `ContentResolver` 访问媒体，**绝不使用**
 *    `File("/sdcard/...")` 裸路径（Android 10+ 分区存储下既不可靠也会被系统拒绝）；
 *  - 媒体 Uri 形如 `content://media/external/images/media/<id>`，删除/查询都走 [ContentResolver]；
 *  - 读取权限（READ_MEDIA_IMAGES/READ_MEDIA_VIDEO 或 READ_EXTERNAL_STORAGE）由 UI 层申请，
 *    本层不申请权限；遇到 `SecurityException` 原样抛出，交给引擎暂停并引导授权。
 *
 * 列含义速查（对应 MediaStore 内部列）：
 *  - `_ID`           媒体项 ID（不稳定，仅用于拼 URI，断点续传不用它）        → [MediaStore.MediaColumns._ID]
 *  - `DISPLAY_NAME`  文件名（含扩展名）                                        → [MediaStore.MediaColumns.DISPLAY_NAME]
 *  - `SIZE`          字节数，**完整性校验基准**（R2 铁则）                     → [MediaStore.MediaColumns.SIZE]
 *  - `RELATIVE_PATH` 相对路径（如 `DCIM/Camera/`，**API 29+ 才有**）           → [MediaStore.MediaColumns.RELATIVE_PATH]
 *  - `DATE_TAKEN`    拍摄时间（毫秒；图片有，视频常为 0/null）                  → [MediaStore.Images.ImageColumns.DATE_TAKEN]
 *  - `DATE_MODIFIED` 最后修改时间（**秒**！必须 ×1000 才能与毫秒比较）          → [MediaStore.MediaColumns.DATE_MODIFIED]
 *  - `MIME_TYPE`     MIME 类型                                                → [MediaStore.MediaColumns.MIME_TYPE]
 *  - `BUCKET_ID` / `BUCKET_DISPLAY_NAME` 相册（文件夹）ID / 显示名             → Images.ImageColumns / Video.VideoColumns
 */
internal object MediaStoreHelper {

    private const val TAG = "MediaStoreHelper"

    /** 回收站标记（MediaStore.MediaColumns.IS_TRASHED，API 30+；用字面量避免低版本静态字段引用）。 */
    private const val COL_IS_TRASHED = "is_trashed"

    /** 相册（bucket）ID/显示名：MediaColumns 自 API 29 起提供，与 Images/Video 集合通用。 */
    private val COL_BUCKET_ID: String = MediaStore.MediaColumns.BUCKET_ID
    private val COL_BUCKET_DISPLAY_NAME: String = MediaStore.MediaColumns.BUCKET_DISPLAY_NAME

    /**
     * DATE_TAKEN 列名（"datetaken"）：`MediaStore.MediaColumns.DATE_TAKEN`（API 29+，minSdk=29 可用）。
     *
     * 视频的 DATE_TAKEN 兼容说明：
     *  - 视频集合通常有 `datetaken` 列（值常为 0/null），但个别 ROM 视频表缺列，
     *    投影不存在的列会抛 IllegalArgumentException；
     *  - 因此 [queryMediaRows] 对视频先带 DATE_TAKEN 查询，失败时回退到不含该列的投影，
     *    排序按契约回退 DATE_MODIFIED（见 [MediaRow.effectiveTimestampMillis]）。
     * 注意：fileKey 始终使用 DATE_MODIFIED，与 DateTaken 无关。
     */
    private val COL_DATE_TAKEN: String = MediaStore.MediaColumns.DATE_TAKEN

    /** 图片集合：`content://media/external/images/media`（VOLUME_EXTERNAL = 全部分区外部卷）。 */
    val IMAGE_COLLECTION: Uri
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** 视频集合：`content://media/external/video/media`。 */
    val VIDEO_COLLECTION: Uri
        get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** 按媒体类型取对应的 MediaStore 集合 Uri。 */
    fun collectionOf(kind: MediaKind): Uri =
        if (kind == MediaKind.IMAGE) IMAGE_COLLECTION else VIDEO_COLLECTION

    // ---------------- 投影列 ----------------

    /** 两种媒体都需要的公共列。 */
    private val COMMON_COLUMNS = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        // RELATIVE_PATH 是 API 29+ 列；minSdk=29，无需再按 SDK 区分
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.MIME_TYPE,
    )

    private val IMAGE_COLUMNS: Array<String> = COMMON_COLUMNS + arrayOf(
        COL_DATE_TAKEN,
        COL_BUCKET_ID,
        COL_BUCKET_DISPLAY_NAME,
    )

    /** 视频首选投影：带 DATE_TAKEN。 */
    private val VIDEO_COLUMNS: Array<String> = COMMON_COLUMNS + arrayOf(
        COL_DATE_TAKEN,
        COL_BUCKET_ID,
        COL_BUCKET_DISPLAY_NAME,
    )

    /** 视频回退投影：不带 DATE_TAKEN（个别 ROM 视频表无此列）。 */
    private val VIDEO_COLUMNS_WITHOUT_DATE_TAKEN: Array<String> = COMMON_COLUMNS + arrayOf(
        COL_BUCKET_ID,
        COL_BUCKET_DISPLAY_NAME,
    )

    /** 相册聚合只需要 ID/大小/相册列。 */
    private val ALBUM_COLUMNS = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.SIZE,
        COL_BUCKET_ID,
        COL_BUCKET_DISPLAY_NAME,
    )

    /** 一条 MediaStore 媒体行（纯元数据，不含文件路径）。 */
    data class MediaRow(
        val id: Long,
        val displayName: String,
        val size: Long,
        val relativePath: String,
        /** 拍摄时间（毫秒；视频/缺失时为 0） */
        val dateTakenMillis: Long,
        /** 修改时间（**秒**，MediaStore 原样） */
        val dateModifiedSeconds: Long,
        val mimeType: String,
        val bucketId: String,
        val bucketName: String,
        val kind: MediaKind,
    ) {

        /**
         * 排序时间戳（毫秒）：优先 DATE_TAKEN，为 0 时回退 DATE_MODIFIED。
         * 这同时规避了「视频没有 DATE_TAKEN」「部分图片 DATE_TAKEN 缺失」两类设备差异。
         */
        val effectiveTimestampMillis: Long
            get() = if (dateTakenMillis > 0L) dateTakenMillis else dateModifiedSeconds * 1000L

        /** 转成引擎使用的待迁移项，同时生成断点续传 fileKey。 */
        fun toMigrationSource(): MigrationSource {
            val collection = collectionOf(kind)
            return MigrationSource(
                // fileKey 必须用 DATE_MODIFIED（秒），不能用 DATE_TAKEN：
                // ① 契约规定；② DATE_TAKEN 可能为 0、且用户旋转/编辑后拍摄时间不变而修改时间会变，
                //    用修改时间能更准确识别「文件内容可能已变化」。
                fileKey = FileKeyUtil.build(relativePath, displayName, size, dateModifiedSeconds),
                uri = ContentUris.withAppendedId(collection, id),
                displayName = displayName,
                size = size,
                relativePath = relativePath,
                dateTakenMillis = dateTakenMillis,
                mimeType = mimeType,
                mediaKind = kind,
                albumId = bucketId,
                albumName = bucketName,
            )
        }
    }

    /** 相册聚合中间结果。 */
    data class AlbumAggregate(
        val albumId: String,
        var albumName: String,
        var itemCount: Int = 0,
        var totalBytes: Long = 0L,
    )

    /** 查询筛选条件。 */
    data class MediaSelection(
        val clause: String?,
        val args: Array<String>?,
        /** true = 条件必然无结果（「仅选中相册」但一个都没选），调用方直接返回空，避免非法 SQL */
        val skip: Boolean = false,
    )

    // ---------------- 条件构造 ----------------

    /** 公共基础过滤：排除系统 PENDING（写入中）与回收站（API 30+）条目，避免删到未就绪文件。 */
    private fun baseFilterParts(): List<String> = buildList {
        add("${MediaStore.MediaColumns.IS_PENDING}=0")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add("$COL_IS_TRASHED=0")
        }
    }

    /**
     * 结合用户设置构造查询条件：
     *  - albumScope=SELECTED 时追加 `bucket_id IN (?,?,...)`；
     *  - 选中集合为空 → [MediaSelection.skip]=true（「仅选中相册」但没选任何相册 = 没文件可迁移）。
     */
    fun buildSelection(settings: AppSettings): MediaSelection {
        val parts = baseFilterParts().toMutableList()
        val args = mutableListOf<String>()

        if (settings.albumScope == AlbumScope.SELECTED) {
            val ids = settings.selectedAlbumIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (ids.isEmpty()) {
                return MediaSelection(clause = null, args = null, skip = true)
            }
            val placeholders = ids.joinToString(separator = ",") { "?" }
            parts += "$COL_BUCKET_ID IN ($placeholders)"
            args += ids
        }

        return MediaSelection(
            clause = parts.joinToString(separator = " AND "),
            args = args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            skip = false,
        )
    }

    /** 仅基础过滤（相册列表统计用，不做相册筛选）。 */
    private fun buildBaseSelection(): MediaSelection =
        MediaSelection(clause = baseFilterParts().joinToString(" AND "), args = null)

    // ---------------- 查询 ----------------

    /**
     * 查询某一类媒体的全部行（调用方须确认 [MediaSelection.skip] 为 false）。
     *
     * 视频优先带 DATE_TAKEN 查询；个别 ROM 视频集合无该列会抛 IllegalArgumentException，
     * 此时自动回退「不带 DATE_TAKEN」的投影（排序回退 DATE_MODIFIED，功能不受影响）。
     */
    fun queryMediaRows(
        resolver: ContentResolver,
        kind: MediaKind,
        selection: MediaSelection,
    ): List<MediaRow> {
        val collection = collectionOf(kind)
        val preferredColumns = if (kind == MediaKind.IMAGE) IMAGE_COLUMNS else VIDEO_COLUMNS
        return try {
            queryRows(resolver, collection, preferredColumns, selection) { cursor ->
                readMediaRow(cursor, kind)
            }
        } catch (e: IllegalArgumentException) {
            if (kind == MediaKind.IMAGE) throw e
            Log.w(TAG, "视频集合不支持 DATE_TAKEN 列，回退为 DATE_MODIFIED 排序依据", e)
            queryRows(resolver, collection, VIDEO_COLUMNS_WITHOUT_DATE_TAKEN, selection) { cursor ->
                readMediaRow(cursor, kind)
            }
        }
    }

    /** 统计相册（合并图片/视频两个集合，按 BUCKET_ID 聚合）。 */
    fun queryAlbums(resolver: ContentResolver): List<AlbumAggregate> {
        val result = LinkedHashMap<String, AlbumAggregate>()
        val selection = buildBaseSelection()

        for (kind in MediaKind.values()) {
            val collection = collectionOf(kind)
            queryRows(resolver, collection, ALBUM_COLUMNS, selection) { cursor ->
                val bucketId = readString(cursor, COL_BUCKET_ID).trim()
                val bucketName = readString(cursor, COL_BUCKET_DISPLAY_NAME).trim()
                val size = readLong(cursor, MediaStore.MediaColumns.SIZE)
                if (bucketId.isEmpty()) {
                    // 没有 bucket（极少数异常行）无法在设置页展示/筛选，跳过
                    null
                } else {
                    val aggregate = result.getOrPut(bucketId) {
                        AlbumAggregate(
                            albumId = bucketId,
                            albumName = bucketName.ifEmpty { bucketId },
                        )
                    }
                    // 图片与视频可能属于同一文件夹（bucket_id 相同，按媒体目录哈希计算）；
                    // 名称以第一个非空 BUCKET_DISPLAY_NAME 为准（先查到的图片优先）。
                    if (bucketName.isNotEmpty() &&
                        (aggregate.albumName.isEmpty() || aggregate.albumName == aggregate.albumId)
                    ) {
                        aggregate.albumName = bucketName
                    }
                    aggregate.itemCount += 1
                    aggregate.totalBytes += size
                    aggregate
                }
            }
        }

        return result.values.sortedWith(
            compareByDescending<AlbumAggregate> { it.itemCount }
                .thenBy { it.albumName }
        )
    }

    /**
     * 排序（**内存排序**，不依赖 SQL ORDER BY）：
     *  - 时间优先 DATE_TAKEN，缺失回退 DATE_MODIFIED（跨图片/视频集合统一口径）；
     *  - DATE_TAKEN_ASC / DATE_TAKEN_DESC；
     *  - 时间相同用相对路径/文件名/大小做稳定排序，保证多次查询顺序一致（串行迁移的可复现性）。
     */
    fun sortRows(rows: MutableList<MediaRow>, order: MediaSortOrder) {
        val stableOrder = compareBy<MediaRow>(
            { it.relativePath },
            { it.displayName },
            { it.size },
            { it.id },
        )
        val comparator = Comparator<MediaRow> { a, b ->
            val byTime = a.effectiveTimestampMillis.compareTo(b.effectiveTimestampMillis)
            when {
                byTime != 0 -> if (order == MediaSortOrder.DATE_TAKEN_ASC) byTime else -byTime
                else -> stableOrder.compare(a, b)
            }
        }
        rows.sortWith(comparator)
    }

    // ---------------- 内部通用查询 ----------------

    /**
     * 通用游标查询：统一做 null / moveToFirst 保护、`try/finally` 关闭游标。
     *
     * 异常策略：查询/读游标异常（权限缺失 [SecurityException]、不同 ROM 的列缺失
     * [IllegalArgumentException] 等）记录日志后**原样上抛**，由上层（引擎/UI）决定
     * 「暂停并引导授权」或「展示错误」。这里不吞异常，避免把「读不到相册」伪装成
     * 「没有待迁移文件」导致任务静默显示完成。
     */
    private fun <T> queryRows(
        resolver: ContentResolver,
        collection: Uri,
        columns: Array<String>,
        selection: MediaSelection,
        mapper: (Cursor) -> T?,
    ): List<T> {
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                collection,
                columns,
                selection.clause,
                selection.args,
                // 不需要 SQL 排序：图片/视频要合并后按「拍摄时间回退修改时间」统一排序（见 sortRows）
                null,
            )
            if (cursor == null || !cursor.moveToFirst()) return emptyList()
            val result = ArrayList<T>(cursor.count.coerceAtLeast(0))
            do {
                val item = mapper(cursor)
                if (item != null) result.add(item)
            } while (cursor.moveToNext())
            return result
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore 查询失败（collection=$collection, projection=${columns.joinToString()})", e)
            throw e
        } finally {
            cursor?.close()
        }
    }

    private fun readMediaRow(cursor: Cursor, kind: MediaKind): MediaRow {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
        val displayName = readString(cursor, MediaStore.MediaColumns.DISPLAY_NAME)
        val size = readLong(cursor, MediaStore.MediaColumns.SIZE)
        val relativePath = readString(cursor, MediaStore.MediaColumns.RELATIVE_PATH)
        val dateModified = readLong(cursor, MediaStore.MediaColumns.DATE_MODIFIED)
        val mimeType = readString(cursor, MediaStore.MediaColumns.MIME_TYPE)
        // 投影里始终包含 DATE_TAKEN（图片必有；视频若有则读取，缺失时列索引为 -1 → 按 0 处理）
        val dateTaken = readLong(cursor, COL_DATE_TAKEN)
        val bucketId = readString(cursor, COL_BUCKET_ID).trim()
        val bucketName = readString(cursor, COL_BUCKET_DISPLAY_NAME).trim()
        return MediaRow(
            id = id,
            displayName = displayName,
            size = size,
            relativePath = relativePath,
            dateTakenMillis = dateTaken,
            dateModifiedSeconds = dateModified,
            mimeType = mimeType,
            bucketId = bucketId,
            bucketName = bucketName,
            kind = kind,
        )
    }

    /** 安全读取字符串列：列不存在（-1）或值为 NULL 时返回空串，绝不抛异常。 */
    private fun readString(cursor: Cursor, column: String): String {
        val index = cursor.getColumnIndex(column)
        if (index < 0 || cursor.isNull(index)) return ""
        return cursor.getString(index).orEmpty()
    }

    /** 安全读取数值列：列不存在或 NULL 时返回 0。 */
    private fun readLong(cursor: Cursor, column: String): Long {
        val index = cursor.getColumnIndex(column)
        if (index < 0 || cursor.isNull(index)) return 0L
        return cursor.getLong(index)
    }
}
