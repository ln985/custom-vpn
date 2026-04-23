package com.wzydqq.icu.vpn

import android.util.Log
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP 中继器 - 正确转发非 DNS 的 UDP 流量
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
    private val activeReceivers = ConcurrentHashMap<UdpKey, Boolean>()

    fun processUdpPacket(packet: IpPacket, vpnOutputStream: java.io.FileOutputStream) {
        val key = UdpKey(packet.sourceIp, packet.sourcePort, packet.destIp, packet.destPort)
        val payload = packet.rawData.sliceArray(packet.payloadOffset until packet.rawData.size)

        if (payload.isEmpty()) return

        val session = sessions.getOrPut(key) {
            createSession(key) ?: return
        }

        session.lastActive = System.currentTimeMillis()

        try {
            val buffer = ByteBuffer.wrap(payload)
            session.channel.send(buffer, session.remoteAddress)
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

            UdpSession(
                channel = channel,
                remoteAddress = InetSocketAddress(key.dstIp, key.dstPort)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP session: ${e.message}")
            null
        }
    }

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
                        Thread.sleep(5)
                    }

                    if (System.currentTimeMillis() - session.lastActive > TIMEOUT_MS) break
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
        val srcParts = srcIp.split(".").map { it.toInt() }
        val dstParts = dstIp.split(".").map { it.toInt() }

        // UDP 头部
        val udpHeader = ByteArray(8)
        udpHeader[0] = (srcPort shr 8).toByte()
        udpHeader[1] = srcPort.toByte()
        udpHeader[2] = (dstPort shr 8).toByte()
        udpHeader[3] = dstPort.toByte()
        val udpLength = payload.size + 8
        udpHeader[4] = (udpLength shr 8).toByte()
        udpHeader[5] = udpLength.toByte()
        udpHeader[6] = 0 // Checksum placeholder
        udpHeader[7] = 0

        // 计算 UDP 校验和（伪头部）
        val pseudoHeader = ByteArray(12)
        pseudoHeader[0] = srcParts[0].toByte()
        pseudoHeader[1] = srcParts[1].toByte()
        pseudoHeader[2] = srcParts[2].toByte()
        pseudoHeader[3] = srcParts[3].toByte()
        pseudoHeader[4] = dstParts[0].toByte()
        pseudoHeader[5] = dstParts[1].toByte()
        pseudoHeader[6] = dstParts[2].toByte()
        pseudoHeader[7] = dstParts[3].toByte()
        pseudoHeader[8] = 0
        pseudoHeader[9] = 17 // UDP protocol
        pseudoHeader[10] = (udpLength shr 8).toByte()
        pseudoHeader[11] = udpLength.toByte()

        val udpChecksum = computeChecksum(pseudoHeader + udpHeader + payload)
        udpHeader[6] = (udpChecksum shr 8).toByte()
        udpHeader[7] = udpChecksum.toByte()

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
        ipHeader[9] = 17 // UDP
        ipHeader[10] = 0
        ipHeader[11] = 0
        ipHeader[12] = srcParts[0].toByte()
        ipHeader[13] = srcParts[1].toByte()
        ipHeader[14] = srcParts[2].toByte()
        ipHeader[15] = srcParts[3].toByte()
        ipHeader[16] = dstParts[0].toByte()
        ipHeader[17] = dstParts[1].toByte()
        ipHeader[18] = dstParts[2].toByte()
        ipHeader[19] = dstParts[3].toByte()

        val ipChecksum = computeChecksum(ipHeader)
        ipHeader[10] = (ipChecksum shr 8).toByte()
        ipHeader[11] = ipChecksum.toByte()

        return ipHeader + udpPacket
    }

    private fun computeChecksum(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i < data.size - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.toInt().inv() and 0xFFFF)
    }

    fun shutdown() {
        sessions.values.forEach { try { it.channel.close() } catch (_: Exception) {} }
        sessions.clear()
        activeReceivers.clear()
    }
}
