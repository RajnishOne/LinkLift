package com.rjnsdev.linklift.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rjnsdev.linklift.app.linkLiftListContentPadding
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccent
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccentBright
import com.rjnsdev.linklift.app.ui.theme.LinkLiftCard
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextMuted
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SettingsScreen(
    preferences: UserPreferences,
    isYouTubeAvailable: Boolean = true,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onPreferredPresetChanged: (QualityPreset) -> Unit,
    onSignInYouTube: () -> Unit,
    onImportCookiesFile: () -> Unit,
    onImportCookiesClipboard: () -> Unit,
    onClearYouTubeCookies: () -> Unit,
) {
    var showHelpDialog by remember { mutableStateOf(false) }

    if (isYouTubeAvailable && showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = LinkLiftAccentBright,
                )
            },
            title = {
                Text(
                    text = "YouTube & Cookies Authentication",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "YouTube enforces bot-detection (Proof-of-Origin / PoToken) on stream endpoints, causing 403 Forbidden errors for unauthenticated downloads.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LinkLiftTextSecondary,
                    )
                    Text(
                        text = "Signing in creates a local session cookie file on your device that lets LinkLift download high-resolution video and audio cleanly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LinkLiftTextSecondary,
                    )
                    Text(
                        text = "🔒 Privacy Guarantee: Your cookies stay strictly inside LinkLift's private sandbox on your phone. Nothing is ever sent to any third-party server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LinkLiftAccentBright,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("Got it")
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = linkLiftListContentPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isYouTubeAvailable) {
            item {
                YouTubeAuthSettingCard(
                    hasCookies = preferences.hasYouTubeCookies,
                    lastModified = preferences.youtubeCookiesLastModified,
                    onSignInClick = onSignInYouTube,
                    onImportFileClick = onImportCookiesFile,
                    onImportClipboardClick = onImportCookiesClipboard,
                    onClearClick = onClearYouTubeCookies,
                    onHelpClick = { showHelpDialog = true },
                )
            }
        }

        item {
            SettingToggleCard(
                icon = Icons.Rounded.Folder,
                title = "Download location",
                subtitle = preferences.downloadLocation,
            )
        }

        item {
            SettingSwitchCard(
                icon = Icons.Rounded.Wifi,
                title = "Download on Wi-Fi only",
                subtitle = "Only download when connected to Wi-Fi.",
                checked = preferences.wifiOnly,
                onCheckedChange = onWifiOnlyChanged,
            )
        }

        item {
            SettingSwitchCard(
                icon = Icons.Rounded.Notifications,
                title = "Completion notifications",
                subtitle = "Show notification when a download completes (requires permission on Android 13+).",
                checked = preferences.completionNotifications,
                onCheckedChange = onNotificationsChanged,
            )
        }

        item {
            NeonCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Default format",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Text(
                        text = "Preferred format to pre-select. You can still change this on the preview screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LinkLiftTextSecondary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QualityPreset.entries.forEach { preset ->
                            PresetRow(
                                preset = preset,
                                isSelected = preferences.preferredQuality == preset,
                                onClick = { onPreferredPresetChanged(preset) },
                            )
                        }
                    }
                }
            }
        }

        item {
            NeonCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "About LinkLift",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Text(
                        text = "Save files from direct links and supported public posts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LinkLiftTextSecondary,
                    )
                    Text(
                        text = "MERGE rows combine video and audio streams to unlock 1080p, 2K, and 4K resolutions on supported platforms.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LinkLiftTextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubeAuthSettingCard(
    hasCookies: Boolean,
    lastModified: Long,
    onSignInClick: () -> Unit,
    onImportFileClick: () -> Unit,
    onImportClipboardClick: () -> Unit,
    onClearClick: () -> Unit,
    onHelpClick: () -> Unit,
) {
    NeonCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, color = LinkLiftAccent.copy(alpha = 0.14f)) {
                        Icon(
                            imageVector = Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            tint = LinkLiftAccentBright,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "YouTube Authentication",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (hasCookies) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(16.dp),
                                )
                                val dateStr = remember(lastModified) {
                                    if (lastModified > 0L) {
                                        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(lastModified))
                                    } else "Active"
                                }
                                Text(
                                    text = "Session Active ($dateStr)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF81C784),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB74D),
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "Not Configured",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFFB74D),
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = onHelpClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                        contentDescription = "Help",
                        tint = LinkLiftTextSecondary,
                    )
                }
            }

            Text(
                text = if (hasCookies) {
                    "YouTube session cookies are active. Protected, age-restricted, and members-only videos will use these credentials when needed."
                } else {
                    "Sign in or import cookies to download age-restricted, members-only, or protected YouTube content without bot blocks."
                },
                style = MaterialTheme.typography.bodySmall,
                color = LinkLiftTextSecondary,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AuthOptionTile(
                    icon = Icons.Rounded.Lock,
                    title = if (hasCookies) "Re-authenticate in Browser" else "Sign In with Browser",
                    subtitle = "Log into YouTube via secure in-app browser session",
                    isPrimary = true,
                    onClick = onSignInClick,
                )

                AuthOptionTile(
                    icon = Icons.Rounded.FileUpload,
                    title = "Import Cookies File",
                    subtitle = "Select a cookies.txt file from device storage",
                    isPrimary = false,
                    onClick = onImportFileClick,
                )

                AuthOptionTile(
                    icon = Icons.Rounded.ContentPaste,
                    title = "Paste Cookies from Clipboard",
                    subtitle = "Import Netscape-formatted cookie text",
                    isPrimary = false,
                    onClick = onImportClipboardClick,
                )

                if (hasCookies) {
                    OutlinedButton(
                        onClick = onClearClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFFE57373).copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFE57373),
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = Color(0xFFE57373),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = "Clear YouTube Session Cookies",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthOptionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isPrimary: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (isPrimary) LinkLiftAccent.copy(alpha = 0.22f) else LinkLiftCard.copy(alpha = 0.75f),
        border = BorderStroke(
            1.dp,
            if (isPrimary) LinkLiftAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (isPrimary) LinkLiftAccent else LinkLiftAccent.copy(alpha = 0.15f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isPrimary) Color.White else LinkLiftAccentBright,
                    modifier = Modifier
                        .padding(9.dp)
                        .size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LinkLiftTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: QualityPreset,
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
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = when (preset) {
                    QualityPreset.BestQuality -> Icons.Rounded.Bolt
                    QualityPreset.SmallestFile -> Icons.Rounded.Speed
                    QualityPreset.AskEveryTime -> Icons.Rounded.GraphicEq
                },
                contentDescription = null,
                tint = if (isSelected) LinkLiftAccentBright else Color.White,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LinkLiftTextSecondary,
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = "Selected",
                    tint = LinkLiftAccentBright,
                )
            }
        }
    }
}

@Composable
private fun SettingToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    NeonCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = LinkLiftAccent.copy(alpha = 0.14f)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LinkLiftAccentBright,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LinkLiftTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    NeonCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = LinkLiftAccent.copy(alpha = 0.14f)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LinkLiftAccentBright,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LinkLiftTextSecondary,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
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
