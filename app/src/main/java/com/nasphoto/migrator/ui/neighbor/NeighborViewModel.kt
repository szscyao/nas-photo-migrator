package com.nasphoto.migrator.ui.neighbor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nasphoto.migrator.business.discovery.IpRange
import com.nasphoto.migrator.business.discovery.IpRangeUtil
import com.nasphoto.migrator.business.discovery.ScanEvent
import com.nasphoto.migrator.business.discovery.SmbBrowseException
import com.nasphoto.migrator.business.discovery.SmbCredentials
import com.nasphoto.migrator.business.discovery.SmbEntry
import com.nasphoto.migrator.business.discovery.SmbFailureKind
import com.nasphoto.migrator.business.discovery.SmbHost
import com.nasphoto.migrator.data.neighbor.NeighborConfigFactory
import com.nasphoto.migrator.data.neighbor.NeighborCredentials
import com.nasphoto.migrator.data.neighbor.SavedDevice
import com.nasphoto.migrator.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// ============================================================================
// 网络邻居向导 —— 状态模型
// ============================================================================

/** 向导内的三个层级（系统返回键逐级回退；SCAN 再返回由导航弹出回首页） */
enum class NeighborStep {
    /** 扫描页：我的设备 + 扫描进度 + 设备列表 */
    SCAN,

    /** 共享文件夹列表 */
    SHARES,

    /** 目录浏览（面包屑逐级进入） */
    BROWSE,
}

/**
 * UI 层的设备条目（合并「我的设备」与「本次扫描发现」）。
 *
 * [fromSaved] = true 表示来自 savedDeviceRepository（先展示、带「已保存」标记、可删除）；
 * false 表示本次扫描新发现（不重复展示在我的设备区）。
 */
data class NeighborDeviceItem(
    val ip: String,
    val hostName: String? = null,
    val shareCount: Int? = null,
    val needsAuth: Boolean = false,
    val fromSaved: Boolean = false,
    val lastUsedAt: Long = 0L,
) {
    val displayName: String
        get() = hostName?.trim()?.takeIf { it.isNotEmpty() } ?: ip
}

/**
 * 认证弹窗状态。
 *
 * 注意：**不保存密码**——用户名仅作为再次输入时的建议值（便于用户改正），
 * 密码只在 AuthDialog 的本地 Compose 状态中短暂存在，确认后立即写入加密仓库。
 */
data class AuthDialogState(
    val host: String,
    val deviceName: String,
    val error: String? = null,
    val submitting: Boolean = false,
    val suggestedUsername: String = "",
)

/**
 * 网络邻居向导 UI 状态（单一快照，ViewModel 唯一输出）。
 */
data class NeighborUiState(
    val step: NeighborStep = NeighborStep.SCAN,

    // ---------------- 环境 ----------------
    val wifiConnected: Boolean = false,

    // ---------------- 设备（我的设备 + 扫描发现，按 IP 合并） ----------------
    val devices: List<NeighborDeviceItem> = emptyList(),
    val pendingDeleteDevice: NeighborDeviceItem? = null,

    // ---------------- 扫描 ----------------
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val scanTotal: Int = 0,
    val scanOpenCount: Int = 0,
    val scanRange: IpRange? = null,
    val scanElapsedMs: Long = 0L,
    val scanFinished: Boolean = false,
    /** 扫描错误/环境提示（已经是人话，不含异常堆栈） */
    val scanError: String? = null,

    // ---------------- 手动网段 ----------------
    val showManualRangeDialog: Boolean = false,
    val manualRangeStart: String = "",
    val manualRangeEnd: String = "",
    val manualRangeError: String? = null,

    // ---------------- 共享列表 ----------------
    val currentDevice: NeighborDeviceItem? = null,
    val shares: List<String> = emptyList(),
    val sharesLoading: Boolean = false,
    val sharesError: String? = null,

    // ---------------- 目录浏览 ----------------
    val currentShare: String? = null,
    /** 共享内相对路径；"" 表示共享根目录，多级如 "DCIM/Camera" */
    val currentPath: String = "",
    val entries: List<SmbEntry> = emptyList(),
    val entriesLoading: Boolean = false,
    val entriesError: String? = null,

    // ---------------- 认证 / 生成配置 ----------------
    val authDialog: AuthDialogState? = null,
    val savingConfig: Boolean = false,
    /** 非空 = 已生成配置，UI 弹出成功提示并提供「返回首页」 */
    val createdConfigName: String? = null,

    /** 一次性 Snackbar 提示 */
    val message: String? = null,
) {
    /** 「我的设备」：先展示、带已保存标记、可删除（按上次使用时间倒序） */
    val savedDevices: List<NeighborDeviceItem>
        get() = devices.filter { it.fromSaved }.sortedByDescending { it.lastUsedAt }

    /** 「本次扫描发现」：扫描新发现、尚未归入我的设备的条目（按 IP 数字顺序） */
    val scannedDevices: List<NeighborDeviceItem>
        get() = devices.filter { !it.fromSaved }.sortedBy { ipSortKey(it.ip) }

    val hasAnyDevice: Boolean get() = devices.isNotEmpty()
}

