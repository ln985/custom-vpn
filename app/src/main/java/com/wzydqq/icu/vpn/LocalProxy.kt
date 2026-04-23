package com.wzydqq.icu.vpn

import android.content.Context
import com.wzydqq.icu.location.LocationStore
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset

/**
 * 本地 HTTP 代理 - 拦截并修改腾讯地图 API 响应
 */
class LocalProxy(private val context: Context) {

    companion object {
        private val TARGET_HOSTS = setOf(
            "apis.map.qq.com"
        )
        private val TARGET_PATHS = setOf(
            "/ws/geocoder/v1"
        )
    }

    fun intercept(packet: IpPacket, outputStream: FileOutputStream) {
        // 建立到目标服务器的连接
        val targetSocket = Socket()
        try {
            targetSocket.connect(java.net.InetSocketAddress("apis.map.qq.com", 80), 10000)

            // 读取客户端请求
            val payload = packet.rawData.sliceArray(packet.payloadOffset until packet.rawData.size)
            val requestText = String(payload, Charsets.UTF_8)

            // 修改请求中的 location 参数
            val location = LocationStore.get(context)
            val modifiedRequest = if (location != null && requestText.contains("location=")) {
                modifyRequestLocation(requestText, location.toLocationString())
            } else {
                requestText
            }

            // 转发请求到目标服务器
            val targetOut = targetSocket.getOutputStream()
            targetOut.write(modifiedRequest.toByteArray())
            targetOut.flush()

            // 读取目标服务器响应
            val targetIn = targetSocket.getInputStream()
            val responseBytes = readHttpResponse(targetIn)

            // 修改响应中的位置数据
            val modifiedResponse = if (location != null) {
                modifyResponse(responseBytes, location)
            } else {
                responseBytes
            }

            // 构建 TCP 响应并写入 VPN 输出
            val tcpResponse = buildTcpResponse(packet, modifiedResponse)
            outputStream.write(tcpResponse)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { targetSocket.close() } catch (_: Exception) {}
        }
    }

    private fun modifyRequestLocation(request: String, newLocation: String): String {
        val regex = Regex("location=[^&\\s]+")
        return if (regex.containsMatchIn(request)) {
            regex.replace(request, "location=$newLocation")
        } else {
            // 在 URL 后添加 location 参数
            request.replaceFirst(Regex("(/ws/geocoder/v1\\?)"), "\$1location=$newLocation&")
        }
    }

    private fun modifyResponse(responseBytes: ByteArray, location: com.wzydqq.icu.location.SelectedLocation): ByteArray {
        try {
            // 找到 JSON 响应体
            val responseStr = String(responseBytes, Charsets.UTF_8)
            val bodyStart = responseStr.indexOf("\r\n\r\n")
            if (bodyStart < 0) return responseBytes

            val headers = responseStr.substring(0, bodyStart)
            var body = responseStr.substring(bodyStart + 4)

            // 检查是否有 gzip 压缩
            val isGzip = headers.lowercase().contains("content-encoding: gzip")
            if (isGzip) {
                try {
                    val compressed = body.toByteArray()
                    val bais = ByteArrayInputStream(compressed)
                    val gzis = java.util.zip.GZIPInputStream(bais)
                    body = gzis.readBytes().toString(Charsets.UTF_8)
                } catch (e: Exception) {
                    return responseBytes
                }
            }

            // 修改 JSON 响应
            val json = JSONObject(body)
            val result = json.optJSONObject("result")
            if (result != null) {
                val formattedAddress = formatAddress(
                    location.provinceName,
                    location.cityName,
                    location.districtName
                )
                if (formattedAddress.isNotEmpty()) {
                    result.put("address", formattedAddress)
                }

                val addressComponent = result.optJSONObject("address_component")
                if (addressComponent != null) {
                    if (location.provinceName.isNotEmpty()) addressComponent.put("province", location.provinceName)
                    if (location.cityName.isNotEmpty()) addressComponent.put("city", location.cityName)
                    if (location.districtName.isNotEmpty()) addressComponent.put("district", location.districtName)
                }

                val adInfo = result.optJSONObject("ad_info")
                if (adInfo != null) {
                    adInfo.put("adcode", location.adcode.toString())
                    if (location.provinceName.isNotEmpty()) adInfo.put("province", location.provinceName)
                    if (location.cityName.isNotEmpty()) adInfo.put("city", location.cityName)
                    if (location.districtName.isNotEmpty()) adInfo.put("district", location.districtName)

                    val normProvince = normalizeSpecialRegionName(location.provinceName)
                    val normCity = normalizeSpecialRegionName(location.cityName)
                    val nameParts = mutableListOf(adInfo.optString("nation", "中国"))
                    nameParts.add(normProvince)
                    // 港澳特别行政区：省=市时，跳过城市避免重复
                    if (!((normProvince == "香港特别行政区" || normProvince == "澳门特别行政区")
                        && normProvince == normCity)) {
                        nameParts.add(normCity)
                    }
                    nameParts.add(location.districtName.trim())
                    adInfo.put("name", nameParts.filter { it.isNotBlank() }.joinToString(","))
                }
            }

            val modifiedBody = json.toString()
            val bodyBytes = modifiedBody.toByteArray(Charsets.UTF_8)

            // 更新 Content-Length
            val newHeaders = headers.replace(
                Regex("Content-Length: \\d+", RegexOption.IGNORE_CASE),
                "Content-Length: ${bodyBytes.size}"
            )

            // 重新压缩
            val finalBody = if (isGzip) {
                val baos = ByteArrayOutputStream()
                java.util.zip.GZIPOutputStream(baos).use { it.write(bodyBytes) }
                baos.toByteArray()
            } else {
                bodyBytes
            }

            return (newHeaders + "\r\n\r\n").toByteArray(Charsets.UTF_8) + finalBody

        } catch (e: Exception) {
            return responseBytes
        }
    }

