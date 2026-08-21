package com.rjnsdev.linklift.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.rjnsdev.linklift.app.isLargeFont
import com.rjnsdev.linklift.app.linkLiftListContentPadding
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccent
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccentBright
import com.rjnsdev.linklift.app.ui.theme.LinkLiftCard
import com.rjnsdev.linklift.app.ui.theme.LinkLiftCyan
import com.rjnsdev.linklift.app.ui.theme.LinkLiftSuccess
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextMuted
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextSecondary



@Composable
internal fun HomeScreen(
    uiState: LinkLiftUiState,
    onUrlChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = linkLiftListContentPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            NeonCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricPill(
                        icon = Icons.Rounded.Bolt,
                        label = "Fast downloads",
                        tint = LinkLiftSuccess,
                    )
                    Text(
                        text = "Seamless media downloads",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Text(
                        text = "Paste a direct file or public post link to download media.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LinkLiftTextSecondary,
                    )
                }
            }
        }

        item {
            LinkComposerCard(
                url = uiState.inputUrl,
                onUrlChanged = onUrlChanged,
                onAnalyze = onAnalyze,
            )
        }

        item {
            SectionHeader(
                title = "Supported links",
                action = "Popular apps",
            )
        }

        item {
            val items = rememberSupportLinkItems(
                isSoundCloudAvailable = uiState.isSoundCloudAvailable,
                isImgurAvailable = uiState.isImgurAvailable
            )
            SupportLinksGrid(items = items)
        }
    }
}



@Composable
internal fun LinkComposerCard(
    url: String,
    onUrlChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
) {
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val detectedUrls = remember(url) { extractAllUrls(url) }
    val isMultiUrl = detectedUrls.size > 1
    val firstUrl = detectedUrls.firstOrNull()
    val looksLikePlaylist = remember(firstUrl) {
        firstUrl?.let { looksLikePlaylistUrl(it) } == true
    }
    val analyzeLabel = when {
        isMultiUrl -> "Review ${detectedUrls.size} links"
        looksLikePlaylist -> "Open playlist"
        else -> "Analyze & Download"
    }
    val analyzeIcon = when {
        isMultiUrl -> Icons.Rounded.Collections
        else -> Icons.Rounded.CloudDownload
    }

    NeonCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "Paste media link",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                text = "Paste a video, audio, playlist, or multiple URLs to queue them.",
                style = MaterialTheme.typography.bodyMedium,
                color = LinkLiftTextSecondary,
            )
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6,
                placeholder = { Text("Paste one or more links here") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = LinkLiftTextSecondary,
                    )
                },
                colors = linkFieldColors(),
            )

            if (isMultiUrl || looksLikePlaylist) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isMultiUrl) {
                        MetricPill(
                            icon = Icons.Rounded.Collections,
                            label = "${detectedUrls.size} links detected",
                            tint = LinkLiftAccentBright,
                        )
                    }
                    if (looksLikePlaylist) {
                        MetricPill(
                            icon = Icons.Rounded.PlayCircleOutline,
                            label = "Playlist or channel link",
                            tint = LinkLiftCyan,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    keyboardController?.hide()
                    onAnalyze()
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                Icon(
                    imageVector = analyzeIcon,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(analyzeLabel)
            }
        }
    }
}

@Composable
internal fun linkFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    focusedIndicatorColor = LinkLiftAccent,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedPlaceholderColor = LinkLiftTextMuted,
    unfocusedPlaceholderColor = LinkLiftTextMuted,
)

private data class SupportLinkItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

@Composable
private fun rememberSupportLinkItems(
    isSoundCloudAvailable: Boolean,
    isImgurAvailable: Boolean,
): List<SupportLinkItem> {
    return remember(isSoundCloudAvailable, isImgurAvailable) {
        buildList {
            add(SupportLinkItem("Direct files", "MP4, WebM, MP3, JPG", Icons.Rounded.PlayCircleOutline))
            add(SupportLinkItem("Instagram", "Public posts and reels only", Icons.Rounded.PlayCircleOutline))
            add(SupportLinkItem("TikTok & Video links", "Public video posts", Icons.Rounded.Bolt))
            add(SupportLinkItem("X, Facebook & Vimeo", "Public posts and videos", Icons.Rounded.GraphicEq))
            add(SupportLinkItem("Pinterest & Reddit", "Public posts", Icons.Rounded.Image))
            add(SupportLinkItem("Twitch & Streamable", "Clips, VODs and hosted video", Icons.Rounded.LiveTv))
            if (isSoundCloudAvailable) {
                add(SupportLinkItem("SoundCloud", "Tracks and audio playlists", Icons.Rounded.MusicNote))
            }
            if (isImgurAvailable) {
                add(SupportLinkItem("Imgur", "Public images and albums", Icons.Rounded.Image))
            }
            add(SupportLinkItem("Dailymotion & Rumble", "Public video links", Icons.Rounded.VideoLibrary))
        }
    }
}

@Composable
private fun SupportLinksGrid(
    items: List<SupportLinkItem>,
    modifier: Modifier = Modifier,
) {
    val largeFont = isLargeFont()
    val spacing = 12.dp
    val minCellWidth = if (largeFont) 200.dp else 168.dp

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = remember(maxWidth, minCellWidth, spacing) {
            ((maxWidth + spacing) / (minCellWidth + spacing))
                .toInt()
                .coerceAtLeast(1)
        }
        val cellWidth = remember(maxWidth, columns, spacing) {
            (maxWidth - spacing * (columns - 1)) / columns
        }

        SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
            val spacingPx = spacing.roundToPx()
            val cellWidthPx = cellWidth.roundToPx()
            val naturalHeights = items.mapIndexed { index, item ->
                subcompose("measure-$index") {
                    SupportCard(
                        modifier = Modifier.width(cellWidth),
                        title = item.title,
                        subtitle = item.subtitle,
                        icon = item.icon,
                    )
                }.first().measure(Constraints.fixedWidth(cellWidthPx)).height
            }
            val uniformHeightPx = naturalHeights.maxOrNull() ?: 0
            val uniformHeight = uniformHeightPx.toDp()

            val rowPlaceables = items.indices.chunked(columns).map { rowIndices ->
                rowIndices.map { index ->
                    val item = items[index]
                    subcompose("layout-$index") {
                        SupportCard(
                            modifier = Modifier
                                .width(cellWidth)
                                .height(uniformHeight),
                            title = item.title,
                            subtitle = item.subtitle,
                            icon = item.icon,
                        )
                    }.first().measure(Constraints.fixed(cellWidthPx, uniformHeightPx))
                }
            }

            val rowCount = rowPlaceables.size
            val totalHeight = if (rowCount == 0) {
                0
            } else {
                uniformHeightPx * rowCount + spacingPx * (rowCount - 1)
            }

            layout(constraints.maxWidth, totalHeight) {
                var y = 0
                rowPlaceables.forEach { row ->
                    var x = 0
                    row.forEach { placeable ->
                        placeable.place(x, y)
                        x += cellWidthPx + spacingPx
                    }
                    y += uniformHeightPx + spacingPx
                }
            }
        }
    }
}

@Composable
internal fun SupportCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = LinkLiftCard.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = LinkLiftAccent.copy(alpha = 0.14f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LinkLiftAccentBright,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = LinkLiftTextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


