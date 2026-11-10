package com.nasphoto.migrator.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nasphoto.migrator.data.config.AlbumScope
import com.nasphoto.migrator.data.config.AppSettings
import com.nasphoto.migrator.data.config.MediaSortOrder
import com.nasphoto.migrator.data.gallery.AlbumInfo
import com.nasphoto.migrator.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页 UI 状态。
 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val loading: Boolean = true,
    /** 相册列表（仅在「仅选中相册」时按需加载） */
    val albums: List<AlbumInfo> = emptyList(),
    val albumsLoading: Boolean = false,
    val albumsLoaded: Boolean = false,
    val albumsError: String? = null,
    /** 已完成迁移记录条数（重置危险操作前提示） */
    val migratedCount: Int = 0,
    val message: String? = null,
)

/**
 * 设置页 ViewModel。
 *
 * 所有开关/单选项即时写入 SettingsRepository（局部更新，不整表覆盖），
 * 相册列表在切换到「仅选中相册」时才通过 galleryRepository.listAlbums() 加载（避免无权限时查询）。
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settingsRepository = container.settingsRepository
    private val galleryRepository = container.galleryRepository
    private val migrationStore = container.migrationStore

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings, loading = false) }
            }
        }
        viewModelScope.launch {
            migrationStore.migratedCountFlow().collect { count ->
                _uiState.update { it.copy(migratedCount = count) }
            }
        }
    }

    /** 统一局部更新入口：失败时通过 Snackbar 提示，不阻断其他操作 */
    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val result = runCatching { settingsRepository.update(transform) }
            result.exceptionOrNull()?.let { error ->
                _uiState.update {
                    it.copy(message = "保存设置失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    // ---------------- 迁移规则 ----------------

    fun setWifiOnly(value: Boolean) = updateSettings { it.copy(wifiOnly = value) }

    fun setVerifyMd5(value: Boolean) = updateSettings { it.copy(verifyMd5 = value) }

    fun setLowBatteryPause(value: Boolean) = updateSettings { it.copy(lowBatteryPause = value) }

    fun setLowBatteryThreshold(value: Int) = updateSettings {
        it.copy(lowBatteryThreshold = value.coerceIn(5, 50))
    }

    fun setKeepAwake(value: Boolean) = updateSettings { it.copy(keepAwake = value) }

    // ---------------- 扫描范围 ----------------

    fun setSortOrder(order: MediaSortOrder) = updateSettings { it.copy(sortOrder = order) }

    fun setAlbumScope(scope: AlbumScope) {
        updateSettings { it.copy(albumScope = scope) }
        if (scope == AlbumScope.SELECTED) {
            loadAlbums()
        }
    }

    /** 勾选/取消勾选单个相册 */
    fun toggleAlbum(albumId: String) {
        updateSettings { settings ->
            val selected = if (albumId in settings.selectedAlbumIds) {
                settings.selectedAlbumIds - albumId
            } else {
                settings.selectedAlbumIds + albumId
            }
            settings.copy(selectedAlbumIds = selected)
        }
    }

    /** 加载相册列表（force=true 强制刷新） */
    fun loadAlbums(force: Boolean = false) {
        val state = _uiState.value
        if (state.albumsLoading) return
        if (!force && state.albumsLoaded) return
        viewModelScope.launch {
            _uiState.update { it.copy(albumsLoading = true, albumsError = null) }
            val result = runCatching { galleryRepository.listAlbums() }
            _uiState.update { current ->
                current.copy(
                    albumsLoading = false,
                    albumsLoaded = result.isSuccess || current.albumsLoaded,
                    albums = result.getOrNull() ?: current.albums,
                    albumsError = result.exceptionOrNull()?.let { error ->
                        "读取相册失败：${error.message ?: error.javaClass.simpleName}（请检查相册权限）"
                    },
                )
            }
        }
    }

    // ---------------- 危险操作 ----------------

    /** 重置已完成迁移记录（只清记录，不删任何本地/远程文件） */
    fun resetMigratedRecords() {
        viewModelScope.launch {
            val result = runCatching { migrationStore.clearMigrated() }
            _uiState.update { current ->
                current.copy(
                    message = if (result.isSuccess) {
                        "已重置全部已完成迁移记录（本地与 NAS 文件均未受影响）"
                    } else {
                        "重置失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
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
            initializer { SettingsViewModel(container) }
        }
    }
}
