package com.rjnsdev.linklift.app

import java.util.Locale

internal const val BATCH_MAX_ITEMS = 200
internal const val BATCH_AUTO_SELECT_LIMIT = 50

enum class AppScreen {
    Home,
    Processing,
    Preview,
    Batch,
    Downloads,
    Settings,
}

enum class BatchSource {
    Playlist,
    BulkPaste,
}

enum class MediaKind {
    Video,
    Audio,
    Image,
    Unknown,
    ;

    companion object {
        fun fromMimeType(mimeType: String?, url: String): MediaKind {
            val normalizedMime = mimeType.orEmpty().lowercase(Locale.ROOT)
            val normalizedUrl = url.lowercase(Locale.ROOT)
            return when {
                normalizedMime.startsWith("video/") -> Video
                normalizedMime.startsWith("audio/") -> Audio
                normalizedMime.startsWith("image/") -> Image
                normalizedUrl.endsWith(".mp4") || normalizedUrl.endsWith(".mkv") ||
                    normalizedUrl.endsWith(".webm") || normalizedUrl.endsWith(".m3u8") -> Video
                normalizedUrl.endsWith(".mp3") || normalizedUrl.endsWith(".wav") ||
                    normalizedUrl.endsWith(".aac") || normalizedUrl.endsWith(".m4a") -> Audio
                normalizedUrl.endsWith(".jpg") || normalizedUrl.endsWith(".jpeg") ||
                    normalizedUrl.endsWith(".png") || normalizedUrl.endsWith(".webp") ||
                    normalizedUrl.endsWith(".gif") -> Image
                else -> Unknown
            }
        }
    }
}

enum class DownloadState {
    Queued,
    Downloading,
    Completed,
    Failed,
    Paused,
}

enum class QualityPreset(val storageKey: String, val label: String, val description: String) {
    BestQuality(
        storageKey = "best_quality",
        label = "Best quality",
        description = "Always pick the highest-resolution stream available.",
    ),
    SmallestFile(
        storageKey = "smallest_file",
        label = "Smallest file",
        description = "Optimize for the lowest data usage and smallest download size.",
    ),
    AskEveryTime(
        storageKey = "ask_every_time",
        label = "Ask every time",
        description = "Show the format picker on every analyze so you can choose per link.",
    );

    companion object {
        fun fromStorageKey(key: String?): QualityPreset =
            entries.firstOrNull { it.storageKey == key } ?: BestQuality
    }
}

data class UserPreferences(
    val wifiOnly: Boolean = false,
    val completionNotifications: Boolean = true,
    val preferredQuality: QualityPreset = QualityPreset.BestQuality,
    val downloadLocation: String = "Downloads/LinkLift",
    val hasYouTubeCookies: Boolean = false,
    val youtubeCookiesLastModified: Long = 0L,
)

data class MediaFormat(
    val formatId: String,
    val label: String,
    val mediaUrl: String,
    val mimeType: String,
    val kind: MediaKind,
    val ext: String,
    val height: Int?,
    val width: Int?,
    val fps: Int?,
    val abr: Int?,
    val tbr: Int?,
    val fileSizeBytes: Long?,
    val fileSizeApprox: Boolean,
    val isAudioOnly: Boolean,
    val isProgressive: Boolean,
    val isVideoOnly: Boolean = false,
    val vcodec: String? = null,
    val acodec: String? = null,
    val httpHeaders: Map<String, String> = emptyMap(),
    val mergeAudio: MediaFormat? = null,
) {
    val requiresMerge: Boolean get() = isVideoOnly && mergeAudio != null
}

data class PreviewMediaItem(
    val resolvedUrl: String,
    val title: String,
    val description: String,
    val mimeType: String,
    val kind: MediaKind,
    val fileSizeBytes: Long?,
    val durationMs: Long?,
    val resolution: String?,
    val fileName: String,
    val formats: List<MediaFormat> = emptyList(),
)

data class MediaPreview(
    val sourceUrl: String,
    val resolvedUrl: String,
    val title: String,
    val description: String,
    val mimeType: String,
    val kind: MediaKind,
    val host: String,
    val fileSizeBytes: Long?,
    val durationMs: Long?,
    val resolution: String?,
    val fileName: String,
    val isDirectLink: Boolean = true,
    val items: List<PreviewMediaItem> = emptyList(),
)

data class DownloadEntry(
    val id: Long,
    val title: String,
    val description: String,
    val mimeType: String?,
    val kind: MediaKind,
    val state: DownloadState,
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val localUri: String?,
    val sourceUrl: String?,
    val updatedAt: Long,
    /** [DownloadManager.COLUMN_REASON] when state is [DownloadState.Failed]; otherwise null. */
    val dmFailureCode: Int? = null,
)

data class LinkBatchEntry(
    val sourceUrl: String,
    val title: String,
    val uploader: String = "",
    val host: String = "",
    val durationMs: Long? = null,
)

data class LinkBatch(
    val source: BatchSource,
    val sourceUrl: String,
    val label: String,
    val title: String,
    val uploader: String = "",
    val entries: List<LinkBatchEntry>,
    val totalCount: Int,
    val returnedCount: Int,
    val isTruncated: Boolean,
)

data class BatchProgress(
    val total: Int,
    val processed: Int,
    val succeeded: Int,
    val failed: Int,
    val currentTitle: String? = null,
) {
    val isActive: Boolean get() = processed < total
}

data class LinkLiftUiState(
    val currentScreen: AppScreen = AppScreen.Home,
    val inputUrl: String = "",
    val preview: MediaPreview? = null,
    val selectedPreviewIndex: Int = 0,
    val selectedFormatIdsByItem: Map<Int, String> = emptyMap(),
    val showAudioOnly: Boolean = false,
    val selectedItemIndices: Set<Int> = emptySet(),
    val batch: LinkBatch? = null,
    val selectedBatchIndices: Set<Int> = emptySet(),
    val batchProgress: BatchProgress? = null,
    val settings: UserPreferences = UserPreferences(),
    val downloads: List<DownloadEntry> = emptyList(),
    val message: String? = null,
    val analysisStartedAt: Long? = null,
    val analysisLatencyMs: Long? = null,
    val downloadServiceAvailable: Boolean = true,
    val isSoundCloudAvailable: Boolean = true,
    val isImgurAvailable: Boolean = true,
    val showYouTubeAuthPrompt: Boolean = false,
    val youTubeAuthPromptReason: String? = null,
)

