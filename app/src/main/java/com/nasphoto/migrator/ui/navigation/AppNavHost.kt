package com.nasphoto.migrator.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nasphoto.migrator.ui.failures.FailureScreen
import com.nasphoto.migrator.ui.home.HomeScreen
import com.nasphoto.migrator.ui.logs.LogScreen
import com.nasphoto.migrator.ui.nas.NasConfigScreen
import com.nasphoto.migrator.ui.neighbor.NeighborScreen
import com.nasphoto.migrator.ui.settings.SettingsScreen

/**
 * 底部导航 5 个顶级目的地（路由字符串固定，页面间跳转用这里，禁止硬编码）。
 */
enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "首页", Icons.Filled.Home),
    NAS("nas", "NAS", Icons.Filled.Cloud),
    SETTINGS("settings", "设置", Icons.Filled.Settings),
    FAILURES("failures", "异常", Icons.Filled.Warning),
    LOGS("logs", "日志", Icons.Filled.Description),
}

/**
 * 应用导航宿主：Scaffold（底部导航）+ NavHost（5 个顶级页面）。
 *
 * 说明：
 *  - 使用 navigation-compose，切换 Tab 时 saveState/restoreState，保留各页滚动与状态；
 *  - 外层 Scaffold 不消费系统栏 inset（contentWindowInsets = 0），
 *    各页顶部栏自行处理状态栏、底部导航自行处理导航栏，避免 inset 双重叠加。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: TopDestination.HOME.route

    // 顶级页面切换（保留状态 + 不重复入栈）
    val navigateTo: (TopDestination) -> Unit = remember(navController) {
        { destination: TopDestination ->
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 「网络邻居」向导是全屏流程页（带自己的返回/逐级返回），不显示底部导航
            if (currentRoute != Routes.NEIGHBOR) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    onSelect = navigateTo,
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopDestination.HOME.route) {
                HomeScreen(
                    onNavigateToNas = { navigateTo(TopDestination.NAS) },
                    onNavigateToFailures = { navigateTo(TopDestination.FAILURES) },
                    onNavigateToNeighbor = { navController.navigate(Routes.NEIGHBOR) },
                )
            }
            // 网络邻居向导（单个目的地，内部 SCAN → SHARES → BROWSE 状态机逐级返回）
            composable(Routes.NEIGHBOR) {
                NeighborScreen(
                    onExit = { navController.popBackStack() },
                    onNavigateHome = {
                        val popped = navController.popBackStack(
                            TopDestination.HOME.route,
                            inclusive = false,
                        )
                        if (!popped) {
                            navController.navigate(TopDestination.HOME.route) {
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }
            composable(TopDestination.NAS.route) {
                NasConfigScreen()
            }
            composable(TopDestination.SETTINGS.route) {
                SettingsScreen()
            }
            composable(TopDestination.FAILURES.route) {
                FailureScreen(
                    onNavigateToHome = { navigateTo(TopDestination.HOME) },
                )
            }
            composable(TopDestination.LOGS.route) {
                LogScreen()
            }
        }
    }
}

/** 底部导航栏（5 个目的地等宽） */
@Composable
private fun AppBottomBar(
    currentRoute: String,
    onSelect: (TopDestination) -> Unit,
) {
    NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
        TopDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                    )
                },
                label = { Text(text = destination.label) },
                alwaysShowLabel = true,
            )
        }
    }
}

/** 供页面内部直接使用的路由常量（避免跨包引用枚举时拼错） */
object Routes {
    const val HOME = "home"
    const val NAS = "nas"
    const val SETTINGS = "settings"
    const val FAILURES = "failures"
    const val LOGS = "logs"

    /** 网络邻居向导（全屏流程页，非底部导航目的地） */
    const val NEIGHBOR = "neighbor"
}
