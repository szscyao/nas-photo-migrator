package com.nasphoto.migrator.business.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本网段 SMB 主机扫描器（任务单 2.1「本网段全扫描」的实现）。
 *
 * 算法：
 *  1) 从当前 **WiFi** 网络的 [LinkProperties] 读取本机 IPv4 地址，推导 /24 网段
 *     （`192.168.1.48` → `192.168.1.1~192.168.1.254`）；也支持 UI 传入手动网段；
 *  2) 对网段内每个 IP 发起 **TCP 445 端口探测**（`Socket.connect`）；
 *  3) 并发 32、单 IP 连接超时 800ms、整轮上限 15 秒（到点不再派发新探测）；
 *  4) 通过 [ScanEvent] 实时上报进度与发现的主机（冷流，collect 才扫描）；
 *  5) **取消即真正中断**：收集协程被取消/页面退出时，`finally` 关闭所有在途 Socket，
 *     阻塞中的 `connect()` 立刻抛异常返回，不会继续占用线程或网络。
 *
 * 线程：`channelFlow` + `flowOn(Dispatchers.IO)`，所有 Socket 探测都在 IO 线程池，
 * 绝不阻塞主线程（硬约束 5）。
 *
 * 说明：本类只做端口探测与网段计算，**不做 SMB 协议交互**（协议交互在 [SmbBrowser] 中，
 * 避免把慢速的 SMB 协商塞进扫描热路径，保证 15 秒内能出结果）。
 */
@Suppress("DEPRECATION") // ConnectivityManager.allNetworks 在 API 31+ 被标记 deprecated，但仍是兼容 WiFi-无外网 场景的最稳方案
class SubnetScanner(context: Context) : NeighborScanner {

    private val appContext: Context = context.applicationContext

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    // ---------------------------------------------------------------------
    // 网络环境
    // ---------------------------------------------------------------------

    override fun isWifiConnected(): Boolean {
        val cm = connectivityManager ?: return false
        // 优先看默认网络；若默认网络不是 WiFi（例如同时开着蜂窝），再遍历所有网络找 WiFi
        val active = cm.activeNetwork
        if (active != null && cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            return true
        }
        return runCatching {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrDefault(false)
    }

    override fun detectLocalRange(): IpRange? {
        val ip = localIpv4Addresses().firstOrNull() ?: return null
        return IpRangeUtil.fromLocalCidr24(ip)
    }

    override fun localIpv4Addresses(): Set<String> {
        val props = wifiLinkProperties() ?: return emptySet()
        val result = LinkedHashSet<String>()
        runCatching {
            for (linkAddress in props.linkAddresses) {
                val address = linkAddress.address
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    address.hostAddress?.let { result.add(it) }
                }
            }
        }
        return result
    }

    /** 取 WiFi 网络的 LinkProperties（拿不到 WiFi 时回退当前活动网络） */
    private fun wifiLinkProperties(): LinkProperties? {
        val cm = connectivityManager ?: return null
        val wifiNetwork: Network? = runCatching {
            val active = cm.activeNetwork
            if (active != null && cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                active
            } else {
                cm.allNetworks.firstOrNull { network ->
                    cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
            }
        }.getOrNull() ?: return runCatching { cm.activeNetwork?.let(cm::getLinkProperties) }.getOrNull()
        return runCatching { cm.getLinkProperties(wifiNetwork) }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // 扫描
    // ---------------------------------------------------------------------

    override fun scan(
        range: IpRange,
        concurrency: Int,
        connectTimeoutMs: Int,
        totalTimeoutMs: Long,
    ): Flow<ScanEvent> = channelFlow {
        // 1) 仅 WiFi 环境允许扫描（移动网络下直接失败，UI 展示「请连接 WiFi 后扫描」）
        if (!isWifiConnected()) {
            send(ScanEvent.Failed("当前未连接 WiFi，请先连接与 NAS 相同的 WiFi 再扫描"))
            return@channelFlow
        }

        // 2) 生成待探测地址（排除本机 IP，避免扫自己）
        val localIps = localIpv4Addresses()
        val targets = IpRangeUtil.expand(
            range = range,
            excludeIps = localIps,
            maxCount = IpRangeUtil.MAX_SCAN_HOSTS,
        )
        if (targets.isEmpty()) {
            send(ScanEvent.Failed("扫描范围无效或没有可探测的地址，请检查网段"))
            return@channelFlow
        }
        send(ScanEvent.Started(range, targets.size))

        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + totalTimeoutMs.coerceAtLeast(1_000L)
        val workerCount = concurrency.coerceIn(1, 128)
        val timeoutMs = connectTimeoutMs.coerceIn(100, 5_000)

        val scanned = AtomicInteger(0)
        val openHosts = Collections.synchronizedList(ArrayList<SmbHost>())
        // 在途 Socket 登记表：取消时统一 close，让阻塞的 connect() 立即返回
        val activeSockets: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
        val taskChannel = Channel<String>(capacity = workerCount)

        try {
            // 生产者：按顺序派发 IP；到 15 秒上限后停止派发（已派发的仍会跑完）
            val producer = launch(Dispatchers.IO) {
                try {
                    for (ip in targets) {
                        if (!isActive) break
                        if (SystemClock.elapsedRealtime() >= deadline) break
                        taskChannel.send(ip)
                    }
                } finally {
                    taskChannel.close()
                }
            }

            // 消费者：固定并发数（默认 32）并行探测 TCP 445
            val workers = List(workerCount) {
                launch(Dispatchers.IO) {
                    // 用 receiveCatching 显式取任务（避免依赖 ReceiveChannel.iterator 的扩展导入）
                    while (true) {
                        val ip = taskChannel.receiveCatching().getOrNull() ?: break
                        if (!isActive) break
                        val open = if (SystemClock.elapsedRealtime() >= deadline) {
                            // 超过整轮上限：不再探测，但仍计入已处理，避免进度永远差一截
                            false
                        } else {
                            probeTcp445(ip, timeoutMs, activeSockets)
                        }
                        val done = scanned.incrementAndGet()
                        if (open) {
                            val host = SmbHost(ip = ip)
                            openHosts.add(host)
                            send(ScanEvent.HostFound(host))
                        }
                        send(ScanEvent.Progress(done, targets.size, openHosts.size))
                    }
                }
            }

            producer.join()
            workers.forEach { it.join() }

            send(
                ScanEvent.Finished(
                    scanned = scanned.get(),
                    total = targets.size,
                    hosts = synchronized(openHosts) { openHosts.toList() },
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            )
        } finally {
            // 取消/异常/正常结束都会走这里：关闭通道 + 关闭所有在途 Socket（真正中断探测）
            runCatching { taskChannel.close() }
            val snapshot = synchronized(activeSockets) { activeSockets.toList() }
            snapshot.forEach { socket -> runCatching { socket.close() } }
            activeSockets.clear()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 探测单个 IP 的 TCP 445 是否开放。
     *
     * 注意：`Socket.connect` 是阻塞调用，本方法只在 IO 线程池的 worker 上运行；
     * 取消时由 [scan] 的 finally 关闭 [socket]，阻塞会立刻以异常返回。
     */
    private fun probeTcp445(ip: String, timeoutMs: Int, activeSockets: MutableSet<Socket>): Boolean {
        val socket = Socket()
        activeSockets.add(socket)
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(ip, NeighborScanner.SMB_PORT), timeoutMs)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 连接被拒 / 超时 / 取消关闭，都视为「该 IP 未开放 445」
            false
        } finally {
            activeSockets.remove(socket)
            runCatching { socket.close() }
        }
    }
}
