package com.rjnsdev.linklift.app

internal const val MERGE_JOB_ID_OFFSET = -1_000_000_000L

internal fun mergeJobToDownloadId(jobId: String): Long {
    // Stable negative Long derived from the string id, kept well below any
    // positive DownloadManager id so they never collide.
    return MERGE_JOB_ID_OFFSET - (jobId.hashCode().toLong().absoluteValue())
}

internal fun Long.absoluteValue(): Long = if (this < 0L) -this else this

internal fun isMergeJobDownloadId(id: Long): Boolean = id <= MERGE_JOB_ID_OFFSET

internal fun MergeJobRecord.toDownloadEntry(): DownloadEntry {
    val statusLabel = when (state) {
        MergeJobState.Queued -> "Queued for merging"
        MergeJobState.DownloadingVideo -> "Downloading video stream"
        MergeJobState.DownloadingAudio -> "Downloading audio stream"
        MergeJobState.Muxing -> "Combining video & audio"
        MergeJobState.Completed -> "Saved to Downloads/LinkLift"
        MergeJobState.Failed -> errorMessage ?: "Failed"
        MergeJobState.Cancelled -> "Cancelled"
    }
    return DownloadEntry(
        id = mergeJobToDownloadId(id),
        title = title,
        description = if (description.isBlank()) statusLabel else "$description • $statusLabel",
        mimeType = mimeType,
        kind = if (mimeType.startsWith("audio")) MediaKind.Audio else MediaKind.Video,
        state = when (state) {
            MergeJobState.Queued -> DownloadState.Queued
            MergeJobState.DownloadingVideo,
            MergeJobState.DownloadingAudio,
            MergeJobState.Muxing -> DownloadState.Downloading
            MergeJobState.Completed -> DownloadState.Completed
            MergeJobState.Failed,
            MergeJobState.Cancelled -> DownloadState.Failed
        },
        progress = progress,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        localUri = resultUri,
        sourceUrl = sourceUrl,
        updatedAt = updatedAt,
        dmFailureCode = null,
    )
}
