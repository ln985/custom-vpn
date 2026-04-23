package com.wzydqq.icu.vpn

import android.content.Context
import android.util.Log
import com.wzydqq.icu.location.LocationStore
import com.wzydqq.icu.location.SelectedLocation
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 本地代理 - 拦截 HTTP 请求，透传 HTTPS 请求
 * 
 * 修复说明：
 * 1. 只处理目标 IP (10.0.0.2) 的流量
 * 2. HTTP (port 80): 拦截并修改请求/响应
 * 3. HTTPS (port 443): 通过 ConnectionTracker 直接转发，不经过此代理
 */
class LocalProxy(private val context: Context) {

    companion object {
        private const val TAG = "LocalProxy"
        private const val REAL_API_HOST = "apis.map.qq.com"
    }

    fun intercept(packet: IpPacket, outputStream: FileOutputStream) {
        val destPort = packet.destPort

        // 只处理 HTTP (port 80) 的拦截
        // HTTPS (port 443) 流量由 ConnectionTracker 处理透传
        if (destPort == 443) {
            // HTTPS 不应该到这里，但以防万一，直接透传
            handlePassthrough(packet, outputStream)
            return
        }

        if (destPort == 80) {
            handleHttpIntercept(packet, outputStream)
            return
        }

        // 其他端口：透传
        handlePassthrough(packet, outputStream)
    }

    private fun handleHttpIntercept(
        packet: IpPacket,
        outputStream: FileOutputStream
    ) {
        val targetSocket = Socket()
        try {
            targetSocket.connect(java.net.InetSocketAddress(REAL_API_HOST, 80), 10000)

            val payload = packet.rawData.sliceArray(packet.payloadOffset until packet.rawData.size)
            if (payload.isEmpty()) return

            val requestText = String(payload, Charsets.UTF_8)
            val location = LocationStore.get(context)

            val modifiedRequest = if (location != null && requestText.contains("location=")) {
                modifyRequestLocation(requestText, location.toLocationString())
            } else {
                requestText
            }

            val targetOut = targetSocket.getOutputStream()
            targetOut.write(modifiedRequest.toByteArray())
            targetOut.flush()

            val targetIn = targetSocket.getInputStream()
            val responseBytes = readHttpResponse(targetIn)

            val modifiedResponse = if (location != null) {
                modifyResponse(responseBytes, location)
            } else {
                responseBytes
            }

            val tcpResponse = buildTcpResponse(packet, modifiedResponse)
            synchronized(outputStream) {
                outputStream.write(tcpResponse)
                outputStream.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP intercept error: ${e.message}")
        } finally {
            try { targetSocket.close() } catch (_: Exception) {}
        }
    }

    private fun handlePassthrough(
        packet: IpPacket,
        outputStream: FileOutputStream
    ) {
        val targetSocket = Socket()
        try {
            val destIp = packet.destIp
            val destPort = packet.destPort
            targetSocket.connect(java.net.InetSocketAddress(destIp, destPort), 10000)

            val payload = packet.rawData.sliceArray(packet.payloadOffset until packet.rawData.size)

            if (payload.isNotEmpty()) {
                val targetOut = targetSocket.getOutputStream()
                targetOut.write(payload)
                targetOut.flush()
            }

            val targetIn = targetSocket.getInputStream()
            val responseBytes = readRawResponse(targetIn)

            if (responseBytes.isNotEmpty()) {
                val tcpResponse = buildTcpResponse(packet, responseBytes)
                synchronized(outputStream) {
                    outputStream.write(tcpResponse)
                    outputStream.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Passthrough error: ${e.message}")
        } finally {
            try { targetSocket.close() } catch (_: Exception) {}
        }
    }

    private fun readRawResponse(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                baos.write(buffer, 0, read)
                if (baos.size() > 65536) break
            }
        } catch (_: Exception) {}
        return baos.toByteArray()
    }

    private fun modifyRequestLocation(request: String, newLocation: String): String {
        val regex = Regex("location=[^&\\s]+")
        return if (regex.containsMatchIn(request)) {
            regex.replace(request, "location=$newLocation")
        } else {
            request.replaceFirst(Regex("(/ws/geocoder/v1\\?)"), "\$1location=$newLocation&")
        }
    }

    private fun modifyResponse(responseBytes: ByteArray, location: SelectedLocation): ByteArray {
        try {
            val responseStr = String(responseBytes, Charsets.UTF_8)
            val bodyStart = responseStr.indexOf("\r\n\r\n")
            if (bodyStart < 0) return responseBytes

            val headers = responseStr.substring(0, bodyStart)
            var body = responseStr.substring(bodyStart + 4)

            val isGzip = headers.lowercase().contains("content-encoding: gzip")
            if (isGzip) {
                try {
                    val compressed = body.toByteArray()
                    val bais = ByteArrayInputStream(compressed)
                    val gzis = GZIPInputStream(bais)
                    body = gzis.readBytes().toString(Charsets.UTF_8)
                } catch (e: Exception) {
                    return responseBytes
                }
            }

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

            val newHeaders = headers.replace(
                Regex("Content-Length: \\d+", RegexOption.IGNORE_CASE),
                "Content-Length: ${bodyBytes.size}"
            )

            val finalBody = if (isGzip) {
                val baos = ByteArrayOutputStream()
                GZIPOutputStream(baos).use { it.write(bodyBytes) }
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

        val contentLengthMatch = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
            .find(headerBuilder.toString())
        val contentLength = contentLengthMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

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
        val ipHeader = packet.rawData.sliceArray(0 until packet.headerLength)
        val tcpHeader = ByteArray(20)

        val srcPortOffset = packet.headerLength
        tcpHeader[0] = packet.rawData[srcPortOffset + 2]
        tcpHeader[1] = packet.rawData[srcPortOffset + 3]
        tcpHeader[2] = packet.rawData[srcPortOffset]
        tcpHeader[3] = packet.rawData[srcPortOffset + 1]

        val ackNum = ((packet.rawData[srcPortOffset + 4].toInt() and 0xFF) shl 24) or
                ((packet.rawData[srcPortOffset + 5].toInt() and 0xFF) shl 16) or
                ((packet.rawData[srcPortOffset + 6].toInt() and 0xFF) shl 8) or
                (packet.rawData[srcPortOffset + 7].toInt() and 0xFF)
        val seqNum = ackNum + payload.size
        tcpHeader[4] = (seqNum shr 24).toByte()
        tcpHeader[5] = (seqNum shr 16).toByte()
        tcpHeader[6] = (seqNum shr 8).toByte()
        tcpHeader[7] = seqNum.toByte()

        tcpHeader[8] = packet.rawData[srcPortOffset + 4]
        tcpHeader[9] = packet.rawData[srcPortOffset + 5]
        tcpHeader[10] = packet.rawData[srcPortOffset + 6]
        tcpHeader[11] = packet.rawData[srcPortOffset + 7]

        tcpHeader[12] = 0x50
        tcpHeader[13] = 0x18 // PSH + ACK
        tcpHeader[14] = 0xFF.toByte()
        tcpHeader[15] = 0xFF.toByte()

        val tcpPacket = tcpHeader + payload

        val responseIpHeader = ipHeader.clone()
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
        val parts = if ((normProvince == "香港特别行政区" || normProvince == "澳门特别行政区")
            && normProvince == normCity) {
            listOf(normProvince, normDistrict).filter { it.isNotEmpty() }
        } else {
            listOf(normProvince, normCity, normDistrict).filter { it.isNotEmpty() }
        }
        return parts.joinToString("")
    }
}
