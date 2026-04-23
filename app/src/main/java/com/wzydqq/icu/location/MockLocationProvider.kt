package com.wzydqq.icu.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Mock Location Provider - 通过 Android 系统的 Mock Location 机制提供伪装位置
 * 需要在开发者选项中设置本应用为模拟位置应用
 */
object MockLocationProvider {

    private const val PROVIDER_NAME = LocationManager.GPS_PROVIDER

    /**
     * 检查是否有权限设置 Mock Location
     */
    fun canMockLocation(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.getProviders(true)
        return providers.contains("gps") || providers.contains("network")
    }

    /**
     * 设置伪装位置
     * 注意：需要在 开发者选项 -> 选择模拟位置应用 中选择本应用
     */
    @SuppressLint("MissingPermission")
    fun setMockLocation(context: Context, location: com.wzydqq.icu.location.SelectedLocation) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        try {
            locationManager.removeTestProvider(PROVIDER_NAME)
        } catch (e: Exception) {
            // Provider might not exist yet
        }

        try {
            locationManager.addTestProvider(
                PROVIDER_NAME,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                0,     // powerRequirement
                5      // accuracy
            )
            locationManager.setTestProviderEnabled(PROVIDER_NAME, true)

            val mockLocation = Location(PROVIDER_NAME).apply {
                latitude = location.lat
                longitude = location.lng
                altitude = 0.0
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                accuracy = 1.0f
                speed = 0.0f
                bearing = 0.0f
            }

            locationManager.setTestProviderLocation(PROVIDER_NAME, mockLocation)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 清除伪装位置
     */
    fun clearMockLocation(context: Context) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager.removeTestProvider(PROVIDER_NAME)
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * 检查是否已设置为模拟位置应用
     */
    fun isMockLocationApp(context: Context): Boolean {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.addTestProvider(
                PROVIDER_NAME, false, false, false, false, true, true, true, 0, 5
            )
            locationManager.removeTestProvider(PROVIDER_NAME)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}