/** IPv4 → 无符号排序键（避免 192.x 解析成负数后排序错乱） */
private fun ipSortKey(ip: String): Long =
    (IpRangeUtil.parseIpv4(ip)?.toLong() ?: 0L) and 0xFFFFFFFFL

// ============================================================================
// 网络邻居向导 ViewModel
// ============================================================================

/**
 * 「网络邻居（本网段全扫描）」向导 ViewModel。
 *
 * 数据来源（只依赖 AppContainer 暴露的接口）：
 *  - neighborScanner：/24 网段 TCP 445 扫描（冷流，collect 才扫描；取消 collect 立即中断 Socket）
 *  - smbBrowser：SMB 只读浏览（probe 补全设备名/共享数、listShares、listDirectory）
 *  - savedDeviceRepository：「我的设备」持久化（先展示、再被扫描结果补充）
 *  - neighborCredentialStore：SMB 凭据加密存储（AUTH_REQUIRED 时弹窗输入并保存）
 *  - nasConfigRepository：选定目录后生成标准 NasConfig 并设为激活
 *
 * 铁则约束：本功能只读局域网（扫描 + 浏览），不参与迁移引擎的「上传→校验→删除本地」闭环。
 */
class NeighborViewModel(private val container: AppContainer) : ViewModel() {

    private val scanner = container.neighborScanner
    private val smbBrowser = container.smbBrowser
    private val savedDeviceRepository = container.savedDeviceRepository
    private val credentialStore = container.neighborCredentialStore
    private val nasConfigRepository = container.nasConfigRepository

    private val _uiState = MutableStateFlow(NeighborUiState())
    val uiState: StateFlow<NeighborUiState> = _uiState.asStateFlow()

    /** 扫描任务（取消时真正中断底层 Socket 探测） */
    private var scanJob: Job? = null

    /** 共享/目录加载任务（快速切换目录时取消上一个，避免结果乱序） */
    private var loadJob: Job? = null

    /** 是否已经发起过一轮扫描（用于 onResume 时避免重复自动扫描） */
    private var scanStartedOnce = false

    /** 认证成功后需要重试的挂起操作（不进入 UI 状态，避免泄漏） */
    private var pendingAction: PendingAction? = null

    /** 内存中的凭据缓存（真正落盘在加密仓库；UI 状态中不出现密码） */
    private val credentialsCache = mutableMapOf<String, SmbCredentials>()

    /** 已发过 probe 的 IP（每个 IP 只补全一次） */
    private val probedIps = mutableSetOf<String>()

    /** 已写入「我的设备」的 IP（避免扫描过程中重复 upsert） */
    private val persistedIps = mutableSetOf<String>()

    /** 已尝试补全设备名的 IP（登录后补全，避免重复请求） */
    private val hostNameProbedIps = mutableSetOf<String>()

    /** probe 并发限制 4（任务单：不阻塞设备列表展示） */
    private val probeSemaphore = Semaphore(4)

    init {
        // 1) 「我的设备」先立即展示（不等扫描）
        viewModelScope.launch {
            savedDeviceRepository.devicesFlow.collect { saved -> applySavedDevices(saved) }
        }
        // 2) WiFi 可用则自动开始扫描
        autoScanOnEnter()
    }

    // =========================================================================
    // 设备列表
    // =========================================================================

