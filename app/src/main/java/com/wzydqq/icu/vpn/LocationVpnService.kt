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

    companion object {
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
            // 建立 VPN 接口
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

            // 启动数据包处理线程
            vpnThread = Thread { processPackets() }.apply {
                name = "VPN-Thread"
                start()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun processPackets() {
        val fd = vpnInterface ?: return
        val inputStream = FileInputStream(fd.fileDescriptor)
        val outputStream = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)

        // 连接到远程 DNS 代理
        val channel = DatagramChannel.open()
        protect(channel.socket())
        channel.connect(InetSocketAddress("8.8.8.8", 53))

        try {
            while (isRunning) {
                // 从 VPN 接口读取数据包
                val length = inputStream.read(buffer.array())
                if (length <= 0) {
                    Thread.sleep(10)
                    continue
                }

                buffer.limit(length)
                buffer.position(0)

                // 解析 IP 包
                val packet = try {
                    IpPacket.parse(buffer)
                } catch (e: Exception) {
                    null
                }

                if (packet != null) {
                    // 只处理 DNS 查询和到目标服务器的 TCP
                    when {
                        packet.isDnsQuery() -> {
                            // 转发 DNS 查询并可能修改响应
                            handleDns(packet, channel, outputStream)
                        }
                        packet.isTcpToTarget() -> {
                            // 拦截到腾讯地图 API 的 TCP 连接
                            handleTcpIntercept(packet, outputStream)
                        }
                        else -> {
                            // 其他流量直接转发
                            forwardPacket(packet, channel, outputStream)
                        }
                    }
                }

                buffer.clear()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { channel.close() } catch (_: Exception) {}
        }
    }

    private fun handleDns(packet: IpPacket, channel: DatagramChannel, outputStream: FileOutputStream) {
        // 检查是否是目标域名的 DNS 查询
        val domain = packet.extractDnsQuery()
        if (domain != null && domain.contains("apis.map.qq.com")) {
            // 返回本地地址，让请求走我们的拦截器
            val response = packet.buildDnsResponse(domain, "10.0.0.2")
            outputStream.write(response)
        } else {
            // 其他 DNS 查询直接转发
            forwardPacket(packet, channel, outputStream)
        }
    }

    private fun handleTcpIntercept(packet: IpPacket, outputStream: FileOutputStream) {
        // 通过本地代理处理 TCP 连接
        // 这里会修改腾讯地图 API 的响应
        Thread {
            try {
                val proxy = LocalProxy(this)
                proxy.intercept(packet, outputStream)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun forwardPacket(packet: IpPacket, channel: DatagramChannel, outputStream: FileOutputStream) {
        try {
            val buffer = ByteBuffer.wrap(packet.rawData)
            channel.write(buffer)

            buffer.clear()
            channel.read(buffer)

            outputStream.write(buffer.array(), 0, buffer.position())
        } catch (e: Exception) {
            // Ignore forwarding errors
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null

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
