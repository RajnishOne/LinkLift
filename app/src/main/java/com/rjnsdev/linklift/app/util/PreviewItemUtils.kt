package com.rjnsdev.linklift.app

internal fun selectedPreviewItem(
    preview: MediaPreview,
    index: Int,
): PreviewMediaItem {
    return preview.items.getOrElse(index.coerceAtLeast(0)) {
        PreviewMediaItem(
            resolvedUrl = preview.resolvedUrl,
            title = preview.title,
            description = preview.description,
            mimeType = preview.mimeType,
            kind = preview.kind,
            fileSizeBytes = preview.fileSizeBytes,
            durationMs = preview.durationMs,
            resolution = preview.resolution,
            fileName = preview.fileName,
        )
    }
}

internal fun multiItemFileName(
    originalFileName: String,
    index: Int,
): String {
    val dotIndex = originalFileName.lastIndexOf('.')
    return if (dotIndex > 0) {
        "${originalFileName.substring(0, dotIndex)}_${index + 1}${originalFileName.substring(dotIndex)}"
    } else {
        "${originalFileName}_${index + 1}"
    }
}
