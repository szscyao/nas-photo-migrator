package com.nasphoto.migrator.data.config

/**
 * 相册扫描排序方式（决定「先传哪一张」，不改变「一次只处理一张」的铁则）。
 */
enum class MediaSortOrder(val label: String) {
    DATE_TAKEN_ASC("按拍摄时间：最早优先"),
    DATE_TAKEN_DESC("按拍摄时间：最新优先"),
}

/**
 * 相册筛选范围。
 */
enum class AlbumScope(val label: String) {
    ALL("全部相册"),
    SELECTED("仅选中相册"),
}

/**
 * 应用设置（非敏感，明文 SharedPreferences 存储）。
 *
 * 注意：以下行为是**产品铁则**，不在设置项中出现、不可关闭：
 *  - 单线程串行（一次只处理 1 个文件）；
 *  - 上传后必须校验（至少大小一致）才允许删除本地原图；
 *  - 失败绝不删除本地；
 *  - 断点续传（已完成 fileKey 持久化）；
 *  - 本地删除成功后才进入下一张。
 */
data class AppSettings(
    /** 仅 WiFi 下迁移（默认开）：移动网络时自动暂停，等 WiFi 恢复自动继续 */
    val wifiOnly: Boolean = true,
    /** 开启 MD5 完整性校验（默认关；开启后每张多读一遍本地文件 + 远程流，速度下降但更严格） */
    val verifyMd5: Boolean = false,
    /** 扫描排序 */
    val sortOrder: MediaSortOrder = MediaSortOrder.DATE_TAKEN_ASC,
    /** 相册筛选范围 */
    val albumScope: AlbumScope = AlbumScope.ALL,
    /** 选中的相册 bucketId 集合（albumScope=SELECTED 时生效） */
    val selectedAlbumIds: Set<String> = emptySet(),
    /** 低电量自动暂停开关 */
    val lowBatteryPause: Boolean = true,
    /** 低电量阈值（百分比，默认 20，范围 5~50） */
    val lowBatteryThreshold: Int = 20,
    /** 迁移期间持有 PARTIAL_WAKE_LOCK 防止息屏断网 */
    val keepAwake: Boolean = true,
    /** 首次进入首页是否已经引导过权限 */
    val permissionGuideShown: Boolean = false,
)
