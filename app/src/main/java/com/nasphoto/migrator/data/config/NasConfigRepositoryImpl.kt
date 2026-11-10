package com.nasphoto.migrator.data.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nasphoto.migrator.protocol.NasProtocol
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
import org.json.JSONObject

/**
 * NAS 多配置仓库实现（**加密持久化**）。
 *
 * 存储结构（全部位于 [EncryptedPrefsFactory] 提供的一个加密 SharedPreferences 文件中）：
 * ```
 * configs_json       = [ {id,name,protocol,host,port,username,password,...}, ... ]  // AES256_GCM 加密值
 * active_config_id   = "uuid"                                                     // 当前迁移目标
 * ```
 * 为什么整份列表存一个 JSON 而不是每个字段一个 pref key：
 *  - 读写简单、原子（一次 commit 写入整份列表，不会出现半更新状态）；
 *  - 列表通常只有个位数条配置，JSON 序列化开销可忽略。
 *
 * 线程模型：
 *  - 对外 suspend 方法统一 `withContext(Dispatchers.IO)`，绝不在主线程做磁盘/加密操作；
 *  - 所有「读-改-写」用 [mutex] 串行化，避免多协程同时 upsert 导致丢失更新；
 *  - Flow 用 [MutableStateFlow] 做内存缓存，collect 时不读盘（写操作完成后立即 refresh + emit）。
 */
class NasConfigRepositoryImpl(context: Context) : NasConfigRepository {

    private val appContext = context.applicationContext

    /**
     * 加密 SharedPreferences（懒创建：首次访问时才做 Keystore/Tink 初始化，通常在 IO 线程）。
     * 为 null 表示 Keystore 彻底不可用，此时降级为「内存模式」（功能可用、重启后配置丢失，
     * 但绝不以明文形式落盘密码）。
     */
    private val prefs: SharedPreferences? by lazy {
        EncryptedPrefsFactory.create(appContext, PREFS_FILE_NAME)
    }

    private val mutex = Mutex()

    /** 配置列表内存缓存（含密码，UI 展示前必须 sanitized()）。 */
    private val _configs = MutableStateFlow<List<NasConfig>>(emptyList())

    /** 当前激活配置（未设置/已被删除时为 null）。 */
    private val _activeConfig = MutableStateFlow<NasConfig?>(null)

    /** 当前激活配置 ID，仅在持有 [mutex] 时读写。 */
    private var activeConfigId: String? = null

    /** 首次加载是否完成（只在 [mutex] 内读写）。 */
    private var loaded = false

    /** 仓库内部作用域：仅用于构造后预热缓存，不承载业务任务。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val configsFlow: Flow<List<NasConfig>> = _configs.asStateFlow()

    override val activeConfigFlow: Flow<NasConfig?> = _activeConfig.asStateFlow()

    init {
        // 预热：AppContainer 在 Application.onCreate（主线程）构造本类，必须异步加载，不能阻塞启动
        scope.launch {
            runCatching { mutex.withLock { ensureLoadedLocked() } }
                .onFailure { Log.e(TAG, "初始化 NAS 配置缓存失败", it) }
        }
    }

    // ==================== 读 ====================

    override suspend fun list(): List<NasConfig> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            _configs.value
        }
    }

    override suspend fun get(id: String): NasConfig? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            _configs.value.firstOrNull { it.id == id }
        }
    }

    override suspend fun getActive(): NasConfig? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            _activeConfig.value
        }
    }

    // ==================== 写 ====================

    override suspend fun upsert(config: NasConfig) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoadedLocked()
                val updated = _configs.value.toMutableList()
                val index = updated.indexOfFirst { it.id == config.id }
                if (index >= 0) {
                    updated[index] = config
                } else {
                    updated.add(config)
                }
                persistLocked(updated, activeConfigId)
                publishLocked(updated, activeConfigId)
            }
        }
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoadedLocked()
                val current = _configs.value
                val updated = current.filterNot { it.id == id }
                if (updated.size == current.size) {
                    // 契约未定义「删除不存在项」的语义：幂等忽略即可，不视为异常
                    Log.w(TAG, "delete: 配置不存在，忽略。id=$id")
                    return@withLock
                }
                // 删除的是当前激活配置 → 自动取消激活，避免出现「激活配置指向已删除项」
                val newActiveId = if (activeConfigId == id) null else activeConfigId
                persistLocked(updated, newActiveId)
                publishLocked(updated, newActiveId)
            }
        }
    }

    override suspend fun setActive(id: String?) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoadedLocked()
                if (id != null && _configs.value.none { it.id == id }) {
                    Log.w(TAG, "setActive: 配置不存在，忽略。id=$id")
                    return@withLock
                }
                persistLocked(_configs.value, id)
                publishLocked(_configs.value, id)
            }
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 首次加载：从加密 prefs 读取 JSON 列表 + 激活 ID，填充 StateFlow 缓存。
     * 调用方必须持有 [mutex]。
     */
    private fun ensureLoadedLocked() {
        if (loaded) return
        // 先置位再读盘：即便读盘/解析异常，也不会让每次调用都重试，从而避免反复抛异常
        loaded = true

        val p = prefs
        if (p == null) {
            Log.w(TAG, "加密存储不可用：NAS 配置运行于内存模式（密码不会明文落盘）")
            return
        }

        val rawConfigs = runCatching { p.getString(KEY_CONFIGS_JSON, null) }.getOrNull()
        val rawActiveId = runCatching { p.getString(KEY_ACTIVE_CONFIG_ID, null) }.getOrNull()

        val configs = parseConfigs(rawConfigs)
        // 激活 ID 可能指向被外部删掉的配置：解析时兜底为 null
        activeConfigId = rawActiveId?.takeIf { id -> configs.any { it.id == id } }

        _configs.value = configs
        _activeConfig.value = configs.firstOrNull { it.id == activeConfigId }
        Log.d(TAG, "加载 NAS 配置完成：count=${configs.size}, active=$activeConfigId")
    }

