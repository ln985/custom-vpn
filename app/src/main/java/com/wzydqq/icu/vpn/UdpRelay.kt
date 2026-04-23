package com.wzydqq.icu.vpn

import android.util.Log
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP 中继器 - 正确转发非 DNS 的 UDP 流量
 * 
 * 为每个 UDP "连接"（源端口+目标端口）维护一个 DatagramChannel，
 * 实现双向数据中继。解决原始 forwardPacket() 无法处理 UDP 的问题。
 */
class UdpRelay(private val protectChannel: (DatagramChannel) -> Boolean) {

    companion object {
        private const val TAG = "UdpRelay"
        private const val TIMEOUT_MS = 60000L
    }

    data class UdpKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    data class UdpSession(
        val channel: DatagramChannel,
        val remoteAddress: InetSocketAddress,
        var lastActive: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<UdpKey, UdpSession>()

    /**
     * 处理一个 UDP 包，返回需要写回 VPN 接口的响应包列表
     */
    fun processUdpPacket(
        packet: IpPacket,
        vpnOutputStream: java.io.FileOutputStream
    ) {
        val key = UdpKey(packet.sourceIp, packet.sourcePort, packet.destIp, packet.destPort)
        val payload = packet.rawData.sliceArray(packet.payloadOffset until packet.rawData.size)

        if (payload.isEmpty()) return

        val session = sessions.getOrPut(key) {
            createSession(key) ?: return
        }

        session.lastActive = System.currentTimeMillis()

        try {
            // 发送到真实目标
            val buffer = ByteBuffer.wrap(payload)
            session.channel.send(buffer, session.remoteAddress)

            // 启动接收（如果还没启动）
            startReceiverIfNeeded(key, session, vpnOutputStream, packet)
        } catch (e: Exception) {
            Log.e(TAG, "UDP send error: ${e.message}")
            sessions.remove(key)
            try { session.channel.close() } catch (_: Exception) {}
        }
    }

    private fun createSession(key: UdpKey): UdpSession? {
        return try {
            val channel = DatagramChannel.open()
            protectChannel(channel)
            channel.configureBlocking(false)
            channel.socket().soTimeout = 0

            val remoteAddress = InetSocketAddress(key.dstIp, key.dstPort)

            UdpSession(
                channel = channel,
                remoteAddress = remoteAddress
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP session: ${e.message}")
            null
        }
    }

    private val activeReceivers = ConcurrentHashMap<UdpKey, Boolean>()

    private fun startReceiverIfNeeded(
        key: UdpKey,
        session: UdpSession,
        vpnOutputStream: java.io.FileOutputStream,
        originalPacket: IpPacket
    ) {
        if (activeReceivers.putIfAbsent(key, true) != null) return

        Thread {
            val buffer = ByteBuffer.allocate(65535)

            try {
                while (!Thread.interrupted()) {
                    buffer.clear()
                    val sender = session.channel.receive(buffer)

                    if (sender != null) {
                        buffer.flip()
                        val responseData = ByteArray(buffer.remaining())
                        buffer.get(responseData)

                        if (responseData.isNotEmpty()) {
                            // 构建 UDP 响应包写回 VPN
                            val responsePacket = buildUdpResponse(
                                srcIp = key.dstIp, srcPort = key.dstPort,
                                dstIp = key.srcIp, dstPort = key.srcPort,
                                payload = responseData,
                                originalPacket = originalPacket
                            )
                            synchronized(vpnOutputStream) {
                                vpnOutputStream.write(responsePacket)
                                vpnOutputStream.flush()
                            }
                        }

                        session.lastActive = System.currentTimeMillis()
                    } else {
                        // 没有数据，短暂休眠避免忙等
                        Thread.sleep(5)
                    }

                    // 检查超时
                    if (System.currentTimeMillis() - session.lastActive > TIMEOUT_MS) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (!Thread.interrupted()) {
                    Log.d(TAG, "UDP receiver ended: ${key.dstIp}:${key.dstPort}")
                }
            } finally {
                activeReceivers.remove(key)
                sessions.remove(key)
                try { session.channel.close() } catch (_: Exception) {}
            }
        }.apply {
            name = "UDP-Recv-${key.dstIp}:${key.dstPort}"
            isDaemon = true
            start()
        }
    }

    private fun buildUdpResponse(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        payload: ByteArray,
        originalPacket: IpPacket
    ): ByteArray {
        // UDP 头部
        val udpHeader = ByteArray(8)
        udpHeader[0] = (srcPort shr 8).toByte()
        udpHeader[1] = srcPort.toByte()
        udpHeader[2] = (dstPort shr 8).toByte()
        udpHeader[3] = dstPort.toByte()
        val udpLength = payload.size + 8
        udpHeader[4] = (udpLength shr 8).toByte()
        udpHeader[5] = udpLength.toByte()
        // Checksum 留空
        udpHeader[6] = 0
        udpHeader[7] = 0

        val udpPacket = udpHeader + payload

        // IP 头部
        val ipHeader = ByteArray(20)
        ipHeader[0] = 0x45
        ipHeader[1] = 0
        val totalLength = 20 + udpPacket.size
        ipHeader[2] = (totalLength shr 8).toByte()
        ipHeader[3] = totalLength.toByte()
        ipHeader[4] = 0
        ipHeader[5] = 0
        ipHeader[6] = 0x40
        ipHeader[7] = 0
        ipHeader[8] = 64
        ipHeader[9] = 17 // Protocol: UDP
        ipHeader[10] = 0
        ipHeader[11] = 0

        val srcParts = srcIp.split(".")
        ipHeader[12] = srcParts[0].toInt().toByte()
        ipHeader[13] = srcParts[1].toInt().toByte()
        ipHeader[14] = srcParts[2].toInt().toByte()
        ipHeader[15] = srcParts[3].toInt().toByte()

        val dstParts = dstIp.split(".")
        ipHeader[16] = dstParts[0].toInt().toByte()
        ipHeader[17] = dstParts[1].toInt().toByte()
        ipHeader[18] = dstParts[2].toInt().toByte()
        ipHeader[19] = dstParts[3].toInt().toByte()

        return ipHeader + udpPacket
    }

    /**
     * 清理所有会话
     */
    fun shutdown() {
        sessions.values.forEach { try { it.channel.close() } catch (_: Exception) {} }
        sessions.clear()
        activeReceivers.clear()
    }

    /**
     * 清理超时会话
     */
    fun cleanupStale() {
        val now = System.currentTimeMillis()
        val staleKeys = sessions.entries
            .filter { now - it.value.lastActive > TIMEOUT_MS }
            .map { it.key }
        staleKeys.forEach { key ->
            sessions.remove(key)?.let { try { it.channel.close() } catch (_: Exception) {} }
        }
    }
}
