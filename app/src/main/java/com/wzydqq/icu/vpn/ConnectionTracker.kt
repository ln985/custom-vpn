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
 * 
 * 为每个 TCP 连接维护一个真实的 Socket，实现双向数据中继。
 * 解决原始 forwardPacket() 无法处理 TCP 的问题。
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
        var clientSeq: Long = 0,      // 客户端期望的下一个序列号
        var serverSeq: Long = 0,      // 服务端期望的下一个序列号
        var established: Boolean = false,
        var closed: Boolean = false
    )

    private val connections = ConcurrentHashMap<ConnectionKey, TrackedConnection>()

    /**
     * 处理一个 TCP 包，返回需要写回 VPN 接口的响应包
     */
    fun processTcpPacket(packet: IpPacket, vpnOutputStream: java.io.FileOutputStream): List<ByteArray> {
        val key = ConnectionKey(packet.sourceIp, packet.sourcePort, packet.destIp, packet.destPort)
        val responses = mutableListOf<ByteArray>()

        when {
            packet.isTcpSyn() && !packet.isTcpAck() -> {
                // SYN 包：建立新连接
                handleSyn(key, packet, vpnOutputStream)
            }
            packet.isTcpRst() -> {
                // RST 包：关闭连接
                handleRst(key)
            }
            packet.isTcpFin() -> {
                // FIN 包：关闭连接
                handleFin(key, packet, vpnOutputStream)
            }
            packet.isTcpAck() && packet.payloadLength > 0 -> {
                // 带数据的 ACK 包：转发数据
                handleData(key, packet, vpnOutputStream)
            }
            packet.isTcpAck() -> {
                // 纯 ACK：更新序列号
                val conn = connections[key]
                if (conn != null) {
                    conn.clientSeq = packet.getTcpSequenceNumber() + maxOf(packet.payloadLength, 1)
                }
            }
        }

        return responses
    }

    private fun handleSyn(key: ConnectionKey, packet: IpPacket, vpnOutputStream: java.io.FileOutputStream) {
        // 如果已有连接，先清理
        connections[key]?.let { closeConnection(it) }

        Thread {
            try {
                val socket = Socket()
                protectSocket(socket)
                socket.connect(InetSocketAddress(key.dstIp, key.dstPort), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS

                val conn = TrackedConnection(
                    socket = socket,
                    outputStream = socket.getOutputStream(),
                    inputStream = socket.getInputStream(),
                    clientSeq = packet.getTcpSequenceNumber() + 1, // SYN 消耗 1 个序列号
                    serverSeq = 0
                )

                connections[key] = conn

                // 发送 SYN-ACK 回 VPN
                val synAck = buildTcpPacket(
                    srcIp = key.dstIp, srcPort = key.dstPort,
                    dstIp = key.srcIp, dstPort = key.srcPort,
                    seqNum = 1000L, // 服务端初始序列号
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

                // 启动接收线程：从真实服务器读取数据，写回 VPN
                startReceiverThread(key, conn, vpnOutputStream, packet)

                Log.d(TAG, "TCP connected: ${key.dstIp}:${key.dstPort}")
            } catch (e: Exception) {
                Log.e(TAG, "TCP connect failed: ${key.dstIp}:${key.dstPort} - ${e.message}")
                // 发送 RST
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
        }.start()
    }

    private fun handleData(key: ConnectionKey, packet: IpPacket, vpnOutputStream: java.io.FileOutputStream) {
        val conn = connections[key] ?: return
        if (conn.closed) return

        try {
            val payload = packet.rawData.sliceArray(packet.payloadOffset until packet.rawData.size)
            if (payload.isNotEmpty()) {
                conn.outputStream.write(payload)
                conn.outputStream.flush()
                conn.clientSeq = packet.getTcpSequenceNumber() + payload.size

                // 发送 ACK
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
            Log.e(TAG, "TCP data send error: ${e.message}")
            closeConnection(conn)
            connections.remove(key)
        }
    }

    private fun handleFin(key: ConnectionKey, packet: IpPacket, vpnOutputStream: java.io.FileOutputStream) {
        val conn = connections[key]

        // 发送 FIN-ACK
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
            var seqCounter = 1001L

            try {
                while (!conn.closed && !Thread.interrupted()) {
                    val bytesRead = conn.inputStream.read(buffer)
                    if (bytesRead <= 0) break

                    val payload = buffer.copyOf(bytesRead)

                    val tcpPacket = buildTcpPacket(
                        srcIp = key.dstIp, srcPort = key.dstPort,
                        dstIp = key.srcIp, dstPort = key.srcPort,
                        seqNum = seqCounter,
                        ackNum = conn.clientSeq,
                        flags = 0x18, // PSH + ACK
                        payload = payload,
                        originalPacket = originalPacket
                    )

                    synchronized(vpnOutputStream) {
                        vpnOutputStream.write(tcpPacket)
                        vpnOutputStream.flush()
                    }

                    seqCounter += bytesRead
                    conn.serverSeq = seqCounter
                }
            } catch (e: Exception) {
                if (!conn.closed) {
                    Log.d(TAG, "TCP receiver ended: ${key.dstIp}:${key.dstPort} - ${e.message}")
                }
            } finally {
                if (!conn.closed) {
                    // 发送 FIN
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
     * 构建 TCP 响应包（IP头 + TCP头 + payload）
     */
    private fun buildTcpPacket(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int,
        payload: ByteArray,
        originalPacket: IpPacket
    ): ByteArray {
        // TCP 头部 (20 bytes, no options)
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
        tcpHeader[14] = 0xFF.toByte() // Window size
        tcpHeader[15] = 0xFF.toByte()
        // Checksum (16-17) 和 Urgent pointer (18-19) 设为 0

        val tcpPacket = tcpHeader + payload

        // IP 头部
        val ipHeader = ByteArray(20)
        ipHeader[0] = 0x45 // Version 4, IHL 5
        ipHeader[1] = 0    // DSCP/ECN
        val totalLength = 20 + tcpPacket.size
        ipHeader[2] = (totalLength shr 8).toByte()
        ipHeader[3] = totalLength.toByte()
        ipHeader[4] = 0    // Identification
        ipHeader[5] = 0
        ipHeader[6] = 0x40 // Don't fragment
        ipHeader[7] = 0
        ipHeader[8] = 64   // TTL
        ipHeader[9] = 6    // Protocol: TCP
        ipHeader[10] = 0   // Checksum
        ipHeader[11] = 0

        // Source IP
        val srcParts = srcIp.split(".")
        ipHeader[12] = srcParts[0].toInt().toByte()
        ipHeader[13] = srcParts[1].toInt().toByte()
        ipHeader[14] = srcParts[2].toInt().toByte()
        ipHeader[15] = srcParts[3].toInt().toByte()

        // Dest IP
        val dstParts = dstIp.split(".")
        ipHeader[16] = dstParts[0].toInt().toByte()
        ipHeader[17] = dstParts[1].toInt().toByte()
        ipHeader[18] = dstParts[2].toInt().toByte()
        ipHeader[19] = dstParts[3].toInt().toByte()

        return ipHeader + tcpPacket
    }

    /**
     * 清理所有连接
     */
    fun shutdown() {
        connections.values.forEach { closeConnection(it) }
        connections.clear()
    }

    /**
     * 清理超时连接
     */
    fun cleanupStale() {
        val staleKeys = connections.entries
            .filter { it.value.closed }
            .map { it.key }
        staleKeys.forEach { connections.remove(it) }
    }
}
