package com.wzydqq.icu.injector

import android.content.Context
import com.wzydqq.icu.location.LocationStore
import com.wzydqq.icu.location.MockLocationProvider

/**
 * 位置伪装管理器
 * 通过 Android Mock Location 机制实现位置伪装
 * 
 * 使用方法：
 * 1. 在设置中开启"开发者选项"
 * 2. 选择"选择模拟位置应用"
 * 3. 选择"位置助手"
 * 4. 在本应用中选择要伪装的位置
 * 5. 点击"开启伪装"
 */
class LocationSpoofManager(private val context: Context) {

    private var isActive = false

    /**
     * 开启位置伪装
     */
    fun start(): Boolean {
        val location = LocationStore.get(context) ?: return false
        return try {
            MockLocationProvider.setMockLocation(context, location)
            isActive = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 停止位置伪装
     */
    fun stop() {
        try {
            MockLocationProvider.clearMockLocation(context)
            isActive = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 是否正在运行
     */
    fun isRunning(): Boolean = isActive

    /**
     * 检查是否可以使用 Mock Location
     */
    fun canUse(): Boolean = MockLocationProvider.canMockLocation(context)
}
