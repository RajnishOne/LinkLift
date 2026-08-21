package com.rjnsdev.linklift.app

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object RemoteConfigHelper {

    private const val TAG = "RemoteConfigHelper"

    private val CONFIG_URLS = listOf(
        "https://raw.githubusercontent.com/RajnishOne/LinkLift/main/remote_config.json",
        "https://cdn.jsdelivr.net/gh/RajnishOne/LinkLift@main/remote_config.json",
    )

    @Volatile
    var isYouTubeAvailable: Boolean = true
        private set

    @Volatile
    var isSoundCloudAvailable: Boolean = true
        private set

    @Volatile
    var isImgurAvailable: Boolean = true
        private set

    private val defaultClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun fetchConfig(client: OkHttpClient? = null, onUpdated: () -> Unit = {}) {
        val httpClient = client ?: defaultClient
        for (url in CONFIG_URLS) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            parseJson(body)
                            onUpdated()
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { Log.d(TAG, "Failed to fetch remote config from $url: ${e.message}") }
            }
        }
    }

    fun parseJson(jsonString: String) {
        runCatching {
            fun extractBool(key: String): Boolean? {
                val pattern = Regex("\"$key\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
                return pattern.find(jsonString)?.groupValues?.get(1)?.toBooleanStrictOrNull()
            }

            extractBool("is_youtube_available")?.let { isYouTubeAvailable = it }
            extractBool("is_soundcloud_available")?.let { isSoundCloudAvailable = it }
            extractBool("is_imgur_available")?.let { isImgurAvailable = it }
        }
    }



    fun getDisabledPlatformMessage(url: String): String? {
        if (isYouTubeUrl(url)) {
            if (!isYouTubeAvailable) return "YouTube downloads are not supported"
        }
        if (isSoundCloudUrl(url)) {
            if (!isSoundCloudAvailable) return "SoundCloud downloads are not supported"
        }
        if (isImgurUrl(url)) {
            if (!isImgurAvailable) return "Imgur downloads are not supported"
        }
        return null
    }

    fun getDisabledPlatformMessageForAny(urls: List<String>): String? {
        for (url in urls) {
            val msg = getDisabledPlatformMessage(url)
            if (msg != null) return msg
        }
        return null
    }
}


