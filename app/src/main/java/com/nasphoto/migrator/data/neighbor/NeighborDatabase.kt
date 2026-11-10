package com.nasphoto.migrator.data.neighbor

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 「网络邻居 —— 我的设备」独立 Room 数据库。
 *
 * 为什么单独建库（而不是加进既有 [com.nasphoto.migrator.data.record.MigratorDatabase]）：
 *  - 既有迁移记录库（已完成/失败/日志）承载的是「断点续传正确性」的关键数据，schema 必须稳定；
 *  - 「我的设备」只是可随时重建的展示缓存，独立成库后它的版本演进/破坏性升级
 *    不会触碰迁移记录库，符合「增量开发、不动既有数据层」的约束。
 *
 * 数据库文件名：`neighbor.db`（与 `nas_migrator.db` 分离）。
 */
@Database(
    entities = [SavedDeviceEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NeighborDatabase : RoomDatabase() {

    abstract fun savedDeviceDao(): SavedDeviceDao

    companion object {
        private const val DB_NAME = "neighbor.db"

        @Volatile
        private var INSTANCE: NeighborDatabase? = null

        fun get(context: Context): NeighborDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NeighborDatabase::class.java,
                    DB_NAME,
                )
                    // 「我的设备」属于可再生数据：表结构升级时破坏性迁移成本最低
                    // （丢失的只是收藏列表，重新扫描一次即可恢复），也避免写迁移脚本引入风险。
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
