package com.rjnsdev.linklift.app

import android.net.Uri
import android.util.Patterns
import android.webkit.URLUtil
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

private val YT_DLP_EXACT_HOSTS = setOf(
    "youtu.be",
    "instagr.am",
    "fb.watch",
    "redd.it",
    "pin.it",
    "dai.ly",
    "x.com",
    "drive.google.com",
)

private val YT_DLP_HOST_MARKERS = listOf(
    "youtube.com",
    "youtube-nocookie.com",
    "instagram.com",
    "tiktok.com",
    "twitter.com",
    "facebook.com",
    "vimeo.com",
    "reddit.com",
    "pinterest.",
    "twitch.tv",
    "streamable.com",
    "imgur.com",
    "soundcloud.com",
    "dailymotion.com",
    "rumble.com",
    "xhamster.com",
    "xhamster2.com",
    "pornhub.com",
)

private val FALLBACK_URL_PATTERN = Pattern.compile(
    """https?://[^\s<>"']+|www\.[^\s<>"']+""",
    Pattern.CASE_INSENSITIVE
)

private fun getUriHost(url: String): String {
    val jvmHost = runCatching { URI(url).host }.getOrNull()
    if (!jvmHost.isNullOrBlank()) return jvmHost
    return runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
}

private fun getUriPath(url: String): String {
    val jvmPath = runCatching { URI(url).path }.getOrNull()
    if (!jvmPath.isNullOrBlank()) return jvmPath
    return runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
}

private fun getUriQueryParam(url: String, key: String): String? {
    val jvmParam = runCatching {
        val query = URI(url).rawQuery.orEmpty()
        query.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == key }
            ?.get(1)
    }.getOrNull()
    if (!jvmParam.isNullOrBlank()) return jvmParam
    return runCatching { Uri.parse(url).getQueryParameter(key) }.getOrNull()
}

private fun isHttpOrHttps(url: String): Boolean {
    val runAndroid = runCatching { URLUtil.isNetworkUrl(url) }.getOrNull()
    if (runAndroid != null) return runAndroid
    val lowered = url.lowercase(Locale.ROOT)
    return lowered.startsWith("http://") || lowered.startsWith("https://")
}

/** Hosts routed through yt-dlp (`generic_media_resolver`) instead of HTML scraping. */
internal fun isYtDlpSupportedHost(url: String): Boolean {
    val host = getUriHost(url).lowercase(Locale.ROOT).removePrefix("www.")
    if (host.isBlank()) return false
    if (host in YT_DLP_EXACT_HOSTS) return true
    return YT_DLP_HOST_MARKERS.any { marker ->
        host == marker || host.endsWith(".$marker") || host.contains(marker)
    }
}

internal fun extractFirstUrl(text: String): String? = extractAllUrls(text).firstOrNull()

internal fun extractAllUrls(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val pattern = runCatching { Patterns.WEB_URL }.getOrNull() ?: FALLBACK_URL_PATTERN
    val matcher = pattern.matcher(text)
    val seen = LinkedHashSet<String>()
    while (matcher.find()) {
        val candidate = matcher.group()
            ?.trim()
            ?.trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?', '"', '\'')
            ?: continue
        val normalized = if (isHttpOrHttps(candidate)) {
            candidate
        } else if (candidate.startsWith("//")) {
            "https:$candidate"
        } else if (candidate.contains(".") && !candidate.startsWith("http")) {
            "https://$candidate".takeIf { isHttpOrHttps(it) }
        } else {
            null
        } ?: continue
        seen += normalized
    }
    return seen.toList()
}

internal fun looksLikePlaylistUrl(rawUrl: String): Boolean {
    val host = getUriHost(rawUrl).lowercase(Locale.ROOT).removePrefix("www.")
    val path = getUriPath(rawUrl).lowercase(Locale.ROOT)
    val listParam = getUriQueryParam(rawUrl, "list")

    return when {
        host.endsWith("youtube.com") || host == "music.youtube.com" -> {
            path.startsWith("/playlist") ||
                path.startsWith("/channel/") ||
                path.startsWith("/c/") ||
                path.startsWith("/user/") ||
                path.startsWith("/@") ||
                (!listParam.isNullOrBlank() && (path.startsWith("/playlist") || path.startsWith("/watch") && listParam.startsWith("PL")))
        }
        host == "youtu.be" -> !listParam.isNullOrBlank()
        host.endsWith("tiktok.com") -> {
            (path.startsWith("/@") && !path.contains("/video/") && !path.contains("/photo/")) ||
                path.endsWith("/foryou") ||
                path.endsWith("/trending")
        }
        host == "x.com" || host.endsWith("twitter.com") -> {
            val segments = path.trim('/').split('/').filter { it.isNotBlank() }
            segments.size == 1 && segments[0] !in setOf(
                "i", "home", "explore", "search", "settings", "notifications", "messages",
            )
        }
        host.contains("vimeo.com") -> path.startsWith("/channels/") || path.startsWith("/showcase/")
        host.contains("soundcloud.com") -> path.contains("/sets/")
        host.contains("twitch.tv") -> path.endsWith("/videos")
        host.contains("imgur.com") -> path.startsWith("/a/") || path.startsWith("/gallery/")
        host == "drive.google.com" -> path.contains("/folders/")
        host.contains("dailymotion.com") -> path.startsWith("/playlist/")
        host.contains("rumble.com") -> path.contains("/playlists/")
        else -> false
    }
}

internal fun shouldRefreshUrlBeforeDownload(url: String): Boolean {
    val host = getUriHost(url).lowercase(Locale.ROOT)
    return host.contains("xhamster") ||
           host.contains("pornhub.com") ||
           host.contains("tiktok.com") ||
           host.contains("facebook.com") ||
           host.contains("fb.watch") ||
           host.contains("instagram.com") ||
           host.contains("instagr.am")
}

internal fun shouldDownloadWithMergeService(format: MediaFormat, sourceUrl: String): Boolean {
    if (format.requiresMerge) return true
    val host = getUriHost(sourceUrl).lowercase(Locale.ROOT)
    return host.contains("tiktok.com") || host == "vt.tiktok.com" ||
           host.contains("facebook.com") || host == "fb.watch" ||
           host.contains("xhamster.com") || host.contains("xhamster2.com") ||
           host.contains("pornhub.com")
}

internal fun isYouTubeUrl(url: String): Boolean {
    val host = getUriHost(url).lowercase(Locale.ROOT)
    return host.contains("youtube.com") || host.endsWith("youtube.com") ||
           host.contains("youtube-nocookie.com") || host == "youtu.be" || host.contains("youtu.be")
}

internal fun isSoundCloudUrl(url: String): Boolean {
    val host = getUriHost(url).lowercase(Locale.ROOT)
    return host.contains("soundcloud.com") || host.endsWith("soundcloud.com")
}

internal fun isImgurUrl(url: String): Boolean {
    val host = getUriHost(url).lowercase(Locale.ROOT)
    return host.contains("imgur.com") || host.endsWith("imgur.com")
}
