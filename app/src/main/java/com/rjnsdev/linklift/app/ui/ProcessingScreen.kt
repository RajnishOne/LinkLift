package com.rjnsdev.linklift.app

import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rjnsdev.linklift.app.isLargeFont
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccent
import com.rjnsdev.linklift.app.ui.theme.LinkLiftAccentBright
import com.rjnsdev.linklift.app.ui.theme.LinkLiftCyan
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTextSecondary
import kotlinx.coroutines.delay

@Composable
internal fun ProcessingScreen(
    uiState: LinkLiftUiState,
    onCancel: () -> Unit,
) {
    val startedAt = uiState.analysisStartedAt
    var elapsedMs by remember(startedAt) { mutableStateOf(0L) }

    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            delay(150L)
        }
    }

    val largeFont = isLargeFont()
    val sectionSpacing = if (largeFont) 12.dp else 18.dp
    val progressSize = if (largeFont) 100.dp else 140.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(scrollState)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            NeonCard {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(progressSize),
                        strokeWidth = 6.dp,
                        color = LinkLiftAccent,
                        trackColor = Color.White.copy(alpha = 0.1f),
                    )
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (largeFont) 28.dp else 34.dp),
                    )
                }

                Text(
                    text = "Analyzing link",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Text(
                    text = "Getting your download ready. This may take a few seconds.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LinkLiftTextSecondary,
                    textAlign = TextAlign.Center,
                )

                val elapsedLabel = "${elapsedMs} ms"
                if (largeFont) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MetricPill(
                            icon = Icons.Rounded.Shield,
                            label = "Preparing download",
                            tint = LinkLiftAccentBright,
                        )
                        MetricPill(
                            icon = Icons.Rounded.Speed,
                            label = elapsedLabel,
                            tint = LinkLiftCyan,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MetricPill(
                            icon = Icons.Rounded.Shield,
                            label = "Preparing download",
                            tint = LinkLiftAccentBright,
                        )
                        MetricPill(
                            icon = Icons.Rounded.Speed,
                            label = elapsedLabel,
                            tint = LinkLiftCyan,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (largeFont) "Cancel" else "Cancel analysis")
                }
                }
            }
        }
    }
}
