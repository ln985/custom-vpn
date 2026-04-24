package com.wzydqq.icu.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareConfig
import com.github.megatronking.netbare.NetBareListener
import com.github.megatronking.netbare.NetBareService
import com.github.megatronking.netbare.http.HttpInterceptorFactory
import com.github.megatronking.netbare.ssl.JKS
import com.wzydqq.icu.App
import com.wzydqq.icu.injector.LocationInterceptorFactory
import com.wzydqq.icu.ui.MainActivity

class LocationVpnService : NetBareService(), NetBareListener {

    companion object {
        private const val TAG = "LocationVpnService"
        private const val CHANNEL_ID = "location_vpn_channel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            // 先检查证书是否已安装
            if (!JKS.isInstalled(context, App.JSK_ALIAS)) {
                try {
                    JKS.install(context, App.JSK_ALIAS, App.JSK_PASSWORD)
                } catch (e: Exception) {
                    Log.e(TAG, "Certificate install failed: ${e.message}")
                }
                return
            }

            val intent = Intent(context, LocationVpnService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationVpnService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        NetBare.get().registerNetBareListener(this)
    }

    override fun onDestroy() {
        NetBare.get().unregisterNetBareListener(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_START == intent.action) {
            setupNetBare()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun setupNetBare() {
        try {
            val jks = App.instance.getJKS()

            // 创建拦截器工厂列表
            val interceptorFactories: List<HttpInterceptorFactory> = listOf(
                    LocationInterceptorFactory(this)
            )

            // 使用 defaultHttpConfig 创建配置（包含 JKS 和拦截器）
            val config = NetBareConfig.defaultHttpConfig(jks, interceptorFactories)

            // 启动 NetBare
            NetBare.get().start(config)
            Log.d(TAG, "NetBare setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup NetBare: ${e.message}")
        }
    }

    override fun notificationId(): Int = NOTIFICATION_ID

    @NonNull
    override fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("📍 位置助手")
                    .setContentText("位置伪装服务运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                    .setContentTitle("📍 位置助手")
                    .setContentText("位置伪装服务运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                    CHANNEL_ID,
                    "位置伪装服务",
                    NotificationManager.IMPORTANCE_LOW)
            channel.setDescription("位置助手 VPN 服务")
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // NetBareListener
    override fun onServiceStarted() {
        Log.d(TAG, "NetBare service started")
    }

    override fun onServiceStopped() {
        Log.d(TAG, "NetBare service stopped")
    }
}
