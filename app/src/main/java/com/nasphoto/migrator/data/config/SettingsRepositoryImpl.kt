package com.nasphoto.migrator.data.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 应用设置仓库实现（**明文 SharedPreferences**）。
 *
 * 为什么明文：设置项（WiFi/校验开关/排序/相册筛选/电量/唤醒）都不敏感，无需加密；
 * 加密存储只用于 NAS 密码（见 [NasConfigRepositoryImpl]）。
 *
 * 线程模型：
 *  - 对外 suspend 方法统一 `withContext(Dispatchers.IO)`；
 *  - [MutableStateFlow] 做内存缓存，collect 不读盘；
 *  - 监听 SharedPreferences 变更（含外部修改）后回读并 emit；
 *  - 所有写操作经 [mutex] 串行化，update 的「读最新 → transform → 保存」不会丢更新。
 *
 * 容错：缺失 key 用 [AppSettings] 默认值；类型非法/数值越界（如阈值 > 50）会被纠正，绝不抛异常。
 */
class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val mutex = Mutex()

    /** 设置内存缓存（初始为默认值；首次 IO 加载后填充真实值）。 */
    private val _settings = MutableStateFlow(AppSettings())

    /** 首次加载是否完成（只在 [mutex] 内读写）。 */
    private var loaded = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val settingsFlow: Flow<AppSettings> = _settings.asStateFlow()

    /** prefs 变更回调：本类写入与外部写入都会触发；统一回读磁盘后 emit。 */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scope.launch {
            runCatching { refreshFromDisk() }
                .onFailure { Log.e(TAG, "同步设置变更失败", it) }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // 异步预热缓存：AppContainer 在 Application.onCreate（主线程）构造本类，不能阻塞启动
        scope.launch {
            runCatching { refreshFromDisk() }
                .onFailure { Log.e(TAG, "初始化设置缓存失败", it) }
        }
    }

    // ==================== 读 ====================

    override suspend fun get(): AppSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            _settings.value
        }
    }

    // ==================== 写 ====================

    override suspend fun save(settings: AppSettings) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sanitized = sanitize(settings)
                writePrefs(sanitized)
                _settings.value = sanitized
                loaded = true
            }
        }
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoadedLocked()
                // 基于「最新值」做局部更新，避免并发写时用旧快照覆盖新值
                val updated = sanitize(transform(_settings.value))
                writePrefs(updated)
                _settings.value = updated
                updated
            }
        }

    // ==================== 内部实现 ====================

    /**
     * 强制从磁盘回读（首次加载 + prefs 变更回调共用）。
     * 调用方保证在 IO 线程执行（init/监听回调均通过 [scope] 的 IO 调度器）。
     * 读盘放在 [mutex] 内，保证「读 → emit」不会被并发写打断而 emit 出旧快照。
     */
    private suspend fun refreshFromDisk() {
        mutex.withLock {
            _settings.value = readPrefs()
            loaded = true
        }
    }

    /** 首次访问时确保已从磁盘加载（调用方持有 [mutex]）。 */
    private fun ensureLoadedLocked() {
        if (loaded) return
        _settings.value = readPrefs()
        loaded = true
    }

    /** 写入全部设置项（调用方持有 [mutex]，且在 IO 线程）。 */
    private fun writePrefs(settings: AppSettings) {
        val ok = try {
            prefs.edit()
                .putBoolean(KEY_WIFI_ONLY, settings.wifiOnly)
                .putBoolean(KEY_VERIFY_MD5, settings.verifyMd5)
                .putString(KEY_SORT_ORDER, settings.sortOrder.name)
                .putString(KEY_ALBUM_SCOPE, settings.albumScope.name)
                .putString(KEY_SELECTED_ALBUM_IDS, encodeAlbumIds(settings.selectedAlbumIds))
                .putBoolean(KEY_LOW_BATTERY_PAUSE, settings.lowBatteryPause)
                .putInt(KEY_LOW_BATTERY_THRESHOLD, settings.lowBatteryThreshold)
                .putBoolean(KEY_KEEP_AWAKE, settings.keepAwake)
                .putBoolean(KEY_PERMISSION_GUIDE_SHOWN, settings.permissionGuideShown)
                // 低频写操作，用 commit 保证「写完即可被下一次 get 读到」的确定性
                .commit()
        } catch (t: Throwable) {
            Log.e(TAG, "写入设置失败", t)
            false
        }
        if (!ok) Log.e(TAG, "写入设置失败（commit=false），本次修改仅保存在内存中")
    }

    /**
     * 从 prefs 读取设置。
     *
     * 用 `prefs.all` + 安全类型判断读取（而不是逐个 getBoolean/getInt），
     * 这样即使历史版本写入了错误类型（如 Boolean 位置存了 String），也只会回落到默认值，不会抛
     * `ClassCastException` 把应用打崩。
     */
    private fun readPrefs(): AppSettings {
        val defaults = AppSettings()
        return try {
            val all = prefs.all

            fun bool(key: String, def: Boolean): Boolean =
                when (val v = all[key]) {
                    is Boolean -> v
                    is String -> v.toBooleanStrictOrNull() ?: def
                    else -> def
                }

            fun int(key: String, def: Int): Int =
                when (val v = all[key]) {
                    is Int -> v
                    is Long -> v.toInt()
                    is String -> v.toIntOrNull() ?: def
                    else -> def
                }

            fun str(key: String, def: String?): String? =
                when (val v = all[key]) {
                    is String -> v
                    else -> def
                }

            AppSettings(
                wifiOnly = bool(KEY_WIFI_ONLY, defaults.wifiOnly),
                verifyMd5 = bool(KEY_VERIFY_MD5, defaults.verifyMd5),
                sortOrder = parseSortOrder(str(KEY_SORT_ORDER, null), defaults.sortOrder),
                albumScope = parseAlbumScope(str(KEY_ALBUM_SCOPE, null), defaults.albumScope),
                selectedAlbumIds = parseAlbumIds(str(KEY_SELECTED_ALBUM_IDS, null)),
                lowBatteryPause = bool(KEY_LOW_BATTERY_PAUSE, defaults.lowBatteryPause),
                lowBatteryThreshold = int(KEY_LOW_BATTERY_THRESHOLD, defaults.lowBatteryThreshold)
                    .coerceIn(MIN_BATTERY_THRESHOLD, MAX_BATTERY_THRESHOLD),
                keepAwake = bool(KEY_KEEP_AWAKE, defaults.keepAwake),
                permissionGuideShown = bool(KEY_PERMISSION_GUIDE_SHOWN, defaults.permissionGuideShown),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "读取设置失败，回落到默认值", t)
            defaults
        }
    }

    /** 兜底纠正非法值（阈值越界、空白相册 ID 等），保证 Flow 里永远是合法设置。 */
    private fun sanitize(settings: AppSettings): AppSettings = settings.copy(
        lowBatteryThreshold = settings.lowBatteryThreshold
            .coerceIn(MIN_BATTERY_THRESHOLD, MAX_BATTERY_THRESHOLD),
        selectedAlbumIds = settings.selectedAlbumIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet(),
    )

    private companion object {
        private const val TAG = "SettingsRepository"

        /** 设置 prefs 文件名。 */
        private const val PREFS_NAME = "app_settings"

        // ---- key 命名规范（阶段2契约） ----
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_VERIFY_MD5 = "verify_md5"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_ALBUM_SCOPE = "album_scope"
        private const val KEY_SELECTED_ALBUM_IDS = "selected_album_ids"
        private const val KEY_LOW_BATTERY_PAUSE = "low_battery_pause"
        private const val KEY_LOW_BATTERY_THRESHOLD = "low_battery_threshold"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_PERMISSION_GUIDE_SHOWN = "permission_guide_shown"

        /** 低电量阈值允许范围（与 [AppSettings] 注释一致：5~50%）。 */
        private const val MIN_BATTERY_THRESHOLD = 5
        private const val MAX_BATTERY_THRESHOLD = 50

        private fun parseSortOrder(raw: String?, fallback: MediaSortOrder): MediaSortOrder {
            if (raw.isNullOrBlank()) return fallback
            return MediaSortOrder.values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: fallback
        }

        private fun parseAlbumScope(raw: String?, fallback: AlbumScope): AlbumScope {
            if (raw.isNullOrBlank()) return fallback
            return AlbumScope.values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: fallback
        }

        /**
         * 解析选中的相册 ID 集合。
         * 存储格式优先 JSON 数组（`["123","456"]`），兼容历史/手工写入的逗号分隔格式。
         */
        private fun parseAlbumIds(raw: String?): Set<String> {
            if (raw.isNullOrBlank()) return emptySet()
            val text = raw.trim()
            val tokens: List<String> = if (text.startsWith("[")) {
                runCatching {
                    val array = JSONArray(text)
                    (0 until array.length()).mapNotNull { i ->
                        array.optString(i, null)?.takeIf { it.isNotBlank() }
                    }
                }.getOrElse {
                    // JSON 损坏时退回逗号分隔解析，不抛异常
                    text.trim('[', ']').split(',')
                }
            } else {
                text.split(',')
            }
            return tokens
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        /** 序列化为 JSON 数组字符串（排序后输出，保证同样内容得到稳定字符串，便于比较/排查）。 */
        private fun encodeAlbumIds(ids: Set<String>): String {
            if (ids.isEmpty()) return "[]"
            val array = JSONArray()
            ids.map { it.trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .forEach { array.put(it) }
            return array.toString()
        }
    }
}
