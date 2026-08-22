package com.rjnsdev.linklift.app

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rjnsdev.linklift.app.ui.YouTubeAuthActivity
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccent
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccentBright
import com.rjnsdev.linklift.app.ui.theme.LinkLiftCard
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextMuted
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextSecondary
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTheme

@Composable
internal fun LinkLiftRoot(
    viewModel: LinkLiftViewModel,
    /** Call before queueing a download so Android 13+ can show DownloadManager / FGS notifications. */
    ensureNotificationPermission: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val youTubeAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.refreshCookieStatus()
        }
    }

    val importCookiesFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importCookiesFromUri(uri)
        }
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    BackHandler(enabled = uiState.currentScreen == AppScreen.Preview) {
        viewModel.backFromPreview()
    }
    BackHandler(enabled = uiState.currentScreen == AppScreen.Batch) {
        if (uiState.batchProgress?.isActive == true) {
            viewModel.cancelBatchProcessing()
        } else {
            viewModel.backFromBatch()
        }
    }
    BackHandler(enabled = uiState.currentScreen == AppScreen.Processing) {
        viewModel.cancelAnalysis()
    }

    val topBarTitle = when (uiState.currentScreen) {
        AppScreen.Home -> "Dashboard"
        AppScreen.Preview -> "Media Preview"
        AppScreen.Batch -> when (uiState.batch?.source) {
            BatchSource.Playlist -> "Playlist"
            BatchSource.BulkPaste -> "Bulk Links"
            null -> "Batch"
        }
        AppScreen.Downloads -> "Downloads"
        AppScreen.Settings -> "Settings"
        AppScreen.Processing,
        -> null
    }
    val topBarBackAction: (() -> Unit)? = when (uiState.currentScreen) {
        AppScreen.Preview -> viewModel::backFromPreview
        AppScreen.Batch -> {
            {
                if (uiState.batchProgress?.isActive == true) {
                    viewModel.cancelBatchProcessing()
                } else {
                    viewModel.backFromBatch()
                }
            }
        }
        else -> null
    }
    LinkLiftTheme(darkTheme = true) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                topBarTitle?.let { title ->
                    LinkLiftTopBar(
                        title = title,
                        showBack = topBarBackAction != null,
                        onBack = topBarBackAction,
                    )
                }
            },
            bottomBar = {
                if (uiState.currentScreen in setOf(AppScreen.Home, AppScreen.Downloads, AppScreen.Settings)) {
                    LinkLiftBottomBar(
                        currentScreen = uiState.currentScreen,
                        onNavigate = viewModel::openScreen,
                    )
                }
            },
        ) { innerPadding ->
            AppBackground {
                Crossfade(
                    targetState = uiState.currentScreen,
                    label = "linklift-screen",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) { screen ->
                    when (screen) {
                        AppScreen.Home -> HomeScreen(
                            uiState = uiState,
                            onUrlChanged = viewModel::onUrlChanged,
                            onAnalyze = viewModel::analyzeLink,
                        )
                        AppScreen.Processing -> ProcessingScreen(
                            uiState = uiState,
                            onCancel = viewModel::cancelAnalysis,
                        )
                        AppScreen.Preview -> PreviewScreen(
                            uiState = uiState,
                            onFormatSelected = viewModel::selectFormat,
                            onAudioOnlyToggled = viewModel::setAudioOnlyFilter,
                            onItemSelected = viewModel::selectPreviewItem,
                            onDownload = {
                                ensureNotificationPermission()
                                viewModel.queueDownload()
                            },
                            onDownloadAll = {
                                ensureNotificationPermission()
                                viewModel.queueAllDownloads()
                            },
                            onToggleItem = viewModel::toggleCarouselItem,
                            onSelectAllItems = viewModel::selectAllCarouselItems,
                            onClearItemSelection = viewModel::clearCarouselSelection,
                        )
                        AppScreen.Batch -> BatchScreen(
                            uiState = uiState,
                            onToggleEntry = viewModel::toggleBatchEntry,
                            onSelectAll = viewModel::selectAllBatchEntries,
                            onClear = viewModel::clearBatchSelection,
                            onCancel = {
                                if (uiState.batchProgress?.isActive == true) {
                                    viewModel.cancelBatchProcessing()
                                } else {
                                    viewModel.cancelBatch()
                                }
                            },
                            onDownload = {
                                ensureNotificationPermission()
                                viewModel.downloadSelectedBatchEntries()
                            },
                        )
                        AppScreen.Downloads -> DownloadsScreen(
                            downloads = uiState.downloads,
                            isYouTubeAvailable = uiState.isYouTubeAvailable,
                            onRemoveTracked = viewModel::removeTrackedDownloads,
                            onRegenerateTracked = viewModel::regenerateTrackedDownload,
                            onCancelDownload = viewModel::cancelDownload,
                            onPromptYouTubeAuth = {
                                viewModel.promptYouTubeAuth("A YouTube download was blocked. Sign in or import cookies to download this video.")
                            },
                        )
                        AppScreen.Settings -> SettingsScreen(
                            preferences = uiState.settings,
                            isYouTubeAvailable = uiState.isYouTubeAvailable,
                            onWifiOnlyChanged = viewModel::updateWifiOnly,
                            onNotificationsChanged = viewModel::updateCompletionNotifications,
                            onPreferredPresetChanged = viewModel::updatePreferredQuality,
                            onSignInYouTube = {
                                youTubeAuthLauncher.launch(YouTubeAuthActivity.createIntent(context))
                            },
                            onImportCookiesFile = {
                                importCookiesFileLauncher.launch(arrayOf("text/plain", "*/*"))
                            },
                            onImportCookiesClipboard = {
                                val clipText = clipboardManager.getText()?.text.orEmpty()
                                if (clipText.isNotBlank()) {
                                    viewModel.importCookiesFromText(clipText)
                                } else {
                                    viewModel.showMessage("Clipboard is empty")
                                }
                            },
                            onClearYouTubeCookies = viewModel::clearYouTubeCookies,
                        )
                    }
                }
            }

            if (uiState.isYouTubeAvailable && uiState.showYouTubeAuthPrompt) {
                YouTubeAuthPromptDialog(
                    reason = uiState.youTubeAuthPromptReason,
                    onSignIn = {
                        viewModel.dismissYouTubeAuthPrompt()
                        youTubeAuthLauncher.launch(YouTubeAuthActivity.createIntent(context))
                    },
                    onOpenSettings = {
                        viewModel.dismissYouTubeAuthPrompt()
                        viewModel.openScreen(AppScreen.Settings)
                    },
                    onDismiss = viewModel::dismissYouTubeAuthPrompt,
                )
            }
        }
    }
}

