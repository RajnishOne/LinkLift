package com.rjnsdev.linklift.app

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionOff
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeMute
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.rjnsdev.linklift.app.linkLiftUserAgent
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccent
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccentBright
import com.rjnsdev.linklift.app.ui.theme.LinkLiftCard
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextSecondary

data class ResizeModeOption(
    val mode: Int,
    val label: String,
)

data class SubtitleTrackOption(
    val group: TrackGroup,
    val index: Int,
    val label: String,
    val language: String,
    val isSelected: Boolean,
)

data class AudioTrackOption(
    val group: TrackGroup,
    val index: Int,
    val label: String,
    val language: String,
    val isSelected: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkLiftVideoPlayer(
    mediaUri: String,
    audioUri: String? = null,
    height: Dp = 300.dp,
    httpHeaders: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    availableFormats: List<MediaFormat> = emptyList(),
    currentFormatId: String? = null,
    onFormatSelected: ((MediaFormat) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isMuted by remember { mutableStateOf(false) }
    var isLooping by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    var availableSubtitles by remember { mutableStateOf<List<SubtitleTrackOption>>(emptyList()) }
    var selectedSubtitleIndex by remember { mutableIntStateOf(-1) } // -1 means Off

    var availableAudioTracks by remember { mutableStateOf<List<AudioTrackOption>>(emptyList()) }
    var selectedAudioIndex by remember { mutableIntStateOf(0) }

    var savedPositionMs by remember { mutableLongStateOf(0L) }
    var wasPlayingState by remember { mutableStateOf(false) }

    val exoPlayer = remember(mediaUri, audioUri, httpHeaders) {
        val builder = ExoPlayer.Builder(context)
        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            val userAgent = httpHeaders.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                ?: linkLiftUserAgent
            setUserAgent(userAgent)
            if (httpHeaders.isNotEmpty()) {
                setDefaultRequestProperties(httpHeaders)
            }
            setAllowCrossProtocolRedirects(true)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        builder.setMediaSourceFactory(mediaSourceFactory)

        val player = builder.build()

        val videoSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(mediaUri))
        val finalSource = if (!audioUri.isNullOrBlank()) {
            val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUri))
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }

        player.setMediaSource(finalSource)
        player.repeatMode = Player.REPEAT_MODE_OFF
        if (savedPositionMs > 0L) {
            player.seekTo(savedPositionMs)
        }
        player.prepare()
        player.playWhenReady = wasPlayingState
        player
    }

    // Attach player listener for track changes and error logging
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("LinkLiftVideoPlayer", "ExoPlayer error [${error.errorCodeName} / ${error.errorCode}]: ${error.message}", error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                android.util.Log.d("LinkLiftVideoPlayer", "ExoPlayer playbackState: $playbackState duration: ${exoPlayer.duration}ms")
            }

            override fun onTracksChanged(tracks: Tracks) {
                val subs = mutableListOf<SubtitleTrackOption>()
                val audios = mutableListOf<AudioTrackOption>()
                var selSubIdx = -1
                var selAudIdx = 0

                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_TEXT) {
                        val mediaTrackGroup = group.mediaTrackGroup
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val lang = format.language.orEmpty()
                            val label = format.label.takeIf { !it.isNullBlinkOrBlank() }
                                ?: if (lang.isNotBlank()) lang.uppercase() else "Track ${subs.size + 1}"
                            val selected = group.isTrackSelected(i)
                            if (selected) selSubIdx = subs.size
                            subs.add(
                                SubtitleTrackOption(
                                    group = mediaTrackGroup,
                                    index = i,
                                    label = label,
                                    language = lang,
                                    isSelected = selected,
                                )
                            )
                        }
                    } else if (group.type == C.TRACK_TYPE_AUDIO) {
                        val mediaTrackGroup = group.mediaTrackGroup
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val lang = format.language.orEmpty()
                            val label = format.label.takeIf { !it.isNullBlinkOrBlank() }
                                ?: if (lang.isNotBlank()) lang.uppercase() else "Audio ${audios.size + 1}"
                            val selected = group.isTrackSelected(i)
                            if (selected) selAudIdx = audios.size
                            audios.add(
                                AudioTrackOption(
                                    group = mediaTrackGroup,
                                    index = i,
                                    label = label,
                                    language = lang,
                                    isSelected = selected,
                                )
                            )
                        }
                    }
                }

                availableSubtitles = subs
                selectedSubtitleIndex = selSubIdx
                availableAudioTracks = audios
                selectedAudioIndex = selAudIdx
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            savedPositionMs = exoPlayer.currentPosition
            wasPlayingState = exoPlayer.isPlaying
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Apply speed changes
    LaunchedEffect(playbackSpeed) {
        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    // Apply mute changes
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    // Apply loop changes
    LaunchedEffect(isLooping) {
        exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // Content container
    val playerContent: @Composable (isFull: Boolean) -> Unit = { isFull ->
        Box(
            modifier = if (isFull) {
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            } else {
                Modifier
                    .fillMaxWidth()
                    .requiredHeight(height)
            }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        this.resizeMode = resizeMode

                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                            isControlsVisible = (visibility == View.VISIBLE)
                        })
                        isControlsVisible = isControllerFullyVisible

                        // High contrast clean subtitle view styling
                        subtitleView?.apply {
                            setStyle(
                                CaptionStyleCompat(
                                    AndroidColor.WHITE,
                                    AndroidColor.TRANSPARENT,
                                    AndroidColor.TRANSPARENT,
                                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                    AndroidColor.BLACK,
                                    Typeface.DEFAULT_BOLD,
                                )
                            )
                            setFractionalTextSize(0.053f)
                        }
                    }
                },
                update = { view ->
                    view.resizeMode = resizeMode
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Top-left back action button overlay (animates with player controls)
            if (isFull || onBack != null) {
                AnimatedVisibility(
                    visible = isControlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    IconButton(
                        onClick = {
                            if (isFull) {
                                isFullscreen = false
                            } else {
                                onBack?.invoke()
                            }
                        },
                        modifier = Modifier
                            .padding(if (isFull) 16.dp else 8.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            .size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = if (isFull) "Exit Fullscreen" else "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Top-right quick actions bar overlay on video player (animates with player controls)
            AnimatedVisibility(
                visible = isControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Row(
                    modifier = Modifier
                        .padding(if (isFull) 16.dp else 8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // CC Subtitle Quick Toggle
                    val hasCc = availableSubtitles.isNotEmpty()
                    IconButton(
                        onClick = {
                            if (hasCc) {
                                if (selectedSubtitleIndex >= 0) {
                                    // Turn off
                                    selectedSubtitleIndex = -1
                                    updateSubtitleTrack(exoPlayer, null)
                                } else {
                                    // Turn on first available subtitle track
                                    selectedSubtitleIndex = 0
                                    updateSubtitleTrack(exoPlayer, availableSubtitles.first())
                                }
                            } else {
                                Toast.makeText(context, "No CC available for this video", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (hasCc && selectedSubtitleIndex >= 0) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionOff,
                            contentDescription = if (hasCc) "Closed Captions" else "CC Not Available",
                            tint = when {
                                !hasCc -> Color.White.copy(alpha = 0.35f)
                                selectedSubtitleIndex >= 0 -> LinkLiftAccentBright
                                else -> Color.White
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Aspect Ratio Quick Toggle
                    IconButton(
                        onClick = {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AspectRatio,
                            contentDescription = "Aspect Ratio",
                            tint = if (resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) LinkLiftAccentBright else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Player Settings (Speed, Tracks, etc.)
                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Player Settings",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Fullscreen Toggle
                    IconButton(
                        onClick = { isFullscreen = !isFullscreen },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (isFull) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            contentDescription = "Toggle Fullscreen",
                            tint = LinkLiftAccentBright,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }

    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val view = LocalView.current
            val activity = context as? Activity
            DisposableEffect(view, activity) {
                val dialogWindow = (view.context as? DialogWindowProvider)?.window
                    ?: (view.parent as? DialogWindowProvider)?.window
                    ?: (view.rootView.parent as? DialogWindowProvider)?.window

                val targetWindows = listOfNotNull(dialogWindow, activity?.window).distinct()

                targetWindows.forEach { win ->
                    WindowCompat.setDecorFitsSystemWindows(win, false)
                    win.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    val controller = WindowCompat.getInsetsController(win, win.decorView)
                    controller.hide(WindowInsetsCompat.Type.statusBars())
                    controller.hide(WindowInsetsCompat.Type.navigationBars())
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }

                onDispose {
                    targetWindows.forEach { win ->
                        win.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        val controller = WindowCompat.getInsetsController(win, win.decorView)
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black,
            ) {
                playerContent(true)
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = LinkLiftCard.copy(alpha = 0.9f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            modifier = modifier,
        ) {
            playerContent(false)
        }
    }

    // Modern Settings Sheet for Speed, Subtitles, Audio Tracks, and Resize Mode
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LinkLiftCard,
            contentColor = Color.White,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Player Controls & Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    IconButton(onClick = { showSettingsSheet = false }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Quick playback actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Rewind 10s
                    PlayerActionButton(
                        icon = Icons.Rounded.FastRewind,
                        label = "-10s",
                        onClick = {
                            val pos = (exoPlayer.currentPosition - 10_000).coerceAtLeast(0)
                            exoPlayer.seekTo(pos)
                        }
                    )

                    // Mute / Unmute
                    PlayerActionButton(
                        icon = if (isMuted) Icons.AutoMirrored.Rounded.VolumeMute else Icons.AutoMirrored.Rounded.VolumeUp,
                        label = if (isMuted) "Unmute" else "Mute",
                        isActive = isMuted,
                        onClick = { isMuted = !isMuted }
                    )

                    // Repeat toggle
                    PlayerActionButton(
                        icon = if (isLooping) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        label = if (isLooping) "Looping" else "Loop Off",
                        isActive = isLooping,
                        onClick = { isLooping = !isLooping }
                    )

                    // Fast Forward 10s
                    PlayerActionButton(
                        icon = Icons.Rounded.FastForward,
                        label = "+10s",
                        onClick = {
                            val duration = exoPlayer.duration.coerceAtLeast(0)
                            val pos = (exoPlayer.currentPosition + 10_000).coerceAtMost(duration)
                            exoPlayer.seekTo(pos)
                        }
                    )
                }

                // Playback Speed Selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Playback Speed (${playbackSpeed}x)",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                        speeds.forEach { speed ->
                            FilterChip(
                                selected = playbackSpeed == speed,
                                onClick = { playbackSpeed = speed },
                                label = { Text("${speed}x") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LinkLiftAccent,
                                    selectedLabelColor = Color.White,
                                ),
                            )
                        }
                    }
                }

                // Video Quality Selection
                if (availableFormats.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Video Quality",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            availableFormats.forEach { fmt ->
                                val label = fmt.label
                                val isSelected = fmt.formatId == currentFormatId
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        onFormatSelected?.invoke(fmt)
                                    },
                                    label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = LinkLiftAccent,
                                        selectedLabelColor = Color.White,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Aspect Ratio / Scaling Mode
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Aspect Ratio Mode",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val modes = listOf(
                            ResizeModeOption(AspectRatioFrameLayout.RESIZE_MODE_FIT, "Fit"),
                            ResizeModeOption(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Zoom"),
                            ResizeModeOption(AspectRatioFrameLayout.RESIZE_MODE_FILL, "Fill"),
                            ResizeModeOption(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, "Fixed Width"),
                        )
                        modes.forEach { option ->
                            FilterChip(
                                selected = resizeMode == option.mode,
                                onClick = { resizeMode = option.mode },
                                label = { Text(option.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LinkLiftAccent,
                                    selectedLabelColor = Color.White,
                                ),
                            )
                        }
                    }
                }

                // Subtitle Track Selection
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Subtitles / Closed Captions",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    if (availableSubtitles.isEmpty()) {
                        Text(
                            text = "No subtitle tracks detected in media source.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LinkLiftTextSecondary,
                        )
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = selectedSubtitleIndex < 0,
                                onClick = {
                                    selectedSubtitleIndex = -1
                                    updateSubtitleTrack(exoPlayer, null)
                                },
                                label = { Text("Off") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LinkLiftAccent,
                                    selectedLabelColor = Color.White,
                                ),
                            )
                            availableSubtitles.forEachIndexed { idx, sub ->
                                FilterChip(
                                    selected = selectedSubtitleIndex == idx,
                                    onClick = {
                                        selectedSubtitleIndex = idx
                                        updateSubtitleTrack(exoPlayer, sub)
                                    },
                                    label = { Text(sub.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = LinkLiftAccent,
                                        selectedLabelColor = Color.White,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Audio Track Selection
                if (availableAudioTracks.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Audio Track",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            availableAudioTracks.forEachIndexed { idx, audio ->
                                FilterChip(
                                    selected = selectedAudioIndex == idx,
                                    onClick = {
                                        selectedAudioIndex = idx
                                        updateAudioTrack(exoPlayer, audio)
                                    },
                                    label = { Text(audio.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = LinkLiftAccent,
                                        selectedLabelColor = Color.White,
                                    ),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PlayerActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable { onClick() },
    ) {
        Surface(
            shape = CircleShape,
            color = if (isActive) LinkLiftAccent else LinkLiftCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, LinkLiftAccentBright.copy(alpha = 0.5f)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color.White else LinkLiftAccentBright,
                modifier = Modifier.padding(12.dp).size(24.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LinkLiftTextSecondary,
        )
    }
}

private fun updateSubtitleTrack(player: ExoPlayer, option: SubtitleTrackOption?) {
    val builder = player.trackSelectionParameters.buildUpon()
    if (option == null) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(option.group, option.index))
    }
    player.trackSelectionParameters = builder.build()
}

private fun updateAudioTrack(player: ExoPlayer, option: AudioTrackOption) {
    val builder = player.trackSelectionParameters.buildUpon()
    builder.setOverrideForType(TrackSelectionOverride(option.group, option.index))
    player.trackSelectionParameters = builder.build()
}

private fun String?.isNullBlinkOrBlank(): Boolean {
    return this == null || this.trim().isEmpty() || this.trim().equals("null", ignoreCase = true)
}