    private fun readHttpResponse(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var totalRead = 0

        // 读取 headers
        val headerBuilder = StringBuilder()
        var headerEnd = false
        val singleByte = ByteArray(1)

        while (!headerEnd && totalRead < 65536) {
            val read = input.read(singleByte)
            if (read <= 0) break
            baos.write(singleByte[0].toInt())
            headerBuilder.append(singleByte[0].toInt().toChar())
            totalRead++

            if (headerBuilder.endsWith("\r\n\r\n")) {
                headerEnd = true
            }
        }

        if (!headerEnd) return baos.toByteArray()

        // 解析 Content-Length
        val contentLengthMatch = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
            .find(headerBuilder.toString())
        val contentLength = contentLengthMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

        // 读取 body
        var bodyRead = 0
        while (bodyRead < contentLength) {
            val toRead = minOf(buffer.size, contentLength - bodyRead)
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            baos.write(buffer, 0, read)
            bodyRead += read
        }

        return baos.toByteArray()
    }

    private fun buildTcpResponse(packet: IpPacket, payload: ByteArray): ByteArray {
        // 简化的 TCP 响应构建
        val ipHeader = packet.rawData.sliceArray(0 until packet.headerLength)
        val tcpHeader = ByteArray(20)

        // 交换源和目标端口
        val srcPortOffset = packet.headerLength
        tcpHeader[0] = packet.rawData[srcPortOffset + 2]
        tcpHeader[1] = packet.rawData[srcPortOffset + 3]
        tcpHeader[2] = packet.rawData[srcPortOffset]
        tcpHeader[3] = packet.rawData[srcPortOffset + 1]

        // Sequence number
        val ackNum = ((packet.rawData[srcPortOffset + 4].toInt() and 0xFF) shl 24) or
                ((packet.rawData[srcPortOffset + 5].toInt() and 0xFF) shl 16) or
                ((packet.rawData[srcPortOffset + 6].toInt() and 0xFF) shl 8) or
                (packet.rawData[srcPortOffset + 7].toInt() and 0xFF)
        val seqNum = ackNum + payload.size
        tcpHeader[4] = (seqNum shr 24).toByte()
        tcpHeader[5] = (seqNum shr 16).toByte()
        tcpHeader[6] = (seqNum shr 8).toByte()
        tcpHeader[7] = seqNum.toByte()

        // ACK number
        tcpHeader[8] = packet.rawData[srcPortOffset + 4]
        tcpHeader[9] = packet.rawData[srcPortOffset + 5]
        tcpHeader[10] = packet.rawData[srcPortOffset + 6]
        tcpHeader[11] = packet.rawData[srcPortOffset + 7]

        // Data offset (5 words = 20 bytes) + flags (ACK)
        tcpHeader[12] = 0x50
        tcpHeader[13] = 0x10 // ACK

        // Window size
        tcpHeader[14] = 0xFF.toByte()
        tcpHeader[15] = 0xFF.toByte()

        val tcpPacket = tcpHeader + payload

        // 更新 IP 头
        val responseIpHeader = ipHeader.clone()
        // 交换 IP
        for (i in 12..15) {
            responseIpHeader[i] = ipHeader[i + 4]
            responseIpHeader[i + 4] = ipHeader[i]
        }
        val totalLength = packet.headerLength + tcpPacket.size
        responseIpHeader[2] = (totalLength shr 8).toByte()
        responseIpHeader[3] = totalLength.toByte()

        return responseIpHeader + tcpPacket
    }

    private fun normalizeSpecialRegionName(name: String): String {
        val trimmed = name.trim()
        return when (trimmed) {
            "香港" -> "香港特别行政区"
            "澳门" -> "澳门特别行政区"
            else -> trimmed
        }
    }

    private fun formatAddress(province: String, city: String, district: String): String {
        val normProvince = normalizeSpecialRegionName(province)
        val normCity = normalizeSpecialRegionName(city)
        val normDistrict = district.trim()
        // 港澳特别行政区：省=市时，只显示省+区，避免重复
        val parts = if ((normProvince == "香港特别行政区" || normProvince == "澳门特别行政区")
            && normProvince == normCity) {
            listOf(normProvince, normDistrict).filter { it.isNotEmpty() }
        } else {
            listOf(normProvince, normCity, normDistrict).filter { it.isNotEmpty() }
        }
        return parts.joinToString("")
    }
}
