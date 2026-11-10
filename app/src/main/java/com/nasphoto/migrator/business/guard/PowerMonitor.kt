package com.nasphoto.migrator.business.guard

import android.content.Context
import android.os.BatteryManager

/**
 * 电量守卫（BatteryManager 直接读取系统属性，无广播、无后台开销）。
 *
 * 用途：低电量自动暂停（阶段1 文档 2.2 异常表）。
 * 判定规则由引擎负责组合：[AppSettings.lowBatteryPause] + 未充电 + 电量 < 阈值 → 暂停；
 * 充电后（或电量回升）由引擎的自动恢复监视协程继续任务。
 */
class PowerMonitor(context: Context) {

    private val appContext = context.applicationContext

    private val batteryManager: BatteryManager? =
        appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    /**
     * @param percent 电量百分比（0~100），无法读取时为 -1
     * @param isCharging 是否处于充电/满电状态
     */
    data class Snapshot(val percent: Int, val isCharging: Boolean)

    fun snapshot(): Snapshot {
        val bm = batteryManager ?: return Snapshot(percent = -1, isCharging = false)
        val percent = try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            // 个别定制 ROM 上该属性可能抛异常：按「未知电量」处理，不因电量守卫阻断迁移
            -1
        }
        val status = try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        } catch (e: Exception) {
            BatteryManager.BATTERY_STATUS_UNKNOWN
        }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Snapshot(percent = percent.coerceIn(-1, 100), isCharging = charging)
    }
}
