package com.nasphoto.migrator.data.neighbor

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nasphoto.migrator.data.config.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [NeighborCredentialStore] 的加密实现（网络邻居 SMB 认证凭据）。
 *
 * 存储结构（复用 [EncryptedPrefsFactory] 的 EncryptedSharedPreferences + Android Keystore）：
 * ```
 * prefs 文件：neighbor_credentials_enc
 * key  "credentials_json"
 * value {"192.168.1.10":{"u":"user","p":"pass"}, ...}
 * ```
 * 整份 JSON 作为**一个 value** 存进加密 prefs（AES256_GCM 加密），key 由 AES256_SIV 加密，
 * 因此磁盘上不会出现明文账号/密码；本类任何日志都**只输出主机与错误类型，绝不输出用户名/密码**。
 *
 * 降级策略（安全底线）：
 *  - Keystore 彻底不可用（[EncryptedPrefsFactory.create] 返回 null）时，退化为**进程内 Map**，
 *    仅本次运行有效；
 *  - **绝不**回退到明文 SharedPreferences —— 宁可重启后要求重新输入密码，也不让密码明文落盘。
 *
 * 线程模型：`prefs` 懒创建、JSON 读改写、commit 全部在 `Dispatchers.IO` 上执行，
 * 并用 [mutex] 串行化「读-改-写」，避免并发保存时丢失条目。
 */
class EncryptedNeighborCredentialStore(context: Context) : NeighborCredentialStore {

    private val appContext = context.applicationContext

    /**
     * 加密 prefs（懒创建：首次访问时才做 Keystore/Tink 初始化，位于 IO 线程）。
     * null = Keystore 不可用，本进程改用 [memoryFallback]。
     */
    private val prefs: SharedPreferences? by lazy {
        EncryptedPrefsFactory.create(appContext, PREFS_FILE_NAME)
    }

    /** 串行化凭据的「读-改-写」（JSON 整份重写语义） */
    private val mutex = Mutex()

    /** Keystore 不可用时的进程内降级存储（只存在内存，绝不落盘） */
    private val memoryFallback = LinkedHashMap<String, NeighborCredentials>()

    @Volatile
    private var degradedWarned = false

    // ==================== 读 ====================

    override suspend fun get(host: String): NeighborCredentials? = withContext(Dispatchers.IO) {
        val key = normalizeHost(host) ?: return@withContext null
        mutex.withLock {
            val p = prefs
            if (p == null) {
                warnDegradedOnce()
                return@withLock memoryFallback[key]
            }
            readAllLocked(p)[key]
        }
    }

    // ==================== 写 ====================

    override suspend fun save(credentials: NeighborCredentials) {
        withContext(Dispatchers.IO) {
            val key = normalizeHost(credentials.host) ?: return@withContext
            mutex.withLock {
                // 统一以规范化后的 host 作为 key，避免 " 192.168.1.10 " 之类产生重复条目
                val normalized = credentials.copy(host = key)
                val p = prefs
                if (p == null) {
                    warnDegradedOnce()
                    memoryFallback[key] = normalized
                    return@withLock
                }
                val all = readAllLocked(p).toMutableMap()
                all[key] = normalized
                writeAllLocked(p, all)
            }
        }
    }

    override suspend fun remove(host: String) {
        withContext(Dispatchers.IO) {
            val key = normalizeHost(host) ?: return@withContext
            mutex.withLock {
                val p = prefs
                if (p == null) {
                    warnDegradedOnce()
                    memoryFallback.remove(key)
                    return@withLock
                }
                val all = readAllLocked(p).toMutableMap()
                if (all.remove(key) == null) return@withLock
                writeAllLocked(p, all)
            }
        }
    }

    // ==================== 内部实现 ====================

    private fun normalizeHost(host: String?): String? =
        host?.trim()?.takeIf { it.isNotEmpty() }

    /** 读取整份凭据 JSON（调用方持有 [mutex]，且在 IO 线程）。 */
    private fun readAllLocked(p: SharedPreferences): Map<String, NeighborCredentials> {
        val raw = runCatching { p.getString(KEY_CREDENTIALS_JSON, null) }.getOrNull()
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            val result = LinkedHashMap<String, NeighborCredentials>(root.length())
            val keys = root.keys()
            while (keys.hasNext()) {
                val host = keys.next()
                val obj = root.optJSONObject(host) ?: continue
                val normalizedHost = normalizeHost(host) ?: continue
                result[normalizedHost] = NeighborCredentials(
                    host = normalizedHost,
                    username = obj.optString(FIELD_USERNAME, ""),
                    password = obj.optString(FIELD_PASSWORD, ""),
                )
            }
            result
        } catch (t: Throwable) {
            // 注意：这里**不能**打印异常 message —— org.json 的异常信息可能带出原始 JSON 片段，
            // 其中包含密码；只记录异常类型，避免凭据进入日志。
            Log.e(TAG, "解析邻居凭据失败（按空处理，不回退明文）：${t.javaClass.simpleName}")
            emptyMap()
        }
    }

    /** 写回整份凭据 JSON（调用方持有 [mutex]，且在 IO 线程）。 */
    private fun writeAllLocked(p: SharedPreferences, all: Map<String, NeighborCredentials>) {
        try {
            val root = JSONObject()
            for ((host, credentials) in all) {
                val obj = JSONObject()
                obj.put(FIELD_USERNAME, credentials.username)
                obj.put(FIELD_PASSWORD, credentials.password)
                root.put(host, obj)
            }
            // commit 在 IO 线程：凭据是低频写，确保“保存后立即可读”的确定性
            val ok = p.edit().putString(KEY_CREDENTIALS_JSON, root.toString()).commit()
            if (!ok) {
                Log.e(TAG, "写入邻居凭据失败（commit=false），本次修改仅保存在内存中")
            }
        } catch (t: Throwable) {
            // 同样不打印异常 message，防止把待写入 JSON（含密码）带进日志
            Log.e(TAG, "写入邻居凭据异常（不回退明文）：${t.javaClass.simpleName}")
        }
    }

    private fun warnDegradedOnce() {
        if (degradedWarned) return
        degradedWarned = true
        Log.w(TAG, "Keystore 不可用：邻居凭据降级为进程内存储（本次运行有效，绝不落盘明文）")
    }

    private companion object {
        private const val TAG = "NeighborCredentials"

        /** 邻居凭据加密 prefs 文件名（与 NAS 配置的加密文件分离，互不影响） */
        private const val PREFS_FILE_NAME = "neighbor_credentials_enc"

        /** 单 key 存整份 JSON 映射 */
        private const val KEY_CREDENTIALS_JSON = "credentials_json"

        // JSON 字段名（任务单约定：host → {"u": 用户名, "p": 密码}）
        private const val FIELD_USERNAME = "u"
        private const val FIELD_PASSWORD = "p"
    }
}
