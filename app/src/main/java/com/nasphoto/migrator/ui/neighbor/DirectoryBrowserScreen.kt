package com.nasphoto.migrator.ui.neighbor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nasphoto.migrator.business.discovery.SmbEntry
import com.nasphoto.migrator.ui.components.AppTopBar
import com.nasphoto.migrator.ui.components.EmptyState
import com.nasphoto.migrator.ui.components.SectionCard
import com.nasphoto.migrator.ui.util.formatBytes

/**
 * 目录浏览页（向导第③步）。
 *
 * 逐级点击文件夹进入下一级；面包屑可跳回任意层级；文件只展示不可进入；
 * 底部/顶部均有「使用此文件夹」——选定后由 ViewModel 生成标准 NasConfig 并设为迁移目标。
 */
@Composable
fun DirectoryBrowserScreen(
    state: NeighborUiState,
    viewModel: NeighborViewModel,
) {
    val device = state.currentDevice ?: return
    val share = state.currentShare ?: return
    val entriesError = state.entriesError

    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            title = "浏览：$share",
            subtitle = buildString {
                append(device.displayName)
                if (state.currentPath.isNotEmpty()) {
                    append(" / ")
                    append(state.currentPath)
                }
            },
            navigationIcon = {
                IconButton(onClick = viewModel::navigateUp) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级")
                }
            },
            actions = {
                TextButton(
                    onClick = viewModel::useCurrentFolder,
                    enabled = !state.savingConfig,
                ) {
                    Text(text = "使用此文件夹")
                }
            },
        )

        Breadcrumb(
            share = share,
            path = state.currentPath,
            onSelect = viewModel::navigateToPath,
        )
        HorizontalDivider()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.entriesLoading && state.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在读取目录…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                entriesError != null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    SectionCard(title = "无法读取该目录") {
                        Text(
                            text = entriesError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::retryLoad) {
                                Text(text = "重试")
                            }
                            TextButton(onClick = viewModel::requestAuth) {
                                Text(text = "输入账号密码")
                            }
                        }
                        TextButton(onClick = viewModel::navigateUp) {
                            Text(text = "返回上一级")
                        }
                    }
                }

                state.entries.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Folder,
                    title = "空文件夹",
                    description = "这个目录里没有文件或子文件夹。也可以直接「使用此文件夹」作为迁移根目录。",
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    if (state.entriesLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(
                            items = state.entries,
                            key = { entry -> entry.name + if (entry.isDirectory) "/" else "" },
                        ) { entry ->
                            DirectoryEntryRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) {
                                        viewModel.enterDirectory(entry.name)
                                    }
                                },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }
                    }
                }
            }
        }

        // 底部操作栏：「上一级」+「使用此文件夹」
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::navigateUp,
                    enabled = !state.savingConfig,
                ) {
                    Text(text = "上一级")
                }
                Button(
                    onClick = viewModel::useCurrentFolder,
                    enabled = !state.savingConfig,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.savingConfig) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "正在生成配置…")
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "使用此文件夹")
                    }
                }
            }
        }
    }
}

/** 面包屑：共享名（根） / 一级 / 二级 …，可点击回到任意层级 */
@Composable
private fun Breadcrumb(
    share: String,
    path: String,
    onSelect: (String) -> Unit,
) {
    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onSelect("") }) {
            Text(
                text = share,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        segments.forEachIndexed { index, segment ->
            Text(
                text = "/",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val targetPath = segments.take(index + 1).joinToString("/")
            val isLast = index == segments.lastIndex
            if (isLast) {
                Text(
                    text = segment,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                TextButton(onClick = { onSelect(targetPath) }) {
                    Text(
                        text = segment,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** 目录项：文件夹可进入；文件灰显只展示大小，不可进入 */
@Composable
private fun DirectoryEntryRow(
    entry: SmbEntry,
    onClick: () -> Unit,
) {
    val contentColor = if (entry.isDirectory) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isDirectory, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDirectory && entry.sizeBytes >= 0L) {
                Text(
                    text = formatBytes(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!entry.isDirectory) {
            Text(
                text = "文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
