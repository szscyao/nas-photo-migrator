package com.nasphoto.migrator.business.model

/**
 * 迁移引擎状态机。
 *
 * 状态迁移：
 *  IDLE → RUNNING（开始） → PAUSED（用户暂停/网络/电量/权限） → RUNNING（恢复）
 *  RUNNING/PAUSED → STOPPING → IDLE（用户停止）
 *  RUNNING → COMPLETED（队列处理完毕）
 */
enum class MigrationStatus {
    /** 空闲（未启动，或已停止） */
    IDLE,

    /** 运行中：正在串行处理单文件闭环 */
    RUNNING,

    /** 已暂停：当前文件不删除本地，恢复后从当前文件重新开始 */
    PAUSED,

    /** 停止中：等待当前单文件操作安全退出（绝不半途中删除） */
    STOPPING,

    /** 全部完成：队列已空 */
    COMPLETED,

    /** 异常终止（如 NAS 认证失败、空间不足等需要用户介入） */
    FAILED,
    ;

    val isActive: Boolean get() = this == RUNNING || this == PAUSED || this == STOPPING
}

/**
 * 暂停原因（展示在首页/通知栏/日志）。
 */
enum class PauseReason(val description: String) {
    NONE(""),
    USER("用户手动暂停"),
    NETWORK_LOST("网络已断开，等待网络恢复"),
    WIFI_REQUIRED("已开启「仅 WiFi 迁移」，当前为移动网络"),
    LOW_BATTERY("低电量自动暂停（充电后自动继续）"),
    STORAGE_PERMISSION_LOST("相册/存储权限丢失，请重新授权"),
    NAS_UNREACHABLE("NAS 连接失败，请检查网络与配置"),
    NAS_AUTH_FAILED("NAS 认证失败，请检查用户名/密码"),
    NAS_NO_SPACE("NAS 空间不足"),
    FOREGROUND_LIMIT("前台服务运行时长到达系统上限，已暂停"),
}
