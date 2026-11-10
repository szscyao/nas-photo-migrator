// security-crypto 1.1.0 已把 EncryptedSharedPreferences/MasterKey 标记为 @Deprecated（Jetpack 官方建议迁移），
// 但阶段1架构已锁定该方案（自用工具、无上架诉求），因此本文件统一压制该废弃警告，保持构建输出干净。
@file:Suppress("DEPRECATION")

package com.nasphoto.migrator.data.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * 加密 SharedPreferences 工厂（NAS 密码等敏感数据的唯一落盘入口）。
 *
 * 方案（阶段1第3节选型）：
 *  - `MasterKey`（AES256_GCM）由 **Android Keystore** 持有，密钥材料永不进入应用进程/磁盘；
 *  - `EncryptedSharedPreferences` 用 AES256_SIV 加密 **key**、AES256_GCM 加密 **value**。
 *    因此仓库里整份 NAS 配置 JSON（含 password 字段）落盘后都是密文，**密码不会明文落盘**。
 *
 * 为什么要做异常恢复：
 *  Keystore 主密钥可能因「设备恢复出厂 / 备份还原到新机 / 系统锁屏凭据重置 / 厂商 Keystore 故障」
 *  而永久失效，此时 `EncryptedSharedPreferences.create(...)` 会在启动阶段直接抛
 *  `GeneralSecurityException / IOException / KeyStoreException` 等异常。若放任不管，
 *  AppContainer 构造时会直接崩溃（用户连设置页都进不去）。
 *  这里的策略是：捕获 → 删除可能损坏的 prefs 文件/keyset 文件与失效的 Keystore 别名 → 重建一次；
 *  仍然失败时返回 null，由 [NasConfigRepositoryImpl] 降级为「内存模式」（功能可用、配置不落盘），
 *  绝不让一个存储故障拖垮整个应用，也绝不在降级时把密码写到明文 SharedPreferences。
 */
object EncryptedPrefsFactory {

    private const val TAG = "EncryptedPrefsFactory"

    /** 本应用专用 Keystore 主密钥别名（与其它库的默认别名隔离）。 */
    private const val MASTER_KEY_ALIAS = "nas_migrator_master_key"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /** 应用私有 shared_prefs 目录名（不是相册/外部存储，与 Scoped Storage 无关）。 */
    private const val SHARED_PREFS_DIR = "shared_prefs"

    /**
     * security-crypto 内部 keyset 的 SharedPreferences 文件名（**固定名称**，不随业务 prefs 文件名变化）。
     *
     * 来源：`EncryptedSharedPreferences.KEY_KEYSET_ALIAS` / `VALUE_KEYSET_ALIAS`（1.1.0 实现）。
     * 主密钥失效/重建后，旧 keyset 无法再解开，必须连同业务数据文件一起清理；
     * 这些常量在库中是 private，故此处以字面量兜底（库实现多年稳定）。
     */
    private const val KEY_KEYSET_FILE = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUE_KEYSET_FILE = "__androidx_security_crypto_encrypted_prefs_value_keyset__"

    /**
     * 创建（或打开）一个加密 SharedPreferences 文件。
     *
     * @param fileName prefs 文件名（建议不含 `.xml`），例如 NAS 配置文件名
     * @return 加密后的 SharedPreferences；若 Keystore 彻底不可用则返回 null（调用方需降级处理）
     */
    @Suppress("DEPRECATION") // security-crypto 1.1.0 中 EncryptedSharedPreferences/MasterKey 已标记废弃，但本项目按阶段1选型继续使用
    fun create(context: Context, fileName: String): SharedPreferences? {
        val appContext = context.applicationContext
        return try {
            openEncrypted(appContext, fileName)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            Log.e(TAG, "打开加密 SharedPreferences 失败，尝试清理损坏数据并重建：file=$fileName", t)
            recover(appContext, fileName)
            try {
                openEncrypted(appContext, fileName)
            } catch (t2: Throwable) {
                rethrowIfFatal(t2)
                // 彻底失败：降级为内存模式。绝不回退到明文 SharedPreferences —— 那会让密码明文落盘。
                Log.e(TAG, "重建加密 SharedPreferences 仍失败，本次运行使用内存模式（配置不持久化）：file=$fileName", t2)
                null
            }
        }
    }

    /**
     * 真正打开加密 prefs。
     *
     * 注意：首次创建会做 Keystore 生成密钥 + Tink keyset 生成，属于磁盘/加密操作（毫秒到几十毫秒级），
     * 调用方应保证在 IO 线程上执行（[NasConfigRepositoryImpl] 通过 `Dispatchers.IO` + `Mutex` 保证）。
     */
    @Suppress("DEPRECATION")
    private fun openEncrypted(context: Context, fileName: String): SharedPreferences {
        // 主密钥由 Android Keystore 保护：AES256_GCM，密钥材料不出 TEE/StrongBox（可用时）
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            // key 用确定性加密（SIV）：相同 key 得到相同密文，便于 SharedPreferences 按 key 查找
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            // value 用 GCM：带完整性校验，篡改会被检测到
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 恢复动作：先删文件（业务数据 + 两个 keyset），再删可能失效的 Keystore 别名。 */
    private fun recover(context: Context, fileName: String) {
        deleteSharedPrefsFiles(context, listOf(fileName, KEY_KEYSET_FILE, VALUE_KEYSET_FILE))
        deleteMasterKeyAlias()
    }

    /**
     * 删除 shared_prefs 下这些「文件名前缀」对应的文件（含 `.xml` / `.xml.bak` 等）。
     * 只动应用私有目录中本工具自己的文件，不涉及任何相册/外部存储内容。
     */
    private fun deleteSharedPrefsFiles(context: Context, fileBaseNames: List<String>) {
        runCatching {
            val prefsDir = File(context.dataDir, SHARED_PREFS_DIR)
            val files = prefsDir.listFiles() ?: return@runCatching
            for (file in files) {
                if (fileBaseNames.any { base -> file.name.startsWith(base) }) {
                    val deleted = file.delete()
                    Log.w(TAG, "清理加密配置缓存文件 ${file.name}（deleted=$deleted）")
                }
            }
        }.onFailure { Log.w(TAG, "清理加密配置缓存文件失败（继续尝试重建）", it) }
    }

    /** 删除 Keystore 中的主密钥别名，下次 [MasterKey.Builder.build] 会重新生成新密钥。 */
    private fun deleteMasterKeyAlias() {
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
                Log.w(TAG, "已删除失效的 Keystore 主密钥别名：$MASTER_KEY_ALIAS")
            }
        }.onFailure { Log.w(TAG, "删除 Keystore 主密钥别名失败（继续尝试重建）", it) }
    }

    /** 只拦截真正不可恢复的致命错误（OOM / 栈溢出等），其余异常都走恢复流程。 */
    private fun rethrowIfFatal(t: Throwable) {
        if (t is VirtualMachineError || t is ThreadDeath) {
            throw t
        }
    }
}
