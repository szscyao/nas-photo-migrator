package com.nasphoto.migrator.business.guard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络状态守卫（只读快照，不做任何网络操作）。
 *
 * 为什么用「快照轮询」而不是 `NetworkCallback` 常驻监听：
 *  - 引擎的单文件循环在每个安全点（取下一个文件前）都会主动查询一次，粒度足够；
 *  - 上传中途断网会由协议层抛出 IO 异常，管线捕获后再查询一次快照即可判定
 *    「这是网络类中断 → 暂停任务等待恢复」而不是「文件级失败」（阶段1 文档 2.2 异常表）；
 *  - 避免多一个常驻回调与引擎生命周期纠缠（回调在 Binder 线程，反而增加并发复杂度）。
 *
 * 注意：NAS 通常位于局域网，**不能**用 `NET_CAPABILITY_VALIDATED`（要求公网可达）判断可用性，
 * 否则「连着 WiFi 但无外网」的内网 NAS 场景会被误判为断网。
 */
class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 网络快照 */
    data class Snapshot(
        /** 是否有可用的传输通道（WiFi/移动网络/以太网/VPN） */
        val isAvailable: Boolean,
        /** 当前是否 WiFi（以太网按 WiFi 处理，视为不限流量的局域网） */
        val isWifi: Boolean,
    )

    fun snapshot(): Snapshot {
        val cm = connectivityManager ?: return Snapshot(isAvailable = false, isWifi = false)
        val network = cm.activeNetwork ?: return Snapshot(isAvailable = false, isWifi = false)
        val capabilities = cm.getNetworkCapabilities(network)
            ?: return Snapshot(isAvailable = false, isWifi = false)

        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isOther = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        return Snapshot(
            isAvailable = isWifi || isCellular || isOther,
            isWifi = isWifi,
        )
    }

    /**
     * 当前网络是否满足迁移规则。
     * @param wifiOnly 设置项「仅 WiFi 下迁移」
     */
    fun isSatisfied(wifiOnly: Boolean): Boolean {
        val snapshot = snapshot()
        if (!snapshot.isAvailable) return false
        return !wifiOnly || snapshot.isWifi
    }
}
