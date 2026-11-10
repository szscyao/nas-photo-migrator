package com.nasphoto.migrator.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ============================================================================
// UI 展示格式化工具：字节 → MB/GB、时间戳 → yyyy-MM-dd HH:mm 等
// 所有页面统一使用这里的实现，避免各自造轮子导致展示不一致。
// ============================================================================

private val DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

private val DATE_TIME_SECONDS_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA)

private val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.CHINA)

/** 字节数 → 人类可读（B/KB/MB/GB/TB），负数或未知显示 "--" */
fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "--"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    val unit = units[index]
    return when {
        value >= 100.0 -> String.format(Locale.CHINA, "%.0f %s", value, unit)
        value >= 10.0 -> String.format(Locale.CHINA, "%.1f %s", value, unit)
        else -> String.format(Locale.CHINA, "%.2f %s", value, unit)
    }
}

/** 时间戳（毫秒）→ "yyyy-MM-dd HH:mm"；<=0 显示 "--" */
fun formatDateTime(millis: Long): String {
    if (millis <= 0L) return "--"
    return DATE_TIME_FORMATTER.format(
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
    )
}

/** 时间戳（毫秒）→ "yyyy-MM-dd HH:mm:ss"；<=0 显示 "--" */
fun formatDateTimeSeconds(millis: Long): String {
    if (millis <= 0L) return "--"
    return DATE_TIME_SECONDS_FORMATTER.format(
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
    )
}

/** 时间戳（毫秒）→ "HH:mm:ss"（日志行首时间） */
fun formatTime(millis: Long): String {
    if (millis <= 0L) return "--:--:--"
    return TIME_FORMATTER.format(
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
    )
}

/** 时间戳（毫秒）→ "yyyyMMdd-HHmmss"（导出文件名用） */
fun formatFileTimestamp(millis: Long): String {
    return FILE_TIMESTAMP_FORMATTER.format(
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
    )
}

/** 0f~1f → "42%" */
fun formatPercent(fraction: Float): String {
    val percent = (fraction.coerceIn(0f, 1f) * 100f).toInt()
    return "$percent%"
}

/** 数量加千分位，如 1,204 */
fun formatCount(count: Int): String = String.format(Locale.CHINA, "%,d", count)

/** 时长（毫秒）→ "1 小时 05 分" / "03 分 21 秒" */
fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "--"
    val totalSeconds = millis / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> String.format(Locale.CHINA, "%d 小时 %02d 分", hours, minutes)
        minutes > 0 -> String.format(Locale.CHINA, "%02d 分 %02d 秒", minutes, seconds)
        else -> String.format(Locale.CHINA, "%d 秒", seconds)
    }
}
