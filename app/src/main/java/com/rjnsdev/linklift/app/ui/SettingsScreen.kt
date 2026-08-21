package com.rjnsdev.linklift.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
internal fun SettingsScreen(
    preferences: UserPreferences,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onPreferredPresetChanged: (QualityPreset) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = linkLiftListContentPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
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