    /** 把整份配置列表 + 激活 ID 原子写入加密 prefs（调用方持有 [mutex]，且在 IO 线程）。 */
    private fun persistLocked(configs: List<NasConfig>, activeId: String?) {
        val p = prefs
        if (p == null) {
            Log.w(TAG, "加密存储不可用，配置仅保存在内存中（本次进程有效）")
            return
        }
        try {
            val editor = p.edit().putString(KEY_CONFIGS_JSON, serializeConfigs(configs))
            if (activeId.isNullOrEmpty()) {
                editor.remove(KEY_ACTIVE_CONFIG_ID)
            } else {
                editor.putString(KEY_ACTIVE_CONFIG_ID, activeId)
            }
            // commit 而不是 apply：配置是低频写操作，需要「写成功后 UI 立即可依赖」的确定性
            if (!editor.commit()) {
                Log.e(TAG, "写入加密 NAS 配置失败（commit=false），本次修改仅保存在内存中")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "写入加密 NAS 配置异常，本次修改仅保存在内存中", t)
        }
    }

    /** 写操作后立即刷新内存缓存并 emit（避免 collect 时读盘）。调用方持有 [mutex]。 */
    private fun publishLocked(configs: List<NasConfig>, activeId: String?) {
        activeConfigId = activeId
        _configs.value = configs
        _activeConfig.value = configs.firstOrNull { it.id == activeId }
    }

    // ---------------- JSON 序列化 / 反序列化 ----------------

    private fun serializeConfigs(configs: List<NasConfig>): String {
        val array = JSONArray()
        for (c in configs) {
            val obj = JSONObject()
            obj.put(FIELD_ID, c.id)
            obj.put(FIELD_NAME, c.name)
            obj.put(FIELD_PROTOCOL, c.protocol.name)
            obj.put(FIELD_HOST, c.host)
            obj.put(FIELD_PORT, c.port)
            obj.put(FIELD_USERNAME, c.username)
            // 注意：password 的明文只存在于内存与本函数的入参中；落盘前由 EncryptedSharedPreferences
            // 用 AES256_GCM 加密整个 value，因此磁盘上不会出现明文密码。
            obj.put(FIELD_PASSWORD, c.password)
            obj.put(FIELD_SMB_SHARE, c.smbShare)
            obj.put(FIELD_BASE_PATH, c.basePath)
            obj.put(FIELD_USE_HTTPS, c.useHttps)
            obj.put(FIELD_CREATED_AT, c.createdAt)
            array.put(obj)
        }
        return array.toString()
    }

    /** 容错解析：任何单条配置损坏只跳过该条，不抛异常导致整个应用不可用。 */
    private fun parseConfigs(raw: String?): List<NasConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<NasConfig>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                parseConfig(obj)?.let { result.add(it) }
            }
            result
        } catch (t: Throwable) {
            Log.e(TAG, "解析 NAS 配置 JSON 失败（按空配置处理，不影响应用启动）。", t)
            emptyList()
        }
    }

    private fun parseConfig(obj: JSONObject): NasConfig? {
        val id = obj.optString(FIELD_ID, "").trim()
        if (id.isEmpty()) return null
        val protocol = runCatching {
            NasProtocol.valueOf(obj.optString(FIELD_PROTOCOL, NasProtocol.SMB.name))
        }.getOrDefault(NasProtocol.SMB)
        val fallbackPort = protocol.recommendedPort(false)
        val port = obj.optInt(FIELD_PORT, fallbackPort).takeIf { it in 1..65535 } ?: fallbackPort
        return NasConfig(
            id = id,
            name = obj.optString(FIELD_NAME, ""),
            protocol = protocol,
            host = obj.optString(FIELD_HOST, ""),
            port = port,
            username = obj.optString(FIELD_USERNAME, ""),
            password = obj.optString(FIELD_PASSWORD, ""),
            smbShare = obj.optString(FIELD_SMB_SHARE, ""),
            basePath = obj.optString(FIELD_BASE_PATH, ""),
            useHttps = obj.optBoolean(FIELD_USE_HTTPS, false),
            createdAt = obj.optLong(FIELD_CREATED_AT, System.currentTimeMillis()),
        )
    }

    private companion object {
        private const val TAG = "NasConfigRepository"

        /** 加密 prefs 文件名（不含 .xml）。 */
        private const val PREFS_FILE_NAME = "nas_configs_encrypted"

        /** JSON 数组（全局配置列表）的 key。 */
        private const val KEY_CONFIGS_JSON = "configs_json"

        /** 当前激活配置 ID 的 key（空表示未激活）。 */
        private const val KEY_ACTIVE_CONFIG_ID = "active_config_id"

        // ---- JSON 字段名（与 NasConfig 映射；改动需考虑向后兼容） ----
        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_PROTOCOL = "protocol"
        private const val FIELD_HOST = "host"
        private const val FIELD_PORT = "port"
        private const val FIELD_USERNAME = "username"
        private const val FIELD_PASSWORD = "password"
        private const val FIELD_SMB_SHARE = "smb_share"
        private const val FIELD_BASE_PATH = "base_path"
        private const val FIELD_USE_HTTPS = "use_https"
        private const val FIELD_CREATED_AT = "created_at"
    }
}
