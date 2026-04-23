package com.wzydqq.icu.vpn

import java.nio.ByteBuffer

class IpPacket(
    val version: Int,
    val headerLength: Int,
    val protocol: Int,
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
        const val FAKE_DNS_IP = "10.0.0.2"

        fun parse(buffer: ByteBuffer): IpPacket? {
            try {
                val data = ByteArray(buffer.remaining())
                buffer.get(data)

                if (data.size < 20) return null

                val version = (data[0].toInt() and 0xF0) shr 4
                if (version != 4) return null

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

    /**
     * 只拦截通过 DNS 劫持解析到 10.0.0.2 的 apis.map.qq.com 流量
     * 不再拦截所有 80/443 端口的 TCP 流量
     */
    fun isTcpToTarget(): Boolean = protocol == PROTOCOL_TCP && destIp == FAKE_DNS_IP

    fun isTcpSyn(): Boolean {
        if (protocol != PROTOCOL_TCP || rawData.size < headerLength + 14) return false
        val flags = rawData[headerLength + 13].toInt() and 0x3F
        return (flags and 0x02) != 0 && (flags and 0x10) == 0
    }

    fun isTcpFin(): Boolean {
        if (protocol != PROTOCOL_TCP || rawData.size < headerLength + 14) return false
        val flags = rawData[headerLength + 13].toInt() and 0x3F
        return (flags and 0x01) != 0
    }

    fun isTcpRst(): Boolean {
        if (protocol != PROTOCOL_TCP || rawData.size < headerLength + 14) return false
        val flags = rawData[headerLength + 13].toInt() and 0x3F
        return (flags and 0x04) != 0
    }

    fun isTcpAck(): Boolean {
        if (protocol != PROTOCOL_TCP || rawData.size < headerLength + 14) return false
        val flags = rawData[headerLength + 13].toInt() and 0x3F
        return (flags and 0x10) != 0
    }

    fun getTcpSequenceNumber(): Long {
        if (protocol != PROTOCOL_TCP || rawData.size < headerLength + 8) return 0
        val tcpOffset = headerLength
        return ((rawData[tcpOffset + 4].toLong() and 0xFF) shl 24) or
                ((rawData[tcpOffset + 5].toLong() and 0xFF) shl 16) or
                ((rawData[tcpOffset + 6].toLong() and 0xFF) shl 8) or
                (rawData[tcpOffset + 7].toLong() and 0xFF)
    }

    fun getTcpAckNumber(): Long {
        if (protocol != PROTOCOL_TCP || rawData.size < headerLength + 12) return 0
        val tcpOffset = headerLength
        return ((rawData[tcpOffset + 8].toLong() and 0xFF) shl 24) or
                ((rawData[tcpOffset + 9].toLong() and 0xFF) shl 16) or
                ((rawData[tcpOffset + 10].toLong() and 0xFF) shl 8) or
                (rawData[tcpOffset + 11].toLong() and 0xFF)
    }

    fun extractDnsQuery(): String? {
        if (!isDnsQuery() || payloadLength < 12) return null

        try {
            val payload = rawData.sliceArray(payloadOffset until rawData.size)
            val sb = StringBuilder()
            var i = 12

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

        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = (response[3].toInt() and 0xF0).toByte()
        response[6] = 0
        response[7] = 1

        val answer = mutableListOf<Byte>()
        answer.add(0xC0.toByte())
        answer.add(0x0C)
        answer.add(0)
        answer.add(1)
        answer.add(0)
        answer.add(1)
        answer.add(0)
        answer.add(0)
        answer.add(0)
        answer.add(60)
        answer.add(0)
        answer.add(4)
        for (octet in ip.split(".")) {
            answer.add(octet.toInt().toByte())
        }

        val fullResponse = response + answer.toByteArray()

        val udpHeader = ByteArray(8)
        udpHeader[0] = rawData[headerLength + 2]
        udpHeader[1] = rawData[headerLength + 3]
        udpHeader[2] = rawData[headerLength]
        udpHeader[3] = rawData[headerLength + 1]
        val udpLength = fullResponse.size + 8
        udpHeader[4] = (udpLength shr 8).toByte()
        udpHeader[5] = udpLength.toByte()

        val udpPacket = udpHeader + fullResponse

        val ipHeader = rawData.sliceArray(0 until headerLength)
        for (i in 12..15) {
            ipHeader[i] = rawData[i + 4]
            ipHeader[i + 4] = rawData[i]
        }
        val totalLength = headerLength + udpPacket.size
        ipHeader[2] = (totalLength shr 8).toByte()
        ipHeader[3] = totalLength.toByte()
        ipHeader[10] = 0
        ipHeader[11] = 0

        // UDP 校验和
        val srcIpStr = "${ipHeader[12].toInt() and 0xFF}.${ipHeader[13].toInt() and 0xFF}.${ipHeader[14].toInt() and 0xFF}.${ipHeader[15].toInt() and 0xFF}"
        val dstIpStr = "${ipHeader[16].toInt() and 0xFF}.${ipHeader[17].toInt() and 0xFF}.${ipHeader[18].toInt() and 0xFF}.${ipHeader[19].toInt() and 0xFF}"
        val sp = srcIpStr.split(".").map { it.toInt() }
        val dp = dstIpStr.split(".").map { it.toInt() }
        val ph = ByteArray(12)
        ph[0] = sp[0].toByte(); ph[1] = sp[1].toByte(); ph[2] = sp[2].toByte(); ph[3] = sp[3].toByte()
        ph[4] = dp[0].toByte(); ph[5] = dp[1].toByte(); ph[6] = dp[2].toByte(); ph[7] = dp[3].toByte()
        ph[8] = 0; ph[9] = 17
        val uLen = udpPacket.size
        ph[10] = (uLen shr 8).toByte(); ph[11] = uLen.toByte()
        val udpCksum = checksumBytes(ph + udpPacket)
        udpHeader[6] = (udpCksum shr 8).toByte()
        udpHeader[7] = udpCksum.toByte()

        // IP 校验和
        val ipCksum = checksumBytes(ipHeader)
        ipHeader[10] = (ipCksum shr 8).toByte()
        ipHeader[11] = ipCksum.toByte()

        return ipHeader + udpPacket
    }

    private fun checksumBytes(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i < data.size - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.toInt().inv() and 0xFFFF)
    }
}
