package com.nasphoto.migrator.data.record

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 本地 Room 数据库：已完成记录 / 失败记录 / 日志。
 *
 * 纯本地存储，不上传任何数据（产品硬约束：无云端、无账号）。
 */
@Database(
    entities = [MigratedRecord::class, FailureRecord::class, LogRecord::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(RecordConverters::class)
abstract class MigratorDatabase : RoomDatabase() {

    abstract fun migratedRecordDao(): MigratedRecordDao

    abstract fun failureRecordDao(): FailureRecordDao

    abstract fun logRecordDao(): LogRecordDao

    companion object {
        private const val DB_NAME = "nas_migrator.db"

        @Volatile
        private var INSTANCE: MigratorDatabase? = null

        fun get(context: Context): MigratorDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MigratorDatabase::class.java,
                    DB_NAME,
                )
                    // 记录表属于「可重建的元数据」，表结构升级时用破坏性迁移最稳妥；
                    // 已完成记录丢失只会导致重新扫描上传，绝不会导致误删本地文件。
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