    /**
     * 合并「我的设备」流水：只增补/更新元数据，不因流水缺失而移除本次扫描发现的条目；
     * 已在列表中的扫描条目不会被改成 fromSaved（避免扫描过程中列表跳区）。
     */
    private fun applySavedDevices(saved: List<SavedDevice>) {
        _uiState.update { state ->
            val byIp = LinkedHashMap<String, NeighborDeviceItem>()
            state.devices.forEach { byIp[it.ip] = it }
            saved.forEach { device ->
                val existing = byIp[device.ip]
                byIp[device.ip] = if (existing == null) {
                    NeighborDeviceItem(
                        ip = device.ip,
                        hostName = device.hostName,
                        shareCount = device.shareCount,
                        fromSaved = true,
                        lastUsedAt = device.lastUsedAt,
                    )
                } else {
                    existing.copy(
                        hostName = existing.hostName ?: device.hostName,
                        shareCount = existing.shareCount ?: device.shareCount,
                        lastUsedAt = maxOf(existing.lastUsedAt, device.lastUsedAt),
                    )
                }
            }
            state.copy(devices = byIp.values.toList())
        }
    }

    /** 扫描发现的主机合并进设备列表（按 IP 去重） */
    private fun mergeHost(host: SmbHost) {
        _uiState.update { state ->
            val existing = state.devices.firstOrNull { it.ip == host.ip }
            if (existing == null) {
                state.copy(
                    devices = state.devices + NeighborDeviceItem(
                        ip = host.ip,
                        hostName = host.hostName,
                        shareCount = host.shareCount,
                        needsAuth = host.needsAuth,
                        fromSaved = host.fromSaved,
                        lastUsedAt = host.lastUsedAt,
                    ),
                )
            } else {
                state.copy(
                    devices = state.devices.map { item ->
                        if (item.ip != host.ip) {
                            item
                        } else {
                            item.copy(
                                hostName = item.hostName ?: host.hostName,
                                shareCount = item.shareCount ?: host.shareCount,
                                needsAuth = item.needsAuth || host.needsAuth,
                                lastUsedAt = maxOf(item.lastUsedAt, host.lastUsedAt),
                            )
                        }
                    },
                )
            }
        }
    }

    /** 更新指定设备的元数据（probe / 访问成功 / 认证结果） */
    private fun updateDevice(
        ip: String,
        hostName: String? = null,
        shareCount: Int? = null,
        needsAuth: Boolean? = null,
    ) {
        _uiState.update { state ->
            val devices = state.devices.map { item ->
                if (item.ip != ip) {
                    item
                } else {
                    item.copy(
                        hostName = item.hostName ?: hostName,
                        shareCount = shareCount ?: item.shareCount,
                        needsAuth = needsAuth ?: item.needsAuth,
                        lastUsedAt = System.currentTimeMillis(),
                    )
                }
            }
            state.copy(
                devices = devices,
                currentDevice = state.currentDevice?.let { current ->
                    if (current.ip != ip) current else current.copy(
                        hostName = current.hostName ?: hostName,
                        shareCount = shareCount ?: current.shareCount,
                        needsAuth = needsAuth ?: current.needsAuth,
                    )
                },
            )
        }
    }

    /**
     * 扫描发现的设备写入「我的设备」。
     *
     * 用 touch 而不是 upsert：它的 SQL 用 COALESCE 保留已有设备名/共享数（不会用空值覆盖），
     * 且 IP 不存在时会自动补插一条。
     */
    private fun persistDiscovered(ip: String) {
        if (!persistedIps.add(ip)) return
        viewModelScope.launch {
            runCatchingCancellable { savedDeviceRepository.touch(ip, null) }
        }
    }

    /**
     * 异步补全设备名/共享数（限并发 4，不阻塞列表）。
     * 失败静默（设备名拿不到时列表回退显示 IP），不影响扫描结果展示。
     */
    private fun enrichHost(ip: String) {
        if (!probedIps.add(ip)) return
        viewModelScope.launch {
            probeSemaphore.withPermit {
                val result = try {
                    smbBrowser.probe(ip)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    null
                }
                if (result != null) {
                    val name = result.hostName?.trim()?.takeIf { it.isNotEmpty() }
                    val shareCount = result.shares.size.takeIf { !result.needsAuth }
                    updateDevice(
                        ip = ip,
                        hostName = name,
                        shareCount = shareCount,
                        // 匿名探测被拒 → 标记需要账号密码；成功匿名访问则不动已有标记
                        needsAuth = if (result.needsAuth) true else null,
                    )
                    persistProbed(ip, name)
                }
            }
        }
    }

    /** 把探测到的设备名写回「我的设备」（touch 的 COALESCE 不会清掉已有名称/共享数） */
    private fun persistProbed(ip: String, hostName: String?) {
        if (hostName.isNullOrBlank()) return
        viewModelScope.launch {
            runCatchingCancellable { savedDeviceRepository.touch(ip, hostName) }
        }
    }

