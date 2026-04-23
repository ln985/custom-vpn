package com.wzydqq.icu.vpn

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * TCP 连接追踪器 - 正确转发非目标 TCP 流量
 */
class ConnectionTracker(private val protectSocket: (Socket) -> Boolean) {

    companion object {
        private const val TAG = "ConnectionTracker"
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 30000
        private const val BUFFER_SIZE = 65535
    }

    data class ConnectionKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    data class TrackedConnection(
        val socket: Socket,
        val outputStream: OutputStream,
        val inputStream: InputStream,
        var clientSeq: Long = 0,
        var serverSeq: Long = 0,
        var established: Boolean = false,
        var closed: Boolean = false
    )

    private val connections = ConcurrentHashMap<ConnectionKey, TrackedConnection>()

    fun processTcpPacket(packet: IpPacket, vpnOutputStream: java.io.FileOutputStream) {
        val key = ConnectionKey(packet.sourceIp, packet.sourcePort, packet.destIp, packet.destPort)

        when {
            packet.isTcpSyn() && !packet.isTcpAck() -> {
                handleSyn(key, packet, vpnOutputStream)
            }
            packet.isTcpRst() -> {
                handleRst(key)
            }
            packet.isTcpFin() -> {
                handleFin(key, packet, vpnOutputStream)
            }
            packet.isTcpAck() && packet.payloadLength > 0 -> {
                handleData(key, packet, vpnOutputStream)
            }
            packet.isTcpAck() -> {
                val conn = connections[key]
                if (conn != null && conn.established) {
                    conn.clientSeq = packet.getTcpSequenceNumber() + maxOf(packet.payloadLength, 0)
                }
            }
        }
    }

