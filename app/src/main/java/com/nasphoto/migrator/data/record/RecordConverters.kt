package com.nasphoto.migrator.data.record

import androidx.room.TypeConverter
import com.nasphoto.migrator.protocol.NasErrorType

/**
 * Room 枚举 <-> 字符串 转换器。
 * 用 name 字符串存储而不是序号，避免枚举顺序调整导致历史数据错乱。
 */
class RecordConverters {

    @TypeConverter
    fun fromLogLevel(level: LogLevel): String = level.name

    @TypeConverter
    fun toLogLevel(value: String): LogLevel =
        runCatching { LogLevel.valueOf(value) }.getOrDefault(LogLevel.INFO)

    @TypeConverter
    fun fromNasErrorType(type: NasErrorType): String = type.name

    @TypeConverter
    fun toNasErrorType(value: String): NasErrorType =
        runCatching { NasErrorType.valueOf(value) }.getOrDefault(NasErrorType.UNKNOWN)
}
