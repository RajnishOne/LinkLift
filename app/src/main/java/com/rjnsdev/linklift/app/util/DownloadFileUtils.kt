package com.rjnsdev.linklift.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

internal fun openDownloadedFile(context: Context, localUri: String, mimeType: String?) {
    val resolvedUri = resolvableDownloadedUri(context, localUri)
    val resolvedMimeType = resolveDownloadedMimeType(context, resolvedUri, mimeType)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (resolvedMimeType != null) {
            setDataAndType(resolvedUri, resolvedMimeType)
        } else {
            data = resolvedUri
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open file").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.onFailure {
        Toast.makeText(context, "Couldn't open this file outside LinkLift.", Toast.LENGTH_SHORT).show()
    }
}

internal fun isDownloadedMediaAvailable(context: Context, localUri: String?): Boolean {
    if (localUri.isNullOrBlank()) return false

    val uri = Uri.parse(localUri)
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "file" -> uri.path?.let { File(it).exists() } == true
        "content" -> runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        else -> false
    }
}

internal fun resolvableDownloadedUri(context: Context, localUri: String): Uri {
    val uri = Uri.parse(localUri)
    if (!uri.scheme.equals("file", ignoreCase = true)) return uri

    val filePath = uri.path ?: return uri
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(filePath),
    )
}

internal fun resolveDownloadedMimeType(
    context: Context,
    uri: Uri,
    mimeType: String?,
): String? {
    val normalizedMimeType = mimeType?.takeIf { it.isNotBlank() && it != "*/*" }
    if (normalizedMimeType != null) return normalizedMimeType

    context.contentResolver.getType(uri)?.let { return it }

    val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        .orEmpty()
        .lowercase(Locale.ROOT)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}
