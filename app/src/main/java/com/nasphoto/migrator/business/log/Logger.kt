package com.nasphoto.migrator.business.log

/**
 * 应用日志门面：写入本地 Room 日志表（纯本地，不上报）。
 *
 * 实现（[AppLogger]）为 fire-and-forget：内部投递到协程作用域，不阻塞调用方，
 * 因此可以在迁移管线的高频节点（单文件各阶段）安全调用。
 */
interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)

    companion object {
        const val TAG_ENGINE = "Engine"
        const val TAG_PROTOCOL = "Protocol"
        const val TAG_GALLERY = "Gallery"
        const val TAG_STORE = "Store"
        const val TAG_SERVICE = "Service"
        const val TAG_CONFIG = "Config"
    }
}
