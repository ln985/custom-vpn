package com.wzydqq.icu.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.wzydqq.icu.location.LocationStore
import com.wzydqq.icu.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class LocationVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile
    private var isRunning = false
    private var vpnThread: Thread? = null
    private var connectionTracker: ConnectionTracker? = null
    private var udpRelay: UdpRelay? = null

    companion object {
        private const val TAG = "LocationVpnService"
        const val ACTION_START = "com.wzydqq.icu.vpn.START"
        const val ACTION_STOP = "com.wzydqq.icu.vpn.STOP"
        const val CHANNEL_ID = "location_vpn_channel"
        const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, LocationVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        val location = LocationStore.get(this)
        if (location == null) {
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, createNotification())

        try {
            vpnInterface = Builder()
                .setSession("位置助手")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(1500)
                .setBlocking(true)
                .establish()

            if (vpnInterface == null) {
                stopSelf()
                return
            }

            isRunning = true

            // 初始化连接追踪器和 UDP 中继器
            connectionTracker = ConnectionTracker { socket ->
                protect(socket)
                true
            }
            udpRelay = UdpRelay { channel ->
                protect(channel.socket())
                true
            }

            vpnThread = Thread { processPackets() }.apply {
                name = "VPN-Thread"
                start()
            }

            Log.d(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "VPN start failed: ${e.message}")
            stopVpn()
        }
    }

    private fun processPackets() {
        val fd = vpnInterface ?: return
        val inputStream = FileInputStream(fd.fileDescriptor)
        val outputStream = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)

        try {
            while (isRunning) {
                val length = inputStream.read(buffer.array())
                if (length <= 0) {
                    Thread.sleep(5)
                    continue
                }

                buffer.limit(length)
                buffer.position(0)

                val packet = try {
                    IpPacket.parse(buffer)
                } catch (e: Exception) {
                    null
                }

                if (packet != null) {
                    when {
                        // DNS 查询：只劫持 apis.map.qq.com
                        packet.isDnsQuery() -> {
                            handleDns(packet, outputStream)
                        }
                        // TCP 到目标（10.0.0.2 = 被 DNS 劫持的 apis.map.qq.com）
                        packet.isTcpToTarget() -> {
                            handleTcpIntercept(packet, outputStream)
                        }
                        // TCP 到其他地址：通过连接追踪器正确转发
                        packet.protocol == IpPacket.PROTOCOL_TCP -> {
                            connectionTracker?.processTcpPacket(packet, outputStream)
                        }
                        // UDP 到其他地址（非 DNS）：通过 UDP 中继器转发
                        packet.protocol == IpPacket.PROTOCOL_UDP -> {
                            udpRelay?.processUdpPacket(packet, outputStream)
                        }
                        // ICMP 等其他协议：静默丢弃（无法转发）
                        else -> {
                            // 不处理
                        }
                    }
                }

                buffer.clear()
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(TAG, "Packet processing error: ${e.message}")
            }
        } finally {
            connectionTracker?.shutdown()
            udpRelay?.shutdown()
        }
    }

    /**
     * 处理 DNS 查询
     * 只劫持 apis.map.qq.com 的查询，其他 DNS 正常转发
     */
    private fun handleDns(packet: IpPacket, outputStream: FileOutputStream) {
        val domain = packet.extractDnsQuery()

        if (domain != null && domain.contains("apis.map.qq.com")) {
            // 返回本地地址，让 HTTP 请求走我们的拦截器
            val response = packet.buildDnsResponse(domain, "10.0.0.2")
            synchronized(outputStream) {
                outputStream.write(response)
                outputStream.flush()
            }
            Log.d(TAG, "DNS hijack: $domain -> 10.0.0.2")
        } else {
            // 其他 DNS 查询通过 UDP 中继正常转发
            udpRelay?.processUdpPacket(packet, outputStream)
        }
    }

    /**
     * 处理到 apis.map.qq.com (10.0.0.2) 的 TCP 拦截
     * 只处理 HTTP (port 80) 的拦截修改，HTTPS 直接透传
     */
    private fun handleTcpIntercept(packet: IpPacket, outputStream: FileOutputStream) {
        Thread {
            try {
                val proxy = LocalProxy(this)
                proxy.intercept(packet, outputStream)
            } catch (e: Exception) {
                Log.e(TAG, "TCP intercept error: ${e.message}")
            }
        }.apply {
            name = "TCP-Intercept-${packet.sourcePort}"
            start()
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null

        connectionTracker?.shutdown()
        connectionTracker = null
        udpRelay?.shutdown()
        udpRelay = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        Log.d(TAG, "VPN stopped")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "位置伪装服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "位置助手 VPN 服务"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

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
}
