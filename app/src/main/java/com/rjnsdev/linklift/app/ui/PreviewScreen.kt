package com.rjnsdev.linklift.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccent
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccentBright
import com.rjnsdev.linklift.app.ui.theme.LinkLiftCard
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextSecondary
import java.util.Locale

@Composable
internal fun PreviewScreen(
    uiState: LinkLiftUiState,
    onFormatSelected: (itemIndex: Int, formatId: String) -> Unit,
    onAudioOnlyToggled: (Boolean) -> Unit,
    onItemSelected: (Int) -> Unit,
    onDownload: () -> Unit,
    onDownloadAll: () -> Unit,
    onToggleItem: (Int) -> Unit,
    onSelectAllItems: () -> Unit,
    onClearItemSelection: () -> Unit,
) {
    val preview = uiState.preview ?: return
    val currentIndex = uiState.selectedPreviewIndex
    val currentItem = selectedPreviewItem(preview, currentIndex)
    val hasMultipleItems = preview.items.size > 1
    val selectedIndices = uiState.selectedItemIndices
    val selectedCount = selectedIndices.size
    val allFormats = currentItem.formats
    val displayFormats = if (uiState.showAudioOnly) {
        allFormats.filter { it.isAudioOnly }
    } else {
        allFormats
    }
    val hasFormats = allFormats.isNotEmpty()
    val hasAudioOnly = allFormats.any { it.isAudioOnly }
    val selectedFormatId = uiState.selectedFormatIdsByItem[currentIndex]
    val selectedFormat = allFormats.firstOrNull { it.formatId == selectedFormatId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            MediaHero(
                item = currentItem,
                selectedFormat = selectedFormat,
                onFormatSelected = { fmt -> onFormatSelected(currentIndex, fmt.formatId) },
            )
        }

        item {
            NeonCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = currentItem.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    ExpandableDescriptionText(
                        text = currentItem.description,
                        suffix = if (hasMultipleItems) "Item ${uiState.selectedPreviewIndex + 1} of ${preview.items.size}" else null,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LinkLiftTextSecondary,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InfoChip(title = preview.host, value = "Source")
                        InfoChip(title = formatBytes(currentItem.fileSizeBytes), value = "Size")
                        currentItem.durationMs?.let { InfoChip(title = formatDuration(it), value = "Duration") }
                        currentItem.resolution?.let { InfoChip(title = it, value = "Resolution") }
                    }
                }
            }
        }

        if (hasMultipleItems) {
            item {
                NeonCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TitleActionRow(
                            title = "Post items",
                            action = "$selectedCount of ${preview.items.size} selected",
                        )
                        Text(
                            text = "Tap a chip to preview an item. Check the items you want to download.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LinkLiftTextSecondary,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            preview.items.forEachIndexed { index, item ->
                                FilterChip(
                                    selected = index == uiState.selectedPreviewIndex,
                                    onClick = { onItemSelected(index) },
                                    label = {
                                        Text(
                                            text = when (item.kind) {
                                                MediaKind.Video -> "Video ${index + 1}"
                                                MediaKind.Image -> "Photo ${index + 1}"
                                                MediaKind.Audio -> "Audio ${index + 1}"
                                                MediaKind.Unknown -> "Item ${index + 1}"
                                            }
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = when (item.kind) {
                                                MediaKind.Video -> Icons.Rounded.PlayArrow
                                                MediaKind.Image -> Icons.Rounded.Image
                                                MediaKind.Audio -> Icons.Rounded.GraphicEq
                                                MediaKind.Unknown -> Icons.Rounded.Collections
                                            },
                                            contentDescription = null,
                                        )
                                    },
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            preview.items.forEachIndexed { index, item ->
                                CarouselItemRow(
                                    index = index,
                                    item = item,
                                    isActive = index == uiState.selectedPreviewIndex,
                                    isChecked = index in selectedIndices,
                                    onCheckedChange = { onToggleItem(index) },
                                    onRowClick = { onItemSelected(index) },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = onSelectAllItems,
                                enabled = selectedCount < preview.items.size,
                            ) {
                                Text("Select all")
                            }
                            TextButton(
                                onClick = onClearItemSelection,
                                enabled = selectedCount > 0,
                            ) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }
        }

        item {
            FormatPickerCard(
                preview = preview,
                formats = displayFormats,
                hasAudioOnly = hasAudioOnly,
                hasAnyFormats = hasFormats,
                audioOnlyEnabled = uiState.showAudioOnly,
                selectedFormatId = selectedFormatId,
                onAudioOnlyToggled = onAudioOnlyToggled,
                onFormatSelected = { fmt -> onFormatSelected(currentIndex, fmt.formatId) },
                preferredPreset = uiState.settings.preferredQuality,
            )
        }

        item {
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                Icon(
                    imageVector = if (selectedFormat?.isAudioOnly == true) {
                        Icons.Rounded.Audiotrack
                    } else {
                        Icons.Rounded.Download
                    },
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                val baseLabel = if (hasMultipleItems) "Download current item" else "Download"
                val sizeHint = selectedFormat?.fileSizeBytes
                    ?.let { formatBytes(it) }
                    ?.takeIf { it != "Unknown" }
                Text(if (sizeHint != null) "$baseLabel • $sizeHint" else baseLabel)
            }
        }

        if (hasMultipleItems) {
            item {
                OutlinedButton(
                    onClick = onDownloadAll,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    enabled = selectedCount > 0,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Collections,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (selectedCount > 0) {
                            "Download $selectedCount selected item${if (selectedCount == 1) "" else "s"}"
                        } else {
                            "Select items to download"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatPickerCard(
    preview: MediaPreview,
    formats: List<MediaFormat>,
    hasAudioOnly: Boolean,
    hasAnyFormats: Boolean,
    audioOnlyEnabled: Boolean,
    selectedFormatId: String?,
    preferredPreset: QualityPreset,
    onAudioOnlyToggled: (Boolean) -> Unit,
    onFormatSelected: (MediaFormat) -> Unit,
) {
    NeonCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TitleActionRow(
                title = "Format",
                action = preferredPreset.label,
            )

            when {
                !hasAnyFormats -> {
                    Text(
                        text = if (preview.isDirectLink) {
                            "Direct link. LinkLift will save the original file."
                        } else {
                            "This source only exposes one quality option."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = LinkLiftTextSecondary,
                    )
                }
                else -> {
                    Text(
                        text = "Choose a resolution or audio-only stream.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LinkLiftTextSecondary,
                    )

                    if (hasAudioOnly) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (audioOnlyEnabled) {
                                LinkLiftAccent.copy(alpha = 0.18f)
                            } else {
                                LinkLiftCard.copy(alpha = 0.7f)
                            },
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (audioOnlyEnabled) {
                                    LinkLiftAccent
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAudioOnlyToggled(!audioOnlyEnabled) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Audiotrack,
                                    contentDescription = null,
                                    tint = if (audioOnlyEnabled) LinkLiftAccentBright else Color.White,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Audio only",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                    )
                                    Text(
                                        text = "Save only the audio track.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LinkLiftTextSecondary,
                                    )
                                }
                                Switch(
                                    checked = audioOnlyEnabled,
                                    onCheckedChange = onAudioOnlyToggled,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = LinkLiftAccent,
                                        checkedBorderColor = LinkLiftAccentBright,
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                                        uncheckedBorderColor = LinkLiftAccentBright.copy(alpha = 0.45f),
                                    ),
                                )
                            }
                        }
                    }

                    if (formats.isEmpty()) {
                        Text(
                            text = "No matching formats for the current filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LinkLiftTextSecondary,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            formats.forEach { format ->
                                FormatRow(
                                    format = format,
                                    isSelected = format.formatId == selectedFormatId,
                                    onClick = { onFormatSelected(format) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatRow(
    format: MediaFormat,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) LinkLiftAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val backgroundColor = if (isSelected) LinkLiftAccent.copy(alpha = 0.18f) else LinkLiftCard.copy(alpha = 0.7f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (format.isAudioOnly) Icons.Rounded.Audiotrack else Icons.Rounded.PlayCircleOutline,
                contentDescription = null,
                tint = if (isSelected) LinkLiftAccentBright else Color.White,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = format.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                val subtitleParts = buildList {
                    format.fileSizeBytes
                        ?.let { formatBytes(it) }
                        ?.takeIf { it != "Unknown" }
                        ?.let { size ->
                            add(if (format.fileSizeApprox) "~$size" else size)
                        }
                    if (!format.isAudioOnly) {
                        format.fps?.takeIf { it >= 50 }?.let { add("${it}fps") }
                    } else {
                        format.abr?.let { add("${it} kbps") }
                    }
                    if (format.ext.isNotBlank()) add(format.ext.uppercase(Locale.ROOT))
                }
                val subtitle = subtitleParts.joinToString(" • ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = LinkLiftTextSecondary,
                    )
                }
                if (format.requiresMerge) {
                    Text(
                        text = "Muxes video and audio. Saves to Downloads/LinkLift.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LinkLiftTextSecondary.copy(alpha = 0.85f),
                    )
                }
            }
            if (format.requiresMerge) {
                MergeBadge()
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = "Selected",
                    tint = LinkLiftAccentBright,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CarouselItemRow(
    index: Int,
    item: PreviewMediaItem,
    isActive: Boolean,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRowClick: () -> Unit,
) {
    val accentBorder = if (isActive) LinkLiftAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRowClick() },
        shape = RoundedCornerShape(20.dp),
        color = if (isActive) LinkLiftAccent.copy(alpha = 0.12f) else LinkLiftCard.copy(alpha = 0.7f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = when (item.kind) {
                    MediaKind.Video -> Icons.Rounded.PlayArrow
                    MediaKind.Image -> Icons.Rounded.Image
                    MediaKind.Audio -> Icons.Rounded.GraphicEq
                    MediaKind.Unknown -> Icons.Rounded.Collections
                },
                contentDescription = null,
                tint = if (isActive) LinkLiftAccentBright else Color.White,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (item.kind) {
                        MediaKind.Video -> "Video ${index + 1}"
                        MediaKind.Image -> "Photo ${index + 1}"
                        MediaKind.Audio -> "Audio ${index + 1}"
                        MediaKind.Unknown -> "Item ${index + 1}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                val subtitle = buildList {
                    item.resolution?.takeIf { it.isNotBlank() }?.let { add(it) }
                    item.durationMs?.let { add(formatDuration(it)) }
                    item.fileSizeBytes?.let { add(formatBytes(it)) }
                }.joinToString(" • ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = LinkLiftTextSecondary,
                    )
                }
            }
            Checkbox(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = LinkLiftAccent,
                    checkmarkColor = Color.White,
                    uncheckedColor = LinkLiftAccentBright.copy(alpha = 0.8f),
                ),
            )
        }
    }
}

@Composable
internal fun MediaHero(
    item: PreviewMediaItem,
    selectedFormat: MediaFormat? = null,
    onFormatSelected: ((MediaFormat) -> Unit)? = null,
) {
    when (item.kind) {
        MediaKind.Image -> {
            Surface(
                shape = RoundedCornerShape(30.dp),
                color = LinkLiftCard.copy(alpha = 0.9f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            ) {
                AsyncImage(
                    model = item.resolvedUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp)
                        .clip(RoundedCornerShape(30.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        MediaKind.Video -> VideoPreviewCard(
            item = item,
            selectedFormat = selectedFormat,
            onFormatSelected = onFormatSelected,
        )
        MediaKind.Audio -> AudioPreviewCard(item = item)
        MediaKind.Unknown -> EmptyStateCard(
            icon = Icons.Rounded.ErrorOutline,
            title = "Preview unavailable",
            subtitle = "This source cannot be previewed yet.",
        )
    }
}

@Composable
private fun VideoPreviewCard(
    item: PreviewMediaItem,
    selectedFormat: MediaFormat? = null,
    onFormatSelected: ((MediaFormat) -> Unit)? = null,
) {
    val activeFormat = selectedFormat ?: item.formats.firstOrNull { !it.isAudioOnly }
    val mediaUri = activeFormat?.mediaUrl ?: item.resolvedUrl
    val audioUri = activeFormat?.mergeAudio?.mediaUrl
    val previewHeaders = activeFormat?.httpHeaders
        ?: item.formats.firstOrNull { it.mediaUrl == item.resolvedUrl }?.httpHeaders
        ?: emptyMap()

    MediaPlayerSurface(
        mediaUri = mediaUri,
        audioUri = audioUri,
        height = 300.dp,
        httpHeaders = previewHeaders,
        availableFormats = item.formats.filter { !it.isAudioOnly },
        currentFormatId = activeFormat?.formatId,
        onFormatSelected = onFormatSelected,
    )
}

@Composable
internal fun MediaPlayerSurface(
    mediaUri: String,
    audioUri: String? = null,
    height: Dp,
    httpHeaders: Map<String, String> = emptyMap(),
    availableFormats: List<MediaFormat> = emptyList(),
    currentFormatId: String? = null,
    onFormatSelected: ((MediaFormat) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    LinkLiftVideoPlayer(
        mediaUri = mediaUri,
        audioUri = audioUri,
        height = height,
        httpHeaders = httpHeaders,
        availableFormats = availableFormats,
        currentFormatId = currentFormatId,
        onFormatSelected = onFormatSelected,
        onBack = onBack,
    )
}

@Composable
private fun AudioPreviewCard(item: PreviewMediaItem) {
    NeonCard {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Surface(
                shape = CircleShape,
                color = LinkLiftAccent.copy(alpha = 0.14f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = LinkLiftAccentBright,
                    modifier = Modifier
                        .padding(22.dp)
                        .size(38.dp),
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Text(
                text = "Your audio file is ready to preview and download.",
                style = MaterialTheme.typography.bodyMedium,
                color = LinkLiftTextSecondary,
            )
        }
    }
}
