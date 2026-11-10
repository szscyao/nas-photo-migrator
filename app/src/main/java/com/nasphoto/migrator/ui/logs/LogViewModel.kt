package com.nasphoto.migrator.ui.logs

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nasphoto.migrator.data.record.LogLevel
import com.nasphoto.migrator.data.record.LogRecord
import com.nasphoto.migrator.di.AppContainer
import com.nasphoto.migrator.ui.util.LogExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 日志级别筛选（ALL 展示全部含 DEBUG；其余按级别精确过滤）。
 */
enum class LogFilter(val label: String, val level: LogLevel?) {
    ALL("全部", null),
    INFO("信息", LogLevel.INFO),
    WARN("警告", LogLevel.WARN),
    ERROR("错误", LogLevel.ERROR),
}

/**
 * 日志页 UI 状态。
 */
data class LogUiState(
    /** 全部日志（时间倒序，最多 500 条） */
    val logs: List<LogRecord> = emptyList(),
    val filter: LogFilter = LogFilter.ALL,
    val loading: Boolean = true,
    val message: String? = null,
    /** 待启动的分享 Intent（由 UI 消费后置空） */
    val shareIntent: Intent? = null,
) {
    /** 当前筛选后的日志 */
    val filteredLogs: List<LogRecord>
        get() {
            val level = filter.level ?: return logs
            return logs.filter { it.level == level }
        }
}

/**
 * 日志页 ViewModel。
 *
 * 数据来源：MigrationStore.logsFlow(500)，UI 内按级别筛选；
 * 导出/分享：读取全量日志 → LogExporter 写 cacheDir/logs/ 文本 → FileProvider URI（失败降级纯文本）。
 */
class LogViewModel(private val container: AppContainer) : ViewModel() {

    private val migrationStore = container.migrationStore

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            migrationStore.logsFlow(500).collect { logs ->
                _uiState.update {
                    it.copy(
                        logs = logs.sortedByDescending { record -> record.timestamp },
                        loading = false,
                    )
                }
            }
        }
    }

    fun setFilter(filter: LogFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun clearLogs() {
        viewModelScope.launch {
            val result = runCatching { migrationStore.clearLogs() }
            _uiState.update { state ->
                state.copy(
                    message = if (result.isSuccess) {
                        "已清空全部日志"
                    } else {
                        "清空失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    },
                )
            }
        }
    }

    /** 导出日志：优先文件分享，失败自动降级纯文本（LogExporter 内部处理） */
    fun exportLogs(context: Context) {
        viewModelScope.launch {
            val logs = runCatching { migrationStore.allLogs() }
                .getOrNull()
                ?.sortedByDescending { record -> record.timestamp }
                ?: _uiState.value.logs
            if (logs.isEmpty()) {
                _uiState.update { it.copy(message = "暂无日志可导出") }
                return@launch
            }
            val intent = LogExporter.prepareShareIntent(context, logs)
            _uiState.update { it.copy(shareIntent = intent) }
        }
    }

    fun consumeShareIntent() {
        _uiState.update { it.copy(shareIntent = null) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun showMessage(text: String) {
        _uiState.update { it.copy(message = text) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LogViewModel(container) }
        }
    }
}
