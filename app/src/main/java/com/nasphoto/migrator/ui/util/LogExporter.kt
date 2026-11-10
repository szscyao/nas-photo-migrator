package com.nasphoto.migrator.ui.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.nasphoto.migrator.data.record.LogRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 日志导出/分享工具。
 *
 * 优先方案：把日志写为文本文件到 `cacheDir/logs/`，再用 FileProvider
 * （authority = `${applicationId}.fileprovider`，与 AndroidManifest 一致）生成 content:// URI 分享。
 * 兜底方案：FileProvider 配置异常/写入失败时，退化为 ACTION_SEND 纯文本分享，绝不崩溃。
 */
object LogExporter {

    /** 准备分享 Intent（IO 线程写文件；失败自动降级为纯文本分享） */
    suspend fun prepareShareIntent(context: Context, logs: List<LogRecord>): Intent =
        withContext(Dispatchers.IO) {
            val fileUri = runCatching { writeLogFile(context, logs) }.getOrNull()
            if (fileUri != null) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    putExtra(Intent.EXTRA_SUBJECT, "相册迁移日志（${logs.size} 条）")
                    putExtra(Intent.EXTRA_TEXT, "相册迁移日志，共 ${logs.size} 条，见附件。")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(context.contentResolver, "相册迁移日志", fileUri)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "相册迁移日志")
                    putExtra(Intent.EXTRA_TEXT, buildLogText(logs))
                }
            }
        }

    /** 写日志文本文件并返回 FileProvider content URI */
    private fun writeLogFile(context: Context, logs: List<LogRecord>): Uri {
        val dir = File(context.cacheDir, "logs")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建日志导出目录：${dir.absolutePath}")
        }
        val file = File(dir, "nas-migrator-log-${formatFileTimestamp(System.currentTimeMillis())}.txt")
        file.writeText(buildLogText(logs), Charsets.UTF_8)
        // authority 必须与 AndroidManifest 的 ${applicationId}.fileprovider 一致
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** 生成纯文本日志内容（同时用于文件导出与纯文本兜底分享） */
    fun buildLogText(logs: List<LogRecord>): String = buildString {
        appendLine("相册逐张边传边删迁移NAS工具 - 日志导出")
        appendLine("导出时间：${formatDateTimeSeconds(System.currentTimeMillis())}")
        appendLine("日志条数：${logs.size}")
        appendLine("------------------------------------------------------------")
        logs.forEach { record ->
            appendLine(
                "${formatDateTimeSeconds(record.timestamp)} " +
                    "[${record.level.name}] [${record.tag}] ${record.message}",
            )
        }
    }
}