@Composable
private fun LinkLiftBottomBar(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.navigationBarsPadding(),
        containerColor = LinkLiftCard.copy(alpha = 0.95f),
        tonalElevation = 0.dp,
    ) {
        listOf(
            Triple(AppScreen.Home, "Home", Icons.Rounded.Home),
            Triple(AppScreen.Downloads, "Downloads", Icons.Rounded.CloudDownload),
            Triple(AppScreen.Settings, "Settings", Icons.Rounded.Settings),
        ).forEach { item ->
            NavigationBarItem(
                selected = currentScreen == item.first,
                onClick = { onNavigate(item.first) },
                icon = { Icon(imageVector = item.third, contentDescription = item.second) },
                label = { Text(item.second) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkLiftTopBar(
    title: String,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    TopAppBar(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "LinkLift",
                    style = MaterialTheme.typography.labelMedium,
                    color = LinkLiftAccentBright,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            if (showBack && onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
            }
        },
        actions = {
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = LinkLiftAccentBright)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = LinkLiftCard.copy(alpha = 0.96f),
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = LinkLiftAccentBright,
        ),
    )
}

@Composable
private fun YouTubeAuthPromptDialog(
    reason: String?,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = CircleShape,
                color = LinkLiftAccent.copy(alpha = 0.18f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = LinkLiftAccentBright,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(28.dp),
                )
            }
        },
        title = {
            Text(
                text = "YouTube Authentication",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = reason ?: "YouTube blocked extraction or download due to bot verification, age restriction, or missing cookies.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LinkLiftTextSecondary,
                )
                Text(
                    text = "Authenticate via the in-app browser or import session cookies to unlock clean downloads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LinkLiftTextMuted,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSignIn,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LinkLiftAccent,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Login,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 4.dp),
                )
                Text("Sign In")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = LinkLiftTextMuted)
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, LinkLiftAccent.copy(alpha = 0.5f)),
                ) {
                    Text("Open Settings", color = LinkLiftAccentBright)
                }
            }
        },
    )
}
