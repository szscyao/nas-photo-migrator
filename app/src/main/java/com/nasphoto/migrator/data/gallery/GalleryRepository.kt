package com.nasphoto.migrator.data.gallery

import com.nasphoto.migrator.data.config.AppSettings

/**
 * 相册数据仓库（MediaStore 只读 + 删除）。
 *
 * 技术约束：**严格 Scoped Storage** —— 所有访问通过 MediaStore Collections + ContentResolver，
 * 禁止使用 `File("/sdcard/...")` 之类裸路径。
 */
interface GalleryRepository {

    /** 查询本机全部相册（bucket）及数量/大小，供设置页筛选使用 */
    suspend fun listAlbums(): List<AlbumInfo>

    /**
     * 查询「待迁移」文件列表：
     *  1) 按 [AppSettings.albumScope]/[AppSettings.selectedAlbumIds] 过滤相册；
     *  2) 按 [AppSettings.sortOrder] 排序；
     *  3) 排除 [migratedFileKeys]（断点续传：已完成的不再上传）。
     */
    suspend fun queryPending(
        settings: AppSettings,
        migratedFileKeys: Set<String>,
    ): List<MigrationSource>

    /** 与 [queryPending] 同口径的统计（用于首页开始前的预览） */
    suspend fun pendingStats(
        settings: AppSettings,
        migratedFileKeys: Set<String>,
    ): GalleryStats

    /**
     * 删除本地原图（MediaStore delete）。
     * 只在「上传成功 + 完整性校验通过」之后由迁移引擎调用。
     */
    suspend fun deleteLocal(source: MigrationSource): DeleteResult

    /** 该 URI 是否仍然存在（用于删除后的二次确认） */
    suspend fun localExists(source: MigrationSource): Boolean
}
