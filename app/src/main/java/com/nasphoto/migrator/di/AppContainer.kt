package com.nasphoto.migrator.di

import android.app.Application
import com.nasphoto.migrator.business.discovery.JcifsSmbBrowser
import com.nasphoto.migrator.business.discovery.NeighborScanner
import com.nasphoto.migrator.business.discovery.SmbBrowser
import com.nasphoto.migrator.business.discovery.SubnetScanner
import com.nasphoto.migrator.business.engine.MigrationEngine
import com.nasphoto.migrator.business.engine.MigrationEngineApi
import com.nasphoto.migrator.business.log.AppLogger
import com.nasphoto.migrator.business.log.Logger
import com.nasphoto.migrator.data.config.NasConfigRepository
import com.nasphoto.migrator.data.config.NasConfigRepositoryImpl
import com.nasphoto.migrator.data.config.SettingsRepository
import com.nasphoto.migrator.data.config.SettingsRepositoryImpl
import com.nasphoto.migrator.data.gallery.GalleryRepository
import com.nasphoto.migrator.data.gallery.GalleryRepositoryImpl
import com.nasphoto.migrator.data.neighbor.EncryptedNeighborCredentialStore
import com.nasphoto.migrator.data.neighbor.NeighborCredentialStore
import com.nasphoto.migrator.data.neighbor.RoomSavedDeviceRepository
import com.nasphoto.migrator.data.neighbor.SavedDeviceRepository
import com.nasphoto.migrator.data.record.MigrationStore
import com.nasphoto.migrator.data.record.MigrationStoreImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动依赖注入容器（应用级单例）。
 *
 * 依赖方向严格单向：UI → 业务引擎 → 协议/数据层，不允许反向依赖。
 */
class AppContainer(private val app: Application) {

    /** 应用级协程作用域（不会随页面销毁而取消） */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---------------- 数据层 ----------------
    val nasConfigRepository: NasConfigRepository = NasConfigRepositoryImpl(app)

    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(app)

    val galleryRepository: GalleryRepository = GalleryRepositoryImpl(app)

    val migrationStore: MigrationStore = MigrationStoreImpl(app)

    // ---------------- 网络邻居（增量功能：本网段扫描 / SMB 只读浏览 / 我的设备） ----------------
    // 注意：这一组依赖只服务于「网络邻居向导」，与迁移引擎完全解耦，不参与
    // 「上传 → 校验 → 删除本地」闭环（business/engine 一行未改）。

    /** 「我的设备」本地表（独立 Room 数据库，不动既有迁移记录库） */
    val savedDeviceRepository: SavedDeviceRepository by lazy { RoomSavedDeviceRepository(app) }

    /** 邻居凭据加密存储（EncryptedSharedPreferences，复用 EncryptedPrefsFactory 机制） */
    val neighborCredentialStore: NeighborCredentialStore by lazy { EncryptedNeighborCredentialStore(app) }

    /** 本网段 /24 扫描器（TCP 445 探测，32 并发） */
    val neighborScanner: NeighborScanner by lazy { SubnetScanner(app) }

    /** SMB 只读浏览（jcifs-ng：列共享 / 列目录 / 探测设备名） */
    val smbBrowser: SmbBrowser by lazy { JcifsSmbBrowser() }

    // ---------------- 日志 ----------------
    val logger: Logger by lazy { AppLogger(migrationStore, appScope) }

    // ---------------- 核心迁移引擎（全局唯一实例，前台服务与 UI 共享） ----------------
    val engine: MigrationEngineApi by lazy {
        MigrationEngine(
            appContext = app,
            galleryRepository = galleryRepository,
            nasConfigRepository = nasConfigRepository,
            settingsRepository = settingsRepository,
            migrationStore = migrationStore,
            logger = logger,
        )
    }
}
