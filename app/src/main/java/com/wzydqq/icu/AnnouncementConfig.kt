package com.wzydqq.icu

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 公告配置 - 从服务器拉取
 */
data class AnnouncementConfig(
    @SerializedName("enabled")
    val enabled: Boolean = false,
    @SerializedName("title")
    val title: String = "",
    @SerializedName("content")
    val content: String = "",
    @SerializedName("version")
    val version: String = "",
    @SerializedName("update_url")
    val updateUrl: String = "",
    @SerializedName("force_update")
    val forceUpdate: Boolean = false,
    @SerializedName("notices")
    val notices: List<NoticeItem> = emptyList()
)

data class NoticeItem(
    @SerializedName("id")
    val id: Int = 0,
    @SerializedName("title")
    val title: String = "",
    @SerializedName("content")
    val content: String = "",
    @SerializedName("time")
    val time: String = "",
    @SerializedName("important")
    val important: Boolean = false
)

object AnnouncementManager {
    private const val CONFIG_URL = "http://dl.wzydqq.icu/config.json"
    private const val PREFS_NAME = "announcement_prefs"
    private const val KEY_LAST_CONFIG = "last_config"
    private const val KEY_LAST_CHECK = "last_check"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun fetchConfig(): AnnouncementConfig? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(CONFIG_URL)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val config = gson.fromJson(body, AnnouncementConfig::class.java)
                // Cache locally
                cacheConfig(body)
                config
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getCachedConfig(): AnnouncementConfig? {
        return try {
            val json = App.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_CONFIG, null)
            if (json != null) gson.fromJson(json, AnnouncementConfig::class.java) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheConfig(json: String) {
        App.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CONFIG, json)
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }
}
