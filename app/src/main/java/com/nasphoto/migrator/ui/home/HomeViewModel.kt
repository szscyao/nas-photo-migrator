package com.nasphoto.migrator.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nasphoto.migrator.business.model.MigrationPreview
import com.nasphoto.migrator.business.model.MigrationState
import com.nasphoto.migrator.data.config.NasConfig
import com.nasphoto.migrator.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 首页 UI 状态（ViewModel 向 UI 暴露的唯一数据快照）。
 */
data class HomeUiState(
    /** 引擎实时状态（状态机/进度/网络电量/NAS 剩余空间） */
    val state: MigrationState = MigrationState(),
    /** 当前激活的 NAS 配置（null 表示未配置，首页提示去配置） */
    val activeConfig: NasConfig? = null,
    /** 已保存的配置数量（区分「未配置」与「有配置但未激活」） */
    val configCount: Int = 0,
    /** 累计已释放空间（来自 migrationStore.freedBytesFlow()，跨会话累计） */
    val freedBytes: Long = 0L,
    /** 失败记录条数（历史累计，来自 failure_records 表） */
    val failureCount: Int = 0,
    /** 历史已完成迁移数量（断点续传记录数） */
    val migratedCount: Int = 0,
    /** 开始前预览（engine.loadPreview() 结果） */
    val preview: MigrationPreview? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    /** 一次性提示消息（Snackbar） */
    val message: String? = null,
)

/**
 * 「开始迁移」前的预览弹窗状态：先调 engine.loadPreview() 展示待迁移数量与大小，再确认开始。
 */
data class StartPreviewState(
    val loading: Boolean = true,
    val preview: MigrationPreview? = null,
    val error: String? = null,
)

/**
 * 首页 ViewModel。
 *
 * 只依赖 AppContainer 暴露的接口：
 *  - engine.state / engine.loadPreview()：状态与预览（不直接调用启动命令，启动走前台服务静态方法）；
 *  - migrationStore.*Flow：已释放空间、历史完成数、失败记录数；
 *  - nasConfigRepository.*Flow：当前激活配置。
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val engine = container.engine
    private val configRepository = container.nasConfigRepository
    private val migrationStore = container.migrationStore

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _startPreview = MutableStateFlow<StartPreviewState?>(null)
    val startPreview: StateFlow<StartPreviewState?> = _startPreview.asStateFlow()

    init {
        // 引擎状态（状态机 + 进度 + 网络/电量）
        viewModelScope.launch {
            engine.state.collect { state ->
                _uiState.update { it.copy(state = state) }
            }
        }
        // 当前激活配置
        viewModelScope.launch {
            configRepository.activeConfigFlow.collect { config ->
                _uiState.update { it.copy(activeConfig = config) }
            }
        }
        // 全部配置数量
        viewModelScope.launch {
            configRepository.configsFlow.collect { configs ->
                _uiState.update { it.copy(configCount = configs.size) }
            }
        }
        // 累计已释放空间
        viewModelScope.launch {
            migrationStore.freedBytesFlow().collect { freed ->
                _uiState.update { it.copy(freedBytes = freed) }
            }
        }
        // 历史失败记录数
        viewModelScope.launch {
            migrationStore.failureCountFlow().collect { count ->
                _uiState.update { it.copy(failureCount = count) }
            }
        }
        // 历史完成数
        viewModelScope.launch {
            migrationStore.migratedCountFlow().collect { count ->
                _uiState.update { it.copy(migratedCount = count) }
            }
        }
    }

    /**
     * 刷新「待迁移」统计（engine.loadPreview()，由相册仓库按设置扫描与去重）。
     * 仅在已获得相册读取权限时调用，避免 SecurityException。
     */
    fun refreshPreview() {
        if (_uiState.value.previewLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(previewLoading = true, previewError = null) }
            val result = runCatching { engine.loadPreview() }
            _uiState.update { current ->
                current.copy(
                    previewLoading = false,
                    preview = result.getOrNull() ?: current.preview,
                    previewError = result.exceptionOrNull()?.let { error ->
                        "统计待迁移数据失败：${error.message ?: error.javaClass.simpleName}"
                    },
                )
            }
        }
    }

    /** 点击「开始」：先调用 loadPreview() 展示预览，再由 UI 弹确认框 */
    fun requestStartPreview() {
        if (_startPreview.value != null) return
        _startPreview.value = StartPreviewState(loading = true)
        viewModelScope.launch {
            val result = runCatching { engine.loadPreview() }
            val preview = result.getOrNull()
            _startPreview.value = StartPreviewState(
                loading = false,
                preview = preview,
                error = result.exceptionOrNull()?.let { error ->
                    "无法统计待迁移文件：${error.message ?: error.javaClass.simpleName}"
                },
            )
            if (preview != null) {
                _uiState.update { it.copy(preview = preview, previewLoading = false, previewError = null) }
            }
        }
    }

    /** 关闭开始预览弹窗（取消，或确认开始后由 UI 调用） */
    fun dismissStartPreview() {
        _startPreview.value = null
    }

    /** 展示一次性 Snackbar 提示 */
    fun showMessage(text: String) {
        _uiState.update { it.copy(message = text) }
    }

    /** 消费提示消息（Snackbar 展示后调用，避免重复弹出） */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
