package com.wzydqq.icu.injector

import android.content.Context
import android.net.Uri
import com.github.megatronking.netbare.http.HttpBody
import com.github.megatronking.netbare.http.HttpRequest
import com.github.megatronking.netbare.http.HttpRequestHeaderPart
import com.github.megatronking.netbare.http.HttpResponse
import com.github.megatronking.netbare.http.HttpResponseHeaderPart
import com.github.megatronking.netbare.injector.InjectorCallback
import com.github.megatronking.netbare.injector.SimpleHttpInjector
import com.wzydqq.icu.location.LocationStore
import com.wzydqq.icu.location.SelectedLocation
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream

class Link(private val context: Context) : SimpleHttpInjector() {

    companion object {
        private const val TAG = "LocationInterceptor"
        private val PREFIXES = listOf(
            "https://apis.map.qq.com/ws/geocoder/v1",
            "http://apis.map.qq.com/ws/geocoder/v1"
        )
    }

    private var mHoldResponseHeader: HttpResponseHeaderPart? = null

    override fun sniffRequest(request: HttpRequest): Boolean {
        return PREFIXES.any { request.url().startsWith(it) }
    }

    override fun sniffResponse(response: HttpResponse): Boolean {
        return PREFIXES.any { response.url().startsWith(it) }
    }

    override fun onRequestInject(
        header: HttpRequestHeaderPart,
        callback: InjectorCallback
    ) {
        val location = getCurrentLocation() ?: run {
            callback.onFinished(header)
            return
        }

        val originalUrl = header.url()
        val newLocation = location.toLocationString()
        val modifiedUrl = modifyLocationInUrl(originalUrl, newLocation)

        val newHeader = HttpRequestHeaderPart.Builder.from(header)
            .url(modifiedUrl)
            .build()
        callback.onFinished(newHeader)
    }

    override fun onResponseInject(
        response: HttpResponse,
        body: HttpBody,
        callback: InjectorCallback
    ) {
        val header = mHoldResponseHeader ?: run {
            callback.onFinished(body)
            return
        }

        val rawBytes = body.data ?: run {
            callback.onFinished(body)
            return
        }

        val originalBody = decodeResponseBody(rawBytes, response)
        val modifiedBody = modifyGeocoderResponse(originalBody)
        val encodedBody = encodeResponseBody(modifiedBody, response)

        callback.onFinished(
            HttpBody.Builder.from(body)
                .data(encodedBody)
                .build()
        )
        mHoldResponseHeader = null
    }

    override fun onResponseInject(
        header: HttpResponseHeaderPart,
        callback: InjectorCallback
    ) {
        mHoldResponseHeader = header
        callback.onFinished(header)
    }

    private fun getCurrentLocation(): SelectedLocation? = LocationStore.get(context)

    private fun modifyLocationInUrl(originalUrl: String, newLocation: String): String {
        return try {
            val uri = Uri.parse(originalUrl)
            val builder = uri.buildUpon().clearQuery()
            val paramNames = uri.queryParameterNames

            if (paramNames.isNotEmpty()) {
                for (name in paramNames) {
                    if (name == "location") {
                        builder.appendQueryParameter("location", newLocation)
                    } else {
                        for (value in uri.getQueryParameters(name)) {
                            builder.appendQueryParameter(name, value)
                        }
                    }
                }
                if (!paramNames.contains("location")) {
                    builder.appendQueryParameter("location", newLocation)
                }
            } else {
                builder.appendQueryParameter("location", newLocation)
            }
            builder.build().toString()
        } catch (e: Exception) {
            // Fallback regex approach
            val regex = Regex("location=[^&]+")
            if (regex.containsMatchIn(originalUrl)) {
                regex.replace(originalUrl, "location=$newLocation")
            } else if (originalUrl.contains("?")) {
                "$originalUrl&location=$newLocation"
            } else {
                "$originalUrl?location=$newLocation"
            }
        }
    }