    // =========================================================================
    // 扫描
    // =========================================================================

    /** 进入页面：检测 WiFi 与网段，可用则自动扫描 */
    private fun autoScanOnEnter() {
        viewModelScope.launch {
            val environment = withContext(Dispatchers.IO) {
                val wifi = runCatching { scanner.isWifiConnected() }.getOrDefault(false)
                val range = if (wifi) runCatching { scanner.detectLocalRange() }.getOrNull() else null
                wifi to range
            }
            val wifi = environment.first
            val range = environment.second
            if (!wifi) {
                _uiState.update {
                    it.copy(
                        wifiConnected = false,
                        scanError = "当前未连接 WiFi，请先连接与 NAS 相同的 WiFi 再扫描",
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(wifiConnected = true) }
            if (range == null) {
                _uiState.update {
                    it.copy(scanError = "无法识别当前 WiFi 网段，请使用「手动网段」指定扫描范围")
                }
                return@launch
            }
            // onResume 可能已抢先发起扫描，避免重复启动
            if (!scanStartedOnce) {
                startScan(range)
            }
        }
    }

    /** 开始扫描（自动网段 / 手动网段 / 重新扫描共用） */
    fun startScan(range: IpRange) {
        scanJob?.cancel()
        // WiFi 校验：移动网络下不允许扫描
        val wifi = runCatching { scanner.isWifiConnected() }.getOrDefault(false)
        if (!wifi) {
            _uiState.update {
                it.copy(
                    wifiConnected = false,
                    scanning = false,
                    scanError = "当前未连接 WiFi，请先连接与 NAS 相同的 WiFi 再扫描",
                )
            }
            return
        }
        scanStartedOnce = true
        _uiState.update { state ->
            state.copy(
                wifiConnected = true,
                scanning = true,
                scanned = 0,
                scanTotal = 0,
                scanOpenCount = 0,
                scanRange = range,
                scanElapsedMs = 0L,
                scanFinished = false,
                scanError = null,
                // 清空上次「扫描发现」，保留我的设备
                devices = state.devices.filter { it.fromSaved },
            )
        }
        scanJob = viewModelScope.launch {
            try {
                scanner.scan(range).collect { event -> handleScanEvent(event) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        scanning = false,
                        scanError = "扫描失败：${t.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    private fun handleScanEvent(event: ScanEvent) {
        when (event) {
            is ScanEvent.Started -> _uiState.update {
                it.copy(scanTotal = event.total, scanRange = event.range, scanError = null)
            }

            is ScanEvent.Progress -> _uiState.update {
                it.copy(
                    scanned = event.scanned,
                    scanTotal = event.total,
                    scanOpenCount = event.openCount,
                )
            }

            is ScanEvent.HostFound -> {
                mergeHost(event.host)
                persistDiscovered(event.host.ip)
                enrichHost(event.host.ip)
            }

            is ScanEvent.Finished -> {
                _uiState.update {
                    it.copy(
                        scanning = false,
                        scanFinished = true,
                        scanned = event.scanned,
                        scanTotal = event.total,
                        scanOpenCount = event.hosts.size,
                        scanElapsedMs = event.elapsedMs,
                    )
                }
                event.hosts.forEach { host ->
                    mergeHost(host)
                    persistDiscovered(host.ip)
                    enrichHost(host.ip)
                }
            }

            is ScanEvent.Failed -> _uiState.update {
                it.copy(
                    scanning = false,
                    scanFinished = true,
                    scanError = event.message,
                )
            }
        }
    }

    /** 取消扫描：取消 collect 会关闭底层所有在途 Socket（真正中断，不只是停 UI） */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.update {
            it.copy(
                scanning = false,
                scanFinished = true,
                message = "已取消扫描",
            )
        }
    }

    /** 「重新扫描」：优先上次网段，否则重新识别 */
    fun rescan() {
        val state = _uiState.value
        val range = state.scanRange ?: runCatching { scanner.detectLocalRange() }.getOrNull()
        if (range == null) {
            openManualRangeDialog()
            return
        }
        startScan(range)
    }

    /** 页面回到前台：刷新 WiFi 状态；若刚连上 WiFi 且还没扫过则自动开始 */
    fun onResume() {
        viewModelScope.launch {
            val environment = withContext(Dispatchers.IO) {
                val wifi = runCatching { scanner.isWifiConnected() }.getOrDefault(false)
                val range = if (wifi) runCatching { scanner.detectLocalRange() }.getOrNull() else null
                wifi to range
            }
            val wifi = environment.first
            val range = environment.second
            val state = _uiState.value
            _uiState.update { it.copy(wifiConnected = wifi) }
            if (wifi && state.scanError != null && !state.scanning) {
                _uiState.update { it.copy(scanError = null) }
            }
            if (wifi && !scanStartedOnce && range != null) {
                startScan(range)
            }
        }
    }

    // =========================================================================
    // 手动网段
    // =========================================================================

    fun openManualRangeDialog() {
        val current = _uiState.value.scanRange
        val detected = current ?: runCatching { scanner.detectLocalRange() }.getOrNull()
        _uiState.update {
            it.copy(
                showManualRangeDialog = true,
                manualRangeStart = detected?.startIp ?: "192.168.1.1",
                manualRangeEnd = detected?.endIp ?: "192.168.1.254",
                manualRangeError = null,
            )
        }
    }

    fun setManualRangeStart(text: String) {
        _uiState.update { it.copy(manualRangeStart = text, manualRangeError = null) }
    }

    fun setManualRangeEnd(text: String) {
        _uiState.update { it.copy(manualRangeEnd = text, manualRangeError = null) }
    }

    fun dismissManualRangeDialog() {
        _uiState.update { it.copy(showManualRangeDialog = false, manualRangeError = null) }
    }

    fun confirmManualRange() {
        val state = _uiState.value
        // 复用 business/discovery 的纯函数校验（中文错误文案可直接展示）
        val error = IpRangeUtil.validateManualRange(state.manualRangeStart, state.manualRangeEnd)
        if (error != null) {
            _uiState.update { it.copy(manualRangeError = error) }
            return
        }
        _uiState.update { it.copy(showManualRangeDialog = false, manualRangeError = null) }
        startScan(IpRange(state.manualRangeStart.trim(), state.manualRangeEnd.trim()))
    }

    // =========================================================================
    // 我的设备：删除
    // =========================================================================

    fun requestDeleteDevice(device: NeighborDeviceItem) {
        _uiState.update { it.copy(pendingDeleteDevice = device) }
    }

    fun cancelDeleteDevice() {
        _uiState.update { it.copy(pendingDeleteDevice = null) }
    }

    fun confirmDeleteDevice() {
        val device = _uiState.value.pendingDeleteDevice ?: return
        viewModelScope.launch {
            val result = runCatchingCancellable {
                savedDeviceRepository.delete(device.ip)
                // 忘记设备时一并清理该主机的加密凭据（避免残留）
                credentialStore.remove(device.ip)
            }
            credentialsCache.remove(device.ip)
            _uiState.update { state ->
                state.copy(
                    pendingDeleteDevice = null,
                    devices = state.devices.filterNot { it.ip == device.ip },
                    message = if (result.isSuccess) {
                        "已删除设备「${device.displayName}」"
                    } else {
                        "删除失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    },
                )
            }
        }
    }

    // =========================================================================
    // 共享列表 / 目录浏览
    // =========================================================================

    /** 点击设备 → 共享列表 */
    fun openDevice(device: NeighborDeviceItem) {
        pendingAction = null
        _uiState.update {
            it.copy(
                step = NeighborStep.SHARES,
                currentDevice = device,
                shares = emptyList(),
                sharesLoading = true,
                sharesError = null,
                currentShare = null,
                currentPath = "",
                entries = emptyList(),
                entriesError = null,
                authDialog = null,
            )
        }
        loadShares(device)
    }

    private fun loadShares(device: NeighborDeviceItem) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(sharesLoading = true, sharesError = null) }
            val credentials = credentialsFor(device.ip)
            val result = runCatchingCancellable {
                smbBrowser.listShares(device.ip, credentials ?: SmbCredentials.ANONYMOUS)
            }
            val shares = result.getOrNull()
            if (shares != null) {
                val sorted = shares.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    .sortedBy { it.lowercase() }
                _uiState.update {
                    it.copy(sharesLoading = false, shares = sorted, sharesError = null)
                }
                onAccessSuccess(device, sorted.size)
            } else {
                handleBrowseFailure(
                    throwable = result.exceptionOrNull(),
                    device = device,
                    retryAction = PendingAction.LoadShares(device),
                )
            }
        }
    }

    /** 点击共享 → 浏览共享根目录 */
    fun openShare(share: String) {
        val device = _uiState.value.currentDevice ?: return
        loadDirectory(device, share, "")
    }

    /** 点进子目录 */
    fun enterDirectory(name: String) {
        val state = _uiState.value
        val device = state.currentDevice ?: return
        val share = state.currentShare ?: return
        val target = if (state.currentPath.isEmpty()) name else "${state.currentPath}/$name"
        loadDirectory(device, share, target)
    }

    /** 面包屑：跳回任意层级（"" = 共享根） */
    fun navigateToPath(path: String) {
        val state = _uiState.value
        val device = state.currentDevice ?: return
        val share = state.currentShare ?: return
        loadDirectory(device, share, path)
    }

    /** 「上一级」/系统返回：目录内回退，共享根再回共享列表 */
    fun navigateUp() {
        val state = _uiState.value
        when (state.step) {
            NeighborStep.BROWSE -> {
                if (state.currentPath.isEmpty()) {
                    backToShares()
                } else {
                    val device = state.currentDevice ?: return
                    val share = state.currentShare ?: return
                    loadDirectory(device, share, parentPath(state.currentPath))
                }
            }

            NeighborStep.SHARES -> backToScan()
            NeighborStep.SCAN -> Unit
        }
    }

    /** 回到扫描页（清空浏览上下文；设备列表保留） */
    fun backToScan() {
        loadJob?.cancel()
        pendingAction = null
        _uiState.update {
            it.copy(
                step = NeighborStep.SCAN,
                currentDevice = null,
                shares = emptyList(),
                sharesLoading = false,
                sharesError = null,
                currentShare = null,
                currentPath = "",
                entries = emptyList(),
                entriesLoading = false,
                entriesError = null,
                authDialog = null,
            )
        }
    }

    /** 回到共享列表（共享列表已缓存时直接展示） */
    fun backToShares() {
        loadJob?.cancel()
        pendingAction = null
        val device = _uiState.value.currentDevice
        _uiState.update {
            it.copy(
                step = NeighborStep.SHARES,
                currentShare = null,
                currentPath = "",
                entries = emptyList(),
                entriesLoading = false,
                entriesError = null,
                authDialog = null,
            )
        }
        if (device != null && _uiState.value.shares.isEmpty() && !_uiState.value.sharesLoading) {
            loadShares(device)
        }
    }

    private fun loadDirectory(device: NeighborDeviceItem, share: String, path: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    step = NeighborStep.BROWSE,
                    currentShare = share,
                    currentPath = path,
                    entriesLoading = true,
                    entriesError = null,
                )
            }
            val credentials = credentialsFor(device.ip)
            val result = runCatchingCancellable {
                smbBrowser.listDirectory(device.ip, share, path, credentials ?: SmbCredentials.ANONYMOUS)
            }
            val entries = result.getOrNull()
            if (entries != null) {
                // 文件夹优先、名称升序；文件只展示不可进入
                val sorted = entries.sortedWith(
                    compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
                )
                _uiState.update {
                    it.copy(entriesLoading = false, entries = sorted, entriesError = null)
                }
                onAccessSuccess(device, null)
            } else {
                handleBrowseFailure(
                    throwable = result.exceptionOrNull(),
                    device = device,
                    retryAction = PendingAction.LoadDirectory(device, share, path),
                )
            }
        }
    }

    /** 访问成功：刷新设备元数据 + 更新「我的设备」使用时间 */
    private fun onAccessSuccess(device: NeighborDeviceItem, shareCount: Int?) {
        updateDevice(
            ip = device.ip,
            hostName = null,
            shareCount = shareCount,
            needsAuth = false,
        )
        viewModelScope.launch {
            runCatchingCancellable { savedDeviceRepository.touch(device.ip, device.hostName) }
        }
        // 登录成功后若仍没有设备名，用已有凭据再补一次（任务单 2.1「设备名补全」）
        if (device.hostName.isNullOrBlank()) {
            backfillHostName(device.ip)
        }
    }

    /** 带凭据补全设备名（每个 IP 只尝试一次；失败静默回退显示 IP） */
    private fun backfillHostName(ip: String) {
        if (!hostNameProbedIps.add(ip)) return
        viewModelScope.launch {
            val credentials = credentialsFor(ip)
            val name = try {
                smbBrowser.hostName(ip, credentials ?: SmbCredentials.ANONYMOUS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                null
            }
            if (!name.isNullOrBlank()) {
                val trimmed = name.trim()
                updateDevice(ip = ip, hostName = trimmed)
                persistProbed(ip, trimmed)
            }
        }
    }

    private fun parentPath(path: String): String {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.contains('/')) trimmed.substringBeforeLast('/') else ""
    }

    // =========================================================================
    // 认证（按需弹窗）
    // =========================================================================

    /** 浏览失败统一处理：认证类 → 弹窗；其余 → 人话错误 */
    private fun handleBrowseFailure(
        throwable: Throwable?,
        device: NeighborDeviceItem,
        retryAction: PendingAction,
    ) {
        val kind = (throwable as? SmbBrowseException)?.kind ?: SmbFailureKind.UNKNOWN
        val hasCredentials = credentialsCache.containsKey(device.ip)
        val needAuth = kind == SmbFailureKind.AUTH_REQUIRED ||
            (!hasCredentials && kind == SmbFailureKind.ACCESS_DENIED)
        if (needAuth) {
            pendingAction = retryAction
            _uiState.update { state ->
                state.copy(
                    sharesLoading = false,
                    entriesLoading = false,
                    // 同时把提示留在当前页：用户关掉认证弹窗后仍能看到原因与「输入账号密码」入口
                    sharesError = if (state.step == NeighborStep.SHARES) {
                        "需要账号密码才能访问这台设备"
                    } else {
                        state.sharesError
                    },
                    entriesError = if (state.step == NeighborStep.BROWSE) {
                        "需要账号密码才能访问该目录"
                    } else {
                        state.entriesError
                    },
                    authDialog = AuthDialogState(
                        host = device.ip,
                        deviceName = device.displayName,
                        // 已带凭据仍被拒 → 说明账号密码不对
                        error = if (hasCredentials) "账号或密码不对，请重新输入" else null,
                        suggestedUsername = credentialsCache[device.ip]?.username.orEmpty(),
                    ),
                )
            }
            return
        }
        val message = smbFailureMessage(kind)
        _uiState.update { state ->
            when (state.step) {
                NeighborStep.SHARES -> state.copy(sharesLoading = false, sharesError = message)
                NeighborStep.BROWSE -> state.copy(entriesLoading = false, entriesError = message)
                NeighborStep.SCAN -> state.copy(
                    sharesLoading = false,
                    entriesLoading = false,
                    message = message,
                )
            }
        }
    }

    /** 用户主动点「输入账号密码」 */
    fun requestAuth() {
        val state = _uiState.value
        val device = state.currentDevice ?: return
        pendingAction = when (state.step) {
            NeighborStep.SHARES -> PendingAction.LoadShares(device)
            NeighborStep.BROWSE -> state.currentShare?.let { share ->
                PendingAction.LoadDirectory(device, share, state.currentPath)
            }
            NeighborStep.SCAN -> null
        }
        _uiState.update {
            it.copy(
                authDialog = AuthDialogState(
                    host = device.ip,
                    deviceName = device.displayName,
                    suggestedUsername = credentialsCache[device.ip]?.username.orEmpty(),
                ),
            )
        }
    }

    /** 提交账号密码：写入加密仓库 → 重试挂起的浏览操作 */
    fun submitCredentials(username: String, password: String) {
        val dialog = _uiState.value.authDialog ?: return
        if (dialog.submitting) return
        _uiState.update { it.copy(authDialog = dialog.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            val trimmedUser = username.trim()
            val result = runCatchingCancellable {
                credentialStore.save(
                    NeighborCredentials(
                        host = dialog.host,
                        username = trimmedUser,
                        password = password,
                    ),
                )
            }
            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        authDialog = dialog.copy(
                            submitting = false,
                            error = "凭据保存失败，请重试",
                        ),
                    )
                }
                return@launch
            }
            credentialsCache[dialog.host] = SmbCredentials(
                username = trimmedUser,
                password = password,
            )
            _uiState.update { it.copy(authDialog = null) }
            val action = pendingAction
            pendingAction = null
            if (action != null) {
                runPendingAction(action)
            } else {
                retryCurrentStep()
            }
        }
    }

    /** 取消认证：保留当前页面，提示可重新点击设备重试 */
    fun cancelAuth() {
        pendingAction = null
        _uiState.update {
            it.copy(
                authDialog = null,
                message = "需要账号密码才能访问，可重新点击设备重试",
            )
        }
    }

    /** 共享/目录加载失败后的「重试」入口 */
    fun retryLoad() {
        retryCurrentStep()
    }

    private fun retryCurrentStep() {
        val state = _uiState.value
        val device = state.currentDevice ?: return
        when (state.step) {
            NeighborStep.SHARES -> loadShares(device)
            NeighborStep.BROWSE -> {
                val share = state.currentShare ?: return
                loadDirectory(device, share, state.currentPath)
            }
            NeighborStep.SCAN -> Unit
        }
    }

    // =========================================================================
    // 生成配置（使用此文件夹）
    // =========================================================================

    /**
     * 选定当前目录 → 生成标准 NasConfig 并设为迁移目标。
     *
     * 字段规则由 [NeighborConfigFactory]（纯函数）保证：protocol=SMB、port=445、
     * smbShare=所选共享、basePath=当前相对路径、用户名/密码取已保存凭据（匿名为空串）。
     */
    fun useCurrentFolder() {
        val state = _uiState.value
        val device = state.currentDevice ?: return
        val share = state.currentShare ?: return
        if (state.savingConfig) return
        val path = state.currentPath
        viewModelScope.launch {
            _uiState.update { it.copy(savingConfig = true) }
            val credentials = credentialsFor(device.ip)
            val config = NeighborConfigFactory.create(
                deviceIp = device.ip,
                deviceName = device.hostName,
                share = share,
                basePath = path,
                username = credentials?.username.orEmpty(),
                password = credentials?.password.orEmpty(),
            )
            val result = runCatchingCancellable {
                nasConfigRepository.upsert(config)
                nasConfigRepository.setActive(config.id)
            }
            if (result.isSuccess) {
                runCatchingCancellable { savedDeviceRepository.touch(device.ip, device.hostName) }
                _uiState.update {
                    it.copy(
                        savingConfig = false,
                        createdConfigName = config.name,
                        message = "已生成配置「${config.name}」并设为迁移目标",
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        savingConfig = false,
                        message = "生成配置失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun dismissCreatedConfig() {
        _uiState.update { it.copy(createdConfigName = null) }
    }

    // =========================================================================
    // 消息
    // =========================================================================

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun showMessage(text: String) {
        _uiState.update { it.copy(message = text) }
    }

    // =========================================================================
    // 内部工具
    // =========================================================================

    /** 读取凭据：内存缓存优先，其次加密仓库（失败按匿名处理） */
    private suspend fun credentialsFor(host: String): SmbCredentials? {
        credentialsCache[host]?.let { return it }
        val stored = try {
            credentialStore.get(host)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            null
        }
        if (stored == null) return null
        val credentials = SmbCredentials(username = stored.username, password = stored.password)
        credentialsCache[host] = credentials
        return credentials
    }

    /**
     * 类似 runCatching，但**不吞协程取消**（否则快速切换目录时会把取消当失败提示）。
     */
    private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }

    /** 认证成功后要重试的浏览动作（比保存 lambda 更安全、可读） */
    private sealed interface PendingAction {
        data class LoadShares(val device: NeighborDeviceItem) : PendingAction
        data class LoadDirectory(
            val device: NeighborDeviceItem,
            val share: String,
            val path: String,
        ) : PendingAction
    }

    private fun runPendingAction(action: PendingAction) {
        when (action) {
            is PendingAction.LoadShares -> loadShares(action.device)
            is PendingAction.LoadDirectory -> loadDirectory(action.device, action.share, action.path)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { NeighborViewModel(container) }
        }
    }
}

/**
 * 浏览失败分类 → 人话（禁止把异常堆栈展示给用户）。
 */
private fun smbFailureMessage(kind: SmbFailureKind): String = when (kind) {
    SmbFailureKind.AUTH_REQUIRED -> "这台设备需要账号密码"
    SmbFailureKind.ACCESS_DENIED -> "没有访问权限，请换一个有权限的账号或共享文件夹"
    SmbFailureKind.HOST_UNREACHABLE -> "连接超时，请确认手机和 NAS 在同一 WiFi"
    SmbFailureKind.SHARE_NOT_FOUND -> "共享文件夹不存在"
    SmbFailureKind.TIMEOUT -> "连接超时，请确认手机和 NAS 在同一 WiFi"
    SmbFailureKind.UNKNOWN -> "访问失败，请稍后重试"
}
