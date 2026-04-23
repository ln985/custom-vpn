package com.wzydqq.icu.vpn

import java.nio.ByteBuffer

/**
 * 简化的 IP 数据包解析器
 */
class IpPacket(
    val version: Int,
    val headerLength: Int,
    val protocol: Int, // 6=TCP, 17=UDP
    val sourceIp: String,
    val destIp: String,
    val sourcePort: Int,
    val destPort: Int,
    val rawData: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int
) {
    companion object {
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
        const val TARGET_API_HOST = "apis.map.qq.com"

        fun parse(buffer: ByteBuffer): IpPacket? {
            try {
                val data = ByteArray(buffer.remaining())
                buffer.get(data)

                if (data.size < 20) return null

                val version = (data[0].toInt() and 0xF0) shr 4
                if (version != 4) return null // 只处理 IPv4

                val headerLength = (data[0].toInt() and 0x0F) * 4
                val protocol = data[9].toInt() and 0xFF

                val sourceIp = "${data[12].toInt() and 0xFF}.${data[13].toInt() and 0xFF}.${data[14].toInt() and 0xFF}.${data[15].toInt() and 0xFF}"
                val destIp = "${data[16].toInt() and 0xFF}.${data[17].toInt() and 0xFF}.${data[18].toInt() and 0xFF}.${data[19].toInt() and 0xFF}"

                var sourcePort = 0
                var destPort = 0
                var payloadOffset = headerLength
                var payloadLength = data.size - headerLength

                if (protocol == PROTOCOL_TCP && data.size >= headerLength + 20) {
                    val tcpOffset = headerLength
                    sourcePort = ((data[tcpOffset].toInt() and 0xFF) shl 8) or (data[tcpOffset + 1].toInt() and 0xFF)
                    destPort = ((data[tcpOffset + 2].toInt() and 0xFF) shl 8) or (data[tcpOffset + 3].toInt() and 0xFF)
                    val tcpHeaderLength = ((data[tcpOffset + 12].toInt() and 0xF0) shr 4) * 4
                    payloadOffset = headerLength + tcpHeaderLength
                    payloadLength = data.size - payloadOffset
                } else if (protocol == PROTOCOL_UDP && data.size >= headerLength + 8) {
                    val udpOffset = headerLength
                    sourcePort = ((data[udpOffset].toInt() and 0xFF) shl 8) or (data[udpOffset + 1].toInt() and 0xFF)
                    destPort = ((data[udpOffset + 2].toInt() and 0xFF) shl 8) or (data[udpOffset + 3].toInt() and 0xFF)
                    payloadOffset = headerLength + 8
                    payloadLength = data.size - payloadOffset
                }

                return IpPacket(
                    version = version,
                    headerLength = headerLength,
                    protocol = protocol,
                    sourceIp = sourceIp,
                    destIp = destIp,
                    sourcePort = sourcePort,
                    destPort = destPort,
                    rawData = data,
                    payloadOffset = payloadOffset,
                    payloadLength = payloadLength
                )
            } catch (e: Exception) {
                return null
            }
        }
    }

    fun isDnsQuery(): Boolean = protocol == PROTOCOL_UDP && destPort == 53

    fun isTcpToTarget(): Boolean = protocol == PROTOCOL_TCP && destPort == 80

    fun extractDnsQuery(): String? {
        if (!isDnsQuery() || payloadLength < 12) return null

        try {
            val payload = rawData.sliceArray(payloadOffset until rawData.size)
            val sb = StringBuilder()
            var i = 12 // Skip DNS header

            while (i < payload.size) {
                val len = payload[i].toInt() and 0xFF
                if (len == 0) break
                i++
                if (i + len > payload.size) break
                if (sb.isNotEmpty()) sb.append('.')
                for (j in 0 until len) {
                    sb.append(payload[i + j].toInt().toChar())
                }
                i += len
            }

            return if (sb.isNotEmpty()) sb.toString() else null
        } catch (e: Exception) {
            return null
        }
    }

    fun buildDnsResponse(domain: String, ip: String): ByteArray {
        val payload = rawData.sliceArray(payloadOffset until rawData.size)
        val response = payload.copyOf()

        // 设置 QR 位（响应）
        response[2] = (response[2].toInt() or 0x80).toByte()
        // 设置 RCODE = 0 (No Error)
        response[3] = (response[3].toInt() and 0xF0).toByte()
        // ANCOUNT = 1
        response[6] = 0
        response[7] = 1

        // 追加回答记录
        val answer = mutableListOf<Byte>()
        // NAME (指针指向查询)
        answer.add(0xC0.toByte())
        answer.add(0x0C)
        // TYPE A
        answer.add(0)
        answer.add(1)
        // CLASS IN
        answer.add(0)
        answer.add(1)
        // TTL 60s
        answer.add(0)
        answer.add(0)
        answer.add(0)
        answer.add(60)
        // RDLENGTH
        answer.add(0)
        answer.add(4)
        // RDATA
        for (octet in ip.split(".")) {
            answer.add(octet.toInt().toByte())
        }

        val fullResponse = response + answer.toByteArray()

        // 构建 UDP 响应
        val udpHeader = ByteArray(8)
        udpHeader[0] = rawData[headerLength + 2] // 源端口 = 目标端口
        udpHeader[1] = rawData[headerLength + 3]
        udpHeader[2] = rawData[headerLength] // 目标端口 = 源端口
        udpHeader[3] = rawData[headerLength + 1]
        val udpLength = fullResponse.size + 8
        udpHeader[4] = (udpLength shr 8).toByte()
        udpHeader[5] = udpLength.toByte()

        val udpPacket = udpHeader + fullResponse

        // 构建 IP 响应
        val ipHeader = rawData.sliceArray(0 until headerLength)
        // 交换源和目标 IP
        for (i in 12..15) {
            ipHeader[i] = rawData[i + 4]
            ipHeader[i + 4] = rawData[i]
        }
        // 更新总长度
        val totalLength = headerLength + udpPacket.size
        ipHeader[2] = (totalLength shr 8).toByte()
        ipHeader[3] = totalLength.toByte()
        // 清零校验和
        ipHeader[10] = 0
        ipHeader[11] = 0

        return ipHeader + udpPacket
    }
}
