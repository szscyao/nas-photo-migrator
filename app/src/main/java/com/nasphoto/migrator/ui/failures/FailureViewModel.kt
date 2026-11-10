package com.nasphoto.migrator.ui.failures

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nasphoto.migrator.data.record.FailureRecord
import com.nasphoto.migrator.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 异常管理页 UI 状态。
 */
data class FailureUiState(
    /** 失败记录（按失败时间倒序） */
    val failures: List<FailureRecord> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
)

/**
 * 异常管理页 ViewModel。
 *
 * 数据来源：MigrationStore.failuresFlow()（Room）。所有操作只动「失败记录表」，
 * 绝不删除本地相册文件（铁则 R3：失败绝不删除本地）。
 */
class FailureViewModel(private val container: AppContainer) : ViewModel() {

    private val migrationStore = container.migrationStore

    private val _uiState = MutableStateFlow(FailureUiState())
    val uiState: StateFlow<FailureUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            migrationStore.failuresFlow().collect { records ->
                _uiState.update {
                    it.copy(
                        failures = records.sortedByDescending { record -> record.failedAt },
                        loading = false,
                    )
                }
            }
        }
    }

    /**
     * 重试：移除该文件的失败记录，让它在下次开始迁移时重新进入队列。
     * 注意：本页不做上传/校验/删除，必须回首页由引擎重新处理（铁则 R1 单线程串行）。
     */
    fun retry(record: FailureRecord) {
        viewModelScope.launch {
            val result = runCatching { migrationStore.removeFailureByFileKey(record.fileKey) }
            _uiState.update { state ->
                state.copy(
                    message = if (result.isSuccess) {
                        "已移除「${record.displayName}」的失败记录，请回首页重新开始迁移以重试该文件"
                    } else {
                        "操作失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    },
                )
            }
        }
    }

    /** 删除单条失败记录（仅删记录，本地文件不受影响） */
    fun delete(record: FailureRecord) {
        viewModelScope.launch {
            val result = runCatching { migrationStore.removeFailure(record.id) }
            _uiState.update { state ->
                state.copy(
                    message = if (result.isSuccess) {
                        "已删除该失败记录（本地文件未受影响）"
                    } else {
                        "删除失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    },
                )
            }
        }
    }

    /** 清空全部失败记录（只清记录，不删本地文件） */
    fun clearAll() {
        viewModelScope.launch {
            val result = runCatching { migrationStore.clearFailures() }
            _uiState.update { state ->
                state.copy(
                    message = if (result.isSuccess) {
                        "已清空全部失败记录（只清记录，未删除任何本地文件）"
                    } else {
                        "清空失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    },
                )
            }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun showMessage(text: String) {
        _uiState.update { it.copy(message = text) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { FailureViewModel(container) }
        }
    }
}
