package com.nasphoto.migrator.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.nasphoto.migrator.MigratorApp
import com.nasphoto.migrator.di.AppContainer

/**
 * 取得应用级手动 DI 容器（无 Hilt）。
 *
 * 约定（阶段2 契约）：`(context.applicationContext as MigratorApp).container` 是 UI 唯一依赖入口。
 */
@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as MigratorApp).container
    }
}
