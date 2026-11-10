package com.nasphoto.migrator.ui.neighbor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nasphoto.migrator.ui.components.AppTopBar
import com.nasphoto.migrator.ui.components.EmptyState
import com.nasphoto.migrator.ui.components.SectionCard

/**
 * 共享文件夹列表页（向导第②步）。
 *
 * 展示 [NeighborViewModel] 中当前设备（[NeighborUiState.currentDevice]）的共享列表，
 * 点击某个共享进入目录浏览；加载失败按错误类型展示人话提示并支持重试/输入账号密码。
 */
@Composable
fun ShareListScreen(
    state: NeighborUiState,
    viewModel: NeighborViewModel,
) {
    val device = state.currentDevice ?: return
    val sharesError = state.sharesError

    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            title = device.displayName,
            subtitle = "${device.ip} · 选择共享文件夹",
            navigationIcon = {
                IconButton(onClick = viewModel::backToScan) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设备列表")
                }
            },
        )

        when {
            state.sharesLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "正在读取共享文件夹…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            sharesError != null -> ShareErrorContent(
                message = sharesError,
                onRetry = viewModel::retryLoad,
                onEnterCredentials = viewModel::requestAuth,
                onBack = viewModel::backToScan,
            )

            state.shares.isEmpty() -> EmptyState(
                icon = Icons.Filled.Folder,
                title = "没有共享文件夹",
                description = "这台设备没有向当前账号开放共享。可以试试输入账号密码，或确认 NAS 已开启 SMB 共享。",
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::requestAuth) {
                            Text(text = "输入账号密码")
                        }
                        TextButton(onClick = viewModel::retryLoad) {
                            Text(text = "重试")
                        }
                    }
                },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "shares_header") {
                    Column {
                        Text(
                            text = "找到 ${state.shares.size} 个共享文件夹",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "点击共享进入后，逐级点到存放照片/视频的文件夹。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(items = state.shares, key = { "share-$it" }) { share ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openShare(share) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                            Text(
                                text = share,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item(key = "shares_tip") {
                    Text(
                        text = "看不到想要的共享？可能是账号权限限制，点右上角返回后可换设备或输入其他账号。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 共享列表加载失败：错误人话 + 重试 / 输入账号密码 / 返回 */
@Composable
private fun ShareErrorContent(
    message: String,
    onRetry: () -> Unit,
    onEnterCredentials: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)) {
        SectionCard(title = "无法读取共享文件夹") {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) {
                    Text(text = "重试")
                }
                TextButton(onClick = onEnterCredentials) {
                    Text(text = "输入账号密码")
                }
            }
            Spacer(modifier = Modifier.width(2.dp))
            TextButton(onClick = onBack) {
                Text(text = "返回设备列表")
            }
        }
    }
}
