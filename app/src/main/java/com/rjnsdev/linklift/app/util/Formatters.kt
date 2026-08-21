package com.rjnsdev.linklift.app

import java.text.DecimalFormat

internal fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "Unknown"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    val decimal = DecimalFormat("#.##")
    return when {
        bytes >= gb -> "${decimal.format(bytes / gb)} GB"
        bytes >= mb -> "${decimal.format(bytes / mb)} MB"
        bytes >= kb -> "${decimal.format(bytes / kb)} KB"
        else -> "$bytes B"
    }
}

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

internal fun downloadStateLabel(state: DownloadState): String = when (state) {
    DownloadState.Queued -> "Queued"
    DownloadState.Downloading -> "Downloading"
    DownloadState.Completed -> "Completed"
    DownloadState.Failed -> "Failed"
    DownloadState.Paused -> "Paused"
}
