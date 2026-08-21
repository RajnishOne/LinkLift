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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccent
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccentBright
import com.rjnsdev.linklift.app.ui.theme.LinkLiftCard
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextMuted
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextSecondary

@Composable
internal fun BatchScreen(
    uiState: LinkLiftUiState,
    onToggleEntry: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
) {
    val batch = uiState.batch ?: return
    val selected = uiState.selectedBatchIndices
    val selectedCount = selected.size
    val progress = uiState.batchProgress
    val isProcessing = progress?.isActive == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            NeonCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = batch.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = LinkLiftAccentBright,
                            )
                            Text(
                                text = batch.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = when (batch.source) {
                                BatchSource.Playlist -> Icons.Rounded.PlayCircleOutline
                                BatchSource.BulkPaste -> Icons.Rounded.Collections
                            },
                            contentDescription = null,
                            tint = LinkLiftAccentBright,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    if (batch.uploader.isNotBlank()) {
                        Text(
                            text = batch.uploader,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LinkLiftTextSecondary,
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InfoChip(title = "${batch.returnedCount}", value = "Items shown")
                        if (batch.totalCount > batch.returnedCount) {
                            InfoChip(title = "${batch.totalCount}", value = "Total available")
                        }
                        InfoChip(title = "$selectedCount", value = "Selected")
                    }
                    if (batch.isTruncated) {
                        Text(
                            text = "Showing first ${batch.returnedCount} of ${batch.totalCount} items. Open source link to download the rest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LinkLiftAccentBright,
                        )
                    }
                    if (batch.entries.size > BATCH_AUTO_SELECT_LIMIT) {
                        Text(
                            text = "Large batch. Nothing selected by default. Check the items you want to download.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LinkLiftAccentBright,
                        )
                    }
                }
            }
        }

        if (progress != null) {
            item {
                NeonCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (isProcessing) "Resolving links..." else "Batch complete",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (progress.total <= 0) 0f
                                else (progress.processed.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = LinkLiftAccent,
                            trackColor = Color.White.copy(alpha = 0.08f),
                        )
                        Text(
                            text = "${progress.processed} of ${progress.total} • ${progress.succeeded} queued • ${progress.failed} failed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LinkLiftTextSecondary,
                        )
                        progress.currentTitle?.takeIf { isProcessing && it.isNotBlank() }?.let { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodySmall,
                                color = LinkLiftTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        item {
            NeonCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = onSelectAll,
                            enabled = !isProcessing && selectedCount < batch.entries.size,
                        ) {
                            Text("Select all")
                        }
                        TextButton(
                            onClick = onClear,
                            enabled = !isProcessing && selectedCount > 0,
                        ) {
                            Text("Clear")
                        }
                        TextButton(
                            onClick = onCancel,
                        ) {
                            Text(if (isProcessing) "Stop batch" else "Cancel")
                        }
                    }
                }
            }
        }

        itemsIndexed(batch.entries) { index, entry ->
            BatchEntryRow(
                entry = entry,
                isChecked = index in selected,
                isProcessing = isProcessing,
                onCheckedChange = { onToggleEntry(index) },
            )
        }

        item {
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp),
                enabled = !isProcessing && selectedCount > 0,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudDownload,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isProcessing) {
                        "Queuing downloads..."
                    } else if (selectedCount > 0) {
                        "Download $selectedCount item${if (selectedCount == 1) "" else "s"}"
                    } else {
                        "Select items to download"
                    }
                )
            }
        }
    }
}

@Composable
private fun BatchEntryRow(
    entry: LinkBatchEntry,
    isChecked: Boolean,
    isProcessing: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    NeonCard(
        modifier = if (isProcessing) Modifier else Modifier.clickable { onCheckedChange(!isChecked) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(16.dp),
                color = LinkLiftAccent.copy(alpha = 0.18f),
                border = androidx.compose.foundation.BorderStroke(1.dp, LinkLiftAccent.copy(alpha = 0.45f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircleOutline,
                        contentDescription = null,
                        tint = LinkLiftAccentBright,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaLine = buildList {
                    if (entry.uploader.isNotBlank()) add(entry.uploader)
                    entry.durationMs?.let { add(formatDuration(it)) }
                    if (entry.host.isNotBlank()) add(entry.host)
                }.joinToString(" • ")
                if (metaLine.isNotBlank()) {
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = LinkLiftTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = entry.sourceUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = LinkLiftTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                enabled = !isProcessing,
                colors = CheckboxDefaults.colors(
                    checkedColor = LinkLiftAccent,
                    checkmarkColor = Color.White,
                    uncheckedColor = LinkLiftAccentBright.copy(alpha = 0.8f),
                ),
            )
        }
    }
}
