package com.rjnsdev.linklift.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rjnsdev.linklift.app.linkLiftListContentPadding
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccent
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccentBright
import com.rjnsdev.linklift.app.ui.theme.LinkLiftBackground
import com.rjnsdev.linklift.app.ui.theme.LinkLiftBlue
import com.rjnsdev.linklift.app.ui.theme.LinkLiftCard
import com.rjnsdev.linklift.app.ui.theme.LinkLiftError
import com.rjnsdev.linklift.app.ui.theme.LinkLiftSuccess
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextSecondary

@Composable
internal fun DownloadsScreen(
    downloads: List<DownloadEntry>,
    onRemoveTracked: (Set<Long>) -> Unit,
    onRegenerateTracked: (String?) -> Unit,
    onCancelDownload: (Long) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var viewerDownload by remember { mutableStateOf<DownloadEntry?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val filtered = remember(downloads, query) {
        if (query.isBlank()) downloads else downloads.filter {
            it.title.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
        }
    }
    val visibleIds = remember(filtered) { filtered.map { it.id }.toSet() }

    LaunchedEffect(downloads) {
        val validIds = downloads.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(validIds)
        if (selectionMode && validIds.isEmpty()) {
            selectionMode = false
        }
    }

    viewerDownload?.takeIf { isDownloadedMediaAvailable(context, it.localUri) }?.let { download ->
        DownloadedFileViewer(
            download = download,
            onDismiss = { viewerDownload = null },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = linkLiftListContentPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = LinkLiftTextSecondary,
                    )
                },
                placeholder = { Text("Search your downloads...") },
                colors = linkFieldColors(),
            )
        }

        item {
            SectionHeader(
                title = "Tracked items",
                action = "${filtered.size} item${if (filtered.size == 1) "" else "s"}",
            )
        }

        if (downloads.isNotEmpty()) {
            item {
                NeonCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (selectionMode) {
                                "${selectedIds.size} selected. Removing tracked items only clears them from this list."
                            } else {
                                "Manage the items tracked in this tab without deleting the downloaded files."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = LinkLiftTextSecondary,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (selectionMode) {
                                TextButton(
                                    onClick = { selectedIds = selectedIds + visibleIds },
                                    enabled = visibleIds.any { it !in selectedIds },
                                ) {
                                    Text("Select all")
                                }
                                TextButton(
                                    onClick = { selectedIds = emptySet() },
                                    enabled = selectedIds.isNotEmpty(),
                                ) {
                                    Text("Clear")
                                }
                                TextButton(
                                    onClick = {
                                        selectionMode = false
                                        selectedIds = emptySet()
                                    },
                                ) {
                                    Text("Cancel")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onRemoveTracked(selectedIds)
                                        selectionMode = false
                                        selectedIds = emptySet()
                                    },
                                    enabled = selectedIds.isNotEmpty(),
                                ) {
                                    Text("Delete selected")
                                }
                            } else {
                                OutlinedButton(onClick = { selectionMode = true }) {
                                    Text("Select items to delete")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.CloudDownload,
                    title = "No downloads yet",
                    subtitle = "Queue a direct file or supported platform link and it will appear here with live progress.",
                )
            }
        } else {
            items(
                items = filtered,
                key = { it.id },
            ) { item ->
                DownloadDetailCard(
                    download = item,
                    onOpen = { download ->
                        if (isDownloadedMediaAvailable(context, download.localUri)) {
                            viewerDownload = download
                        } else {
                            onRegenerateTracked(download.sourceUrl)
                        }
                    },
                    onCancel = { onCancelDownload(item.id) },
                    selectionMode = selectionMode,
                    selected = item.id in selectedIds,
                    onSelectionChanged = { checked ->
                        selectedIds = if (checked) {
                            selectedIds + item.id
                        } else {
                            selectedIds - item.id
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadDetailCard(
    download: DownloadEntry,
    onOpen: (DownloadEntry) -> Unit,
    onCancel: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelectionChanged: (Boolean) -> Unit = {},
) {
    val canOpen = download.state == DownloadState.Completed &&
        (!download.localUri.isNullOrBlank() || !download.sourceUrl.isNullOrBlank())
    val isActive = download.state == DownloadState.Queued ||
        download.state == DownloadState.Downloading ||
        download.state == DownloadState.Paused
    NeonCard(
        modifier = when {
            selectionMode -> Modifier.clickable { onSelectionChanged(!selected) }
            canOpen -> Modifier.clickable { onOpen(download) }
            else -> Modifier
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DownloadDetailCardHeader(
                title = download.title,
                description = download.description,
                kind = download.kind,
                state = download.state,
                selectionMode = selectionMode,
                selected = selected,
                isActive = isActive,
                onSelectionChanged = onSelectionChanged,
                onCancel = onCancel,
            )
            key(download.progress, download.downloadedBytes, download.totalBytes, download.state) {
                DownloadDetailCardProgress(
                    state = download.state,
                    progress = download.progress,
                    downloadedBytes = download.downloadedBytes,
                    totalBytes = download.totalBytes,
                    selectionMode = selectionMode,
                    isActive = isActive,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun DownloadDetailCardHeader(
    title: String,
    description: String,
    kind: MediaKind,
    state: DownloadState,
    selectionMode: Boolean,
    selected: Boolean,
    isActive: Boolean,
    onSelectionChanged: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadThumbnail(kind = kind, state = state, large = true)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ExpandableDescriptionText(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = LinkLiftTextSecondary,
            )
        }
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { checked -> onSelectionChanged(checked) },
                colors = CheckboxDefaults.colors(
                    checkedColor = LinkLiftAccent,
                    checkmarkColor = Color.White,
                    uncheckedColor = LinkLiftAccentBright.copy(alpha = 0.8f),
                ),
            )
        } else if (isActive) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancel download",
                    tint = LinkLiftError,
                )
            }
        }
    }
}

@Composable
private fun DownloadDetailCardProgress(
    state: DownloadState,
    progress: Float,
    downloadedBytes: Long,
    totalBytes: Long?,
    selectionMode: Boolean,
    isActive: Boolean,
    onCancel: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoChip(title = downloadStateLabel(state), value = "Status")
        InfoChip(title = formatBytes(downloadedBytes), value = "Downloaded")
        totalBytes?.let { InfoChip(title = formatBytes(it), value = "Total") }
    }

    LinearProgressIndicator(
        progress = { if (state == DownloadState.Completed) 1f else progress },
        modifier = Modifier.fillMaxWidth(),
        color = when (state) {
            DownloadState.Completed -> LinkLiftSuccess
            DownloadState.Failed -> LinkLiftError
            else -> LinkLiftAccent
        },
        trackColor = Color.White.copy(alpha = 0.08f),
    )

    if (!selectionMode && isActive) {
        TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = LinkLiftError,
            )
            Text(
                text = "  Cancel download",
                color = LinkLiftError,
            )
        }
    }
}

@Composable
private fun DownloadedFileViewer(
    download: DownloadEntry,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val localUri = download.localUri ?: return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = LinkLiftBackground,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Close",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = download.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { openDownloadedFile(context, localUri, download.mimeType) },
                    ) {
                        Text("External", color = LinkLiftAccentBright)
                    }
                }

                when (download.kind) {
                    MediaKind.Image -> {
                        Surface(
                            shape = RoundedCornerShape(30.dp),
                            color = LinkLiftCard.copy(alpha = 0.9f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            ),
                        ) {
                            AsyncImage(
                                model = Uri.parse(localUri),
                                contentDescription = download.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 260.dp)
                                    .clip(RoundedCornerShape(30.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }

                    MediaKind.Video -> MediaPlayerSurface(
                        mediaUri = localUri,
                        height = 300.dp,
                    )

                    MediaKind.Audio -> NeonCard {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = "Audio playback",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                            )
                            MediaPlayerSurface(
                                mediaUri = localUri,
                                height = 120.dp,
                            )
                        }
                    }

                    MediaKind.Unknown -> EmptyStateCard(
                        icon = Icons.Rounded.ErrorOutline,
                        title = "Preview unavailable",
                        subtitle = "This file type cannot be previewed in LinkLift yet. Use External to open it in another app.",
                    )
                }

                NeonCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExpandableDescriptionText(
                            text = download.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LinkLiftTextSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            InfoChip(title = downloadStateLabel(download.state), value = "Status")
                            InfoChip(title = formatBytes(download.downloadedBytes), value = "Size")
                            download.mimeType?.let { InfoChip(title = it, value = "Type") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadThumbnail(
    kind: MediaKind,
    state: DownloadState,
    large: Boolean = false,
) {
    val icon = when (kind) {
        MediaKind.Video -> Icons.Rounded.PlayCircleOutline
        MediaKind.Audio -> Icons.Rounded.Audiotrack
        MediaKind.Image -> Icons.Rounded.Image
        MediaKind.Unknown -> Icons.Rounded.Download
    }
    val size = if (large) 78.dp else 64.dp

    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(if (large) 22.dp else 18.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = when (state) {
                            DownloadState.Completed -> listOf(LinkLiftSuccess.copy(alpha = 0.25f), LinkLiftAccent.copy(alpha = 0.18f))
                            DownloadState.Failed -> listOf(LinkLiftError.copy(alpha = 0.28f), LinkLiftCard)
                            else -> listOf(LinkLiftAccent.copy(alpha = 0.25f), LinkLiftBlue.copy(alpha = 0.18f))
                        }
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (large) 34.dp else 28.dp),
            )
        }
    }
}