    private fun handleSyn(key: ConnectionKey, packet: IpPacket, vpnOutputStream: java.io.FileOutputStream) {
        connections[key]?.let { closeConnection(it) }
        connections.remove(key)

        Thread {
            try {
                val socket = Socket()
                protectSocket(socket)
                socket.connect(InetSocketAddress(key.dstIp, key.dstPort), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS

                val serverSeq = (System.currentTimeMillis() and 0xFFFFFFF).toLong()

                val conn = TrackedConnection(
                    socket = socket,
                    outputStream = socket.getOutputStream(),
                    inputStream = socket.getInputStream(),
                    clientSeq = packet.getTcpSequenceNumber() + 1,
                    serverSeq = serverSeq + 1
                )
                connections[key] = conn

                // SYN-ACK
                val synAck = buildTcpPacket(
                    srcIp = key.dstIp, srcPort = key.dstPort,
                    dstIp = key.srcIp, dstPort = key.srcPort,
                    seqNum = serverSeq,
                    ackNum = conn.clientSeq,
                    flags = 0x12, // SYN + ACK
                    payload = ByteArray(0),
                    originalPacket = packet
                )
                synchronized(vpnOutputStream) {
                    vpnOutputStream.write(synAck)
                    vpnOutputStream.flush()
                }

                conn.established = true
                startReceiverThread(key, conn, vpnOutputStream, packet)
                Log.d(TAG, "TCP connected: ${key.dstIp}:${key.dstPort}")
            } catch (e: Exception) {
                Log.e(TAG, "TCP connect failed: ${key.dstIp}:${key.dstPort} - ${e.message}")
                val rst = buildTcpPacket(
                    srcIp = key.dstIp, srcPort = key.dstPort,
                    dstIp = key.srcIp, dstPort = key.srcPort,
                    seqNum = 0, ackNum = packet.getTcpSequenceNumber() + 1,
                    flags = 0x14, // RST + ACK
                    payload = ByteArray(0),
                    originalPacket = packet
                )
                try {
                    synchronized(vpnOutputStream) {
                        vpnOutputStream.write(rst)
                        vpnOutputStream.flush()
                    }
                } catch (_: Exception) {}
            }
        }.apply {
            name = "TCP-Connect-${key.dstIp}:${key.dstPort}"
            isDaemon = true
            start()
        }
    }

    private fun handleData(key: ConnectionKey, packet: IpPacket, vpnOutputStream: java.io.FileOutputStream) {
        val conn = connections[key] ?: return
        if (!conn.established || conn.closed) return

        try {
            val payload = packet.rawData.sliceArray(packet.payloadOffset until packet.rawData.size)
            if (payload.isNotEmpty()) {
                conn.outputStream.write(payload)
                conn.outputStream.flush()
                conn.clientSeq = packet.getTcpSequenceNumber() + payload.size

                // ACK
                val ack = buildTcpPacket(
                    srcIp = key.dstIp, srcPort = key.dstPort,
                    dstIp = key.srcIp, dstPort = key.srcPort,
                    seqNum = conn.serverSeq,
                    ackNum = conn.clientSeq,
                    flags = 0x10, // ACK
                    payload = ByteArray(0),
                    originalPacket = packet
                )
                synchronized(vpnOutputStream) {
                    vpnOutputStream.write(ack)
                    vpnOutputStream.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP data error: ${e.message}")
            closeConnection(conn)
            connections.remove(key)
        }
    }

    private fun handleFin(key: ConnectionKey, packet: IpPacket, vpnOutputStream: java.io.FileOutputStream) {
        val conn = connections[key]
        val finAck = buildTcpPacket(
            srcIp = key.dstIp, srcPort = key.dstPort,
            dstIp = key.srcIp, dstPort = key.srcPort,
            seqNum = conn?.serverSeq ?: 0,
            ackNum = packet.getTcpSequenceNumber() + 1,
            flags = 0x11, // FIN + ACK
            payload = ByteArray(0),
            originalPacket = packet
        )
        try {
            synchronized(vpnOutputStream) {
                vpnOutputStream.write(finAck)
                vpnOutputStream.flush()
            }
        } catch (_: Exception) {}

        conn?.let {
            closeConnection(it)
            connections.remove(key)
        }
    }

    private fun handleRst(key: ConnectionKey) {
        connections[key]?.let {
            closeConnection(it)
            connections.remove(key)
        }
    }

    private fun startReceiverThread(
        key: ConnectionKey,
        conn: TrackedConnection,
        vpnOutputStream: java.io.FileOutputStream,
        originalPacket: IpPacket
    ) {
        Thread {
            val buffer = ByteArray(BUFFER_SIZE)

            try {
                while (!conn.closed && !Thread.interrupted()) {
                    val bytesRead = conn.inputStream.read(buffer)
                    if (bytesRead <= 0) break

                    val payload = buffer.copyOf(bytesRead)

                    val tcpPacket = buildTcpPacket(
                        srcIp = key.dstIp, srcPort = key.dstPort,
                        dstIp = key.srcIp, dstPort = key.srcPort,
                        seqNum = conn.serverSeq,
                        ackNum = conn.clientSeq,
                        flags = 0x18, // PSH + ACK
                        payload = payload,
                        originalPacket = originalPacket
                    )

                    synchronized(vpnOutputStream) {
                        vpnOutputStream.write(tcpPacket)
                        vpnOutputStream.flush()
                    }

                    conn.serverSeq += bytesRead
                }
            } catch (e: Exception) {
                if (!conn.closed) {
                    Log.d(TAG, "TCP receiver ended: ${key.dstIp}:${key.dstPort}")
                }
            } finally {
                if (!conn.closed) {
                    try {
                        val fin = buildTcpPacket(
                            srcIp = key.dstIp, srcPort = key.dstPort,
                            dstIp = key.srcIp, dstPort = key.srcPort,
                            seqNum = conn.serverSeq,
                            ackNum = conn.clientSeq,
                            flags = 0x11, // FIN + ACK
                            payload = ByteArray(0),
                            originalPacket = originalPacket
                        )
                        synchronized(vpnOutputStream) {
                            vpnOutputStream.write(fin)
                            vpnOutputStream.flush()
                        }
                    } catch (_: Exception) {}

                    conn.closed = true
                    connections.remove(key)
                }
            }
        }.apply {
            name = "TCP-Recv-${key.dstIp}:${key.dstPort}"
            isDaemon = true
            start()
        }
    }

    private fun closeConnection(conn: TrackedConnection) {
        conn.closed = true
        try { conn.socket.close() } catch (_: Exception) {}
    }

    /**
     * 构建 TCP 包（IP头 + TCP头 + payload），带正确的校验和
     */
    private fun buildTcpPacket(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int,
        payload: ByteArray,
        originalPacket: IpPacket
    ): ByteArray {
        // TCP 头部 (20 bytes)
        val tcpHeader = ByteArray(20)
        tcpHeader[0] = (srcPort shr 8).toByte()
        tcpHeader[1] = srcPort.toByte()
        tcpHeader[2] = (dstPort shr 8).toByte()
        tcpHeader[3] = dstPort.toByte()
        tcpHeader[4] = (seqNum shr 24).toByte()
        tcpHeader[5] = (seqNum shr 16).toByte()
        tcpHeader[6] = (seqNum shr 8).toByte()
        tcpHeader[7] = seqNum.toByte()
        tcpHeader[8] = (ackNum shr 24).toByte()
        tcpHeader[9] = (ackNum shr 16).toByte()
        tcpHeader[10] = (ackNum shr 8).toByte()
        tcpHeader[11] = ackNum.toByte()
        tcpHeader[12] = 0x50.toByte() // Data offset: 5 (20 bytes)
        tcpHeader[13] = flags.toByte()
        tcpHeader[14] = 0xFF.toByte() // Window
        tcpHeader[15] = 0xFF.toByte()
        tcpHeader[16] = 0 // Checksum placeholder
        tcpHeader[17] = 0
        tcpHeader[18] = 0 // Urgent pointer
        tcpHeader[19] = 0

        // 计算 TCP 校验和（需要伪头部）
        val srcParts = srcIp.split(".").map { it.toInt() }
        val dstParts = dstIp.split(".").map { it.toInt() }

        val tcpLength = tcpHeader.size + payload.size
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
        pseudoHeader[9] = 6 // TCP protocol
        pseudoHeader[10] = (tcpLength shr 8).toByte()
        pseudoHeader[11] = tcpLength.toByte()

        val tcpChecksum = computeChecksum(pseudoHeader + tcpHeader + payload)
        tcpHeader[16] = (tcpChecksum shr 8).toByte()
        tcpHeader[17] = tcpChecksum.toByte()

        val tcpPacket = tcpHeader + payload

        // IP 头部 (20 bytes)
        val ipHeader = ByteArray(20)
        ipHeader[0] = 0x45
        ipHeader[1] = 0
        val totalLength = 20 + tcpPacket.size
        ipHeader[2] = (totalLength shr 8).toByte()
        ipHeader[3] = totalLength.toByte()
        ipHeader[4] = 0 // ID
        ipHeader[5] = 0
        ipHeader[6] = 0x40 // Don't fragment
        ipHeader[7] = 0
        ipHeader[8] = 64 // TTL
        ipHeader[9] = 6 // TCP
        ipHeader[10] = 0 // Checksum placeholder
        ipHeader[11] = 0
        ipHeader[12] = srcParts[0].toByte()
        ipHeader[13] = srcParts[1].toByte()
        ipHeader[14] = srcParts[2].toByte()
        ipHeader[15] = srcParts[3].toByte()
        ipHeader[16] = dstParts[0].toByte()
        ipHeader[17] = dstParts[1].toByte()
        ipHeader[18] = dstParts[2].toByte()
        ipHeader[19] = dstParts[3].toByte()

        // 计算 IP 校验和
        val ipChecksum = computeChecksum(ipHeader)
        ipHeader[10] = (ipChecksum shr 8).toByte()
        ipHeader[11] = ipChecksum.toByte()

        return ipHeader + tcpPacket
    }

    /**
     * RFC 1071 校验和算法
     */
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
        connections.values.forEach { closeConnection(it) }
        connections.clear()
    }
}