    private fun modifyGeocoderResponse(originalJsonText: String): String {
        val location = getCurrentLocation() ?: return originalJsonText

        try {
            val json = JSONObject(originalJsonText)
            val result = json.optJSONObject("result") ?: return originalJsonText

            val formattedAddress = formatAddress(
                location.provinceName,
                location.cityName,
                location.districtName
            )

            if (formattedAddress.isNotEmpty()) {
                result.put("address", formattedAddress)
            }

            // Modify address_component
            val addressComponent = result.optJSONObject("address_component")
            if (addressComponent != null) {
                if (location.provinceName.isNotEmpty())
                    addressComponent.put("province", location.provinceName)
                if (location.cityName.isNotEmpty())
                    addressComponent.put("city", location.cityName)
                if (location.districtName.isNotEmpty())
                    addressComponent.put("district", location.districtName)
            }

            // Modify ad_info
            val adInfo = result.optJSONObject("ad_info")
            if (adInfo != null) {
                adInfo.put("adcode", location.adcode.toString())
                if (location.provinceName.isNotEmpty())
                    adInfo.put("province", location.provinceName)
                if (location.cityName.isNotEmpty())
                    adInfo.put("city", location.cityName)
                if (location.districtName.isNotEmpty())
                    adInfo.put("district", location.districtName)

                // Build formatted name
                val parts = listOf(
                    adInfo.optString("nation", "中国"),
                    location.provinceName,
                    location.cityName,
                    location.districtName
                ).filter { it.isNotBlank() }
                adInfo.put("name", parts.joinToString(","))
            }

            return json.toString()
        } catch (e: Exception) {
            return originalJsonText
        }
    }

    private fun formatAddress(province: String, city: String, district: String): String {
        val normProvince = normalizeSpecialRegionName(province)
        val normCity = normalizeSpecialRegionName(city)
        val normDistrict = district.trim()

        val parts = if ((normProvince == "香港特别行政区" || normProvince == "澳门特别行政区")
            && normProvince == normCity
        ) {
            listOf(normProvince, normDistrict)
        } else {
            listOf(normProvince, normCity, normDistrict)
        }

        return parts.filter { it.isNotEmpty() }.joinToString("")
    }

    private fun normalizeSpecialRegionName(name: String): String {
        return name.trim()
    }

    // --- Compression helpers ---

    private fun decodeResponseBody(data: ByteArray, response: HttpResponse): String {
        if (data.isEmpty()) return "<empty body>"

        val encoding = response.responseHeader("Content-Encoding")
            ?.firstOrNull()
            ?.lowercase(Locale.ROOT)

        val decompressed = when {
            encoding?.contains("gzip") == true -> decompressGzip(data)
            encoding?.contains("deflate") == true || encoding?.contains("zlib") == true -> decompressDeflate(data)
            else -> data
        }

        val charset = extractCharset(response) ?: Charsets.UTF_8
        return String(decompressed, charset)
    }

    private fun encodeResponseBody(text: String, response: HttpResponse): ByteArray {
        val charset = extractCharset(response) ?: Charsets.UTF_8
        val bytes = text.toByteArray(charset)

        val encoding = response.responseHeader("Content-Encoding")
            ?.firstOrNull()
            ?.lowercase(Locale.ROOT)

        return when {
            encoding?.contains("gzip") == true -> compressGzip(bytes)
            encoding?.contains("deflate") == true || encoding?.contains("zlib") == true -> compressDeflate(bytes)
            else -> bytes
        }
    }

    private fun compressGzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.use { baos ->
            GZIPOutputStream(baos).use { it.write(data) }
            return baos.toByteArray()
        }
    }

    private fun compressDeflate(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.use { baos ->
            DeflaterOutputStream(baos).use { it.write(data) }
            return baos.toByteArray()
        }
    }

    private fun decompressGzip(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }

    private fun decompressDeflate(data: ByteArray): ByteArray {
        return InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }

    private fun extractCharset(response: HttpResponse): Charset? {
        val contentType = response.responseHeader("Content-Type")?.firstOrNull() ?: return null
        val parts = contentType.split(";").map { it.trim() }
        val charsetPart = parts.find { it.startsWith("charset=", ignoreCase = true) } ?: return null
        val charsetName = charsetPart.substringAfter("=").trim().trim('"', '\'')
        return try {
            if (charsetName.isNotEmpty()) Charset.forName(charsetName) else null
        } catch (e: Exception) {
            null
        }
    }
}
