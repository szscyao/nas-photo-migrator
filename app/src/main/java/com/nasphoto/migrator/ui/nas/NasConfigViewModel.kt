package com.nasphoto.migrator.ui.nas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nasphoto.migrator.data.config.NasConfig
import com.nasphoto.migrator.di.AppContainer
import com.nasphoto.migrator.protocol.NasClientFactory
import com.nasphoto.migrator.protocol.NasConnectionInfo
import com.nasphoto.migrator.protocol.NasException
import com.nasphoto.migrator.protocol.NasProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「测试连接」结果四态（Idle / Testing / Success / Failure）。
 */
sealed interface ConnectionTestResult {

    data object Idle : ConnectionTestResult

    data object Testing : ConnectionTestResult

    /** 成功：返回服务器信息、远程根路径、是否可写、剩余空间 */
    data class Success(val info: NasConnectionInfo) : ConnectionTestResult

    /** 失败：reason 为错误分类文案（NasErrorType.description），detail 为异常原始信息 */
    data class Failure(val reason: String, val detail: String? = null) : ConnectionTestResult
}

/**
 * 配置编辑弹窗状态（新增/编辑共用）。
 */
data class NasConfigEditorState(
    val isNew: Boolean,
    val draft: NasConfig,
    /** 端口输入框原始文本（允许用户清空后重新输入，保存时再解析） */
    val portText: String,
    val saving: Boolean = false,
    val testResult: ConnectionTestResult = ConnectionTestResult.Idle,
    val error: String? = null,
)

/**
 * NAS 配置页 UI 状态。
 */
data class NasConfigUiState(
    val configs: List<NasConfig> = emptyList(),
    val activeConfigId: String? = null,
    val loading: Boolean = true,
    val editor: NasConfigEditorState? = null,
    val pendingDelete: NasConfig? = null,
    val message: String? = null,
)

/**
 * NAS 配置页 ViewModel。
 *
 * 数据来源：NasConfigRepository（configsFlow / activeConfigFlow / upsert / delete / setActive）。
 * 密码展示：列表卡片使用 [NasConfig.summary] + 掩码，绝不显示明文；编辑弹窗内使用
 * PasswordVisualTransformation 隐藏，且仅在内存与加密存储中存在。
 * 测试连接：NasClientFactory.create(config).testConnection()，在 Dispatchers.IO 上执行，
 * 不阻塞 UI（loading 状态由 [ConnectionTestResult.Testing] 表示）。
 */
class NasConfigViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.nasConfigRepository

    private val _uiState = MutableStateFlow(NasConfigUiState())
    val uiState: StateFlow<NasConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.configsFlow.collect { configs ->
                _uiState.update { it.copy(configs = configs, loading = false) }
            }
        }
        viewModelScope.launch {
            repository.activeConfigFlow.collect { active ->
                _uiState.update { it.copy(activeConfigId = active?.id) }
            }
        }
    }

    // ---------------- 编辑弹窗开关 ----------------

    fun startCreate() {
        val draft = NasConfig.draft()
        _uiState.update {
            it.copy(
                editor = NasConfigEditorState(
                    isNew = true,
                    draft = draft,
                    portText = draft.port.toString(),
                ),
            )
        }
    }

    fun startEdit(config: NasConfig) {
        _uiState.update {
            it.copy(
                editor = NasConfigEditorState(
                    isNew = false,
                    draft = config,
                    portText = config.port.toString(),
                ),
            )
        }
    }

    fun closeEditor() {
        if (_uiState.value.editor?.saving == true) return
        _uiState.update { it.copy(editor = null) }
    }

    // ---------------- 编辑字段（全部只改内存 draft，保存时才落库） ----------------

    fun setName(value: String) = updateDraft { it.copy(name = value) }

    fun setHost(value: String) = updateDraft { it.copy(host = value.trim()) }

    fun setSmbShare(value: String) = updateDraft { it.copy(smbShare = value.trim()) }

    fun setBasePath(value: String) = updateDraft { it.copy(basePath = value.trim()) }

    fun setUsername(value: String) = updateDraft { it.copy(username = value) }

    fun setPassword(value: String) = updateDraft { it.copy(password = value) }

    /** 端口输入：只允许数字，最多 5 位；非法输入不覆盖已有效端口 */
    fun setPortText(value: String) {
        val digits = value.filter { it.isDigit() }.take(5)
        _uiState.update { state ->
            val editor = state.editor ?: return@update state
            val parsed = digits.toIntOrNull()
            state.copy(
                editor = editor.copy(
                    portText = digits,
                    draft = if (parsed != null) editor.draft.copy(port = parsed) else editor.draft,
                    testResult = ConnectionTestResult.Idle,
                    error = null,
                ),
            )
        }
    }

    /** 协议切换：端口若仍是旧协议默认值则自动切到新协议默认值 */
    fun setProtocol(protocol: NasProtocol) {
        updateDraft { draft ->
            if (draft.protocol == protocol) return@updateDraft draft
            val oldDefault = draft.protocol.recommendedPort(draft.useHttps)
            val newPort = if (draft.port == oldDefault) protocol.recommendedPort(draft.useHttps) else draft.port
            draft.copy(protocol = protocol, port = newPort)
        }
    }

    /** WebDAV HTTPS 开关：端口若仍是旧默认值则自动切换（80 ↔ 443） */
    fun setUseHttps(useHttps: Boolean) {
        updateDraft { draft ->
            val oldDefault = draft.protocol.recommendedPort(draft.useHttps)
            val newPort = if (draft.port == oldDefault) draft.protocol.recommendedPort(useHttps) else draft.port
            draft.copy(useHttps = useHttps, port = newPort)
        }
    }

    private fun updateDraft(transform: (NasConfig) -> NasConfig) {
        _uiState.update { state ->
            val editor = state.editor ?: return@update state
            val oldDraft = editor.draft
            val newDraft = transform(oldDraft)
            state.copy(
                editor = editor.copy(
                    draft = newDraft,
                    // 协议/HTTPS 切换会改变默认端口时同步输入框
                    portText = if (newDraft.port != oldDraft.port) newDraft.port.toString() else editor.portText,
                    testResult = ConnectionTestResult.Idle,
                    error = null,
                ),
            )
        }
    }

    // ---------------- 保存 / 删除 / 激活 ----------------

    fun save() {
        val editor = _uiState.value.editor ?: return
        if (editor.saving) return
        val port = editor.portText.toIntOrNull() ?: editor.draft.port
        val draft = editor.draft.copy(port = port)
        val validationError = validate(draft)
        if (validationError != null) {
            _uiState.update { it.copy(editor = editor.copy(error = validationError)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(editor = state.editor?.copy(saving = true, error = null))
            }
            val result = runCatching { repository.upsert(draft) }
            if (result.isSuccess) {
                // 第一套配置自动激活，避免用户漏点「设为目标」
                if (_uiState.value.activeConfigId == null) {
                    runCatching { repository.setActive(draft.id) }
                }
                _uiState.update { state ->
                    state.copy(
                        editor = null,
                        message = if (editor.isNew) "配置「${draft.name}」已保存" else "配置「${draft.name}」已更新",
                    )
                }
            } else {
                val error = result.exceptionOrNull()
                _uiState.update { state ->
                    state.copy(
                        editor = state.editor?.copy(
                            saving = false,
                            error = "保存失败：${error?.message ?: error?.javaClass?.simpleName ?: "未知错误"}",
                        ),
                    )
                }
            }
        }
    }

    fun requestDelete(config: NasConfig) {
        _uiState.update { it.copy(pendingDelete = config) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val config = _uiState.value.pendingDelete ?: return
        viewModelScope.launch {
            val wasActive = _uiState.value.activeConfigId == config.id
            val result = runCatching {
                repository.delete(config.id)
                if (wasActive) repository.setActive(null)
            }
            _uiState.update { state ->
                state.copy(
                    pendingDelete = null,
                    message = if (result.isSuccess) {
                        "已删除配置「${config.name}」（不影响 NAS 上已有的文件）"
                    } else {
                        "删除失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    },
                )
            }
        }
    }

    fun setActive(config: NasConfig) {
        viewModelScope.launch {
            val result = runCatching { repository.setActive(config.id) }
            _uiState.update { state ->
                state.copy(
                    message = if (result.isSuccess) {
                        "已将「${config.name}」设为迁移目标"
                    } else {
                        "设置失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    },
                )
            }
        }
    }

    // ---------------- 测试连接 ----------------

    fun testConnection() {
        val editor = _uiState.value.editor ?: return
        if (editor.saving || editor.testResult == ConnectionTestResult.Testing) return
        val port = editor.portText.toIntOrNull() ?: editor.draft.port
        val draft = editor.draft.copy(port = port)
        // 测试连接只校验连接必需字段（名称可以稍后再填）
        val validationError = validateConnectionTarget(draft)
        if (validationError != null) {
            _uiState.update {
                it.copy(editor = editor.copy(testResult = ConnectionTestResult.Failure(validationError)))
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(editor = state.editor?.copy(testResult = ConnectionTestResult.Testing))
            }
            // 协议 SDK 为阻塞 API：切到 IO 线程；create + testConnection 均可能抛 NasException
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    NasClientFactory.create(draft).use { client -> client.testConnection() }
                }
            }
            val testResult = result.fold(
                onSuccess = { ConnectionTestResult.Success(it) },
                onFailure = { throwable ->
                    val nasException = throwable as? NasException
                    ConnectionTestResult.Failure(
                        reason = nasException?.errorType?.description
                            ?: throwable.message
                            ?: "连接测试失败（${throwable.javaClass.simpleName}）",
                        detail = throwable.message?.takeIf { it.isNotBlank() },
                    )
                },
            )
            _uiState.update { state ->
                state.copy(editor = state.editor?.copy(testResult = testResult))
            }
        }
    }

    // ---------------- 消息 ----------------

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun showMessage(text: String) {
        _uiState.update { it.copy(message = text) }
    }

    private fun validate(draft: NasConfig): String? = when {
        draft.name.isBlank() -> "请填写配置名称"
        else -> validateConnectionTarget(draft)
    }

    /** 连接必需字段校验（测试连接与保存共用） */
    private fun validateConnectionTarget(draft: NasConfig): String? = when {
        draft.host.isBlank() -> "请填写主机地址或 IP"
        draft.port !in 1..65535 -> "端口需在 1~65535 之间"
        draft.protocol == NasProtocol.SMB && draft.smbShare.isBlank() -> "SMB 需要填写共享名（如 home）"
        else -> null
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { NasConfigViewModel(container) }
        }
    }
}
