package com.nasphoto.migrator

import android.app.Application
import com.nasphoto.migrator.di.AppContainer

/**
 * 应用入口。
 *
 * 纯本地工具：不做任何数据上报、不初始化任何云服务 SDK。
 * 依赖注入采用**手动容器**（阶段1决策：规模可控，避免 Hilt 带来的构建复杂度）。
 */
class MigratorApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
