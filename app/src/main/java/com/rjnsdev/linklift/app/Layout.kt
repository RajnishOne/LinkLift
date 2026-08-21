package com.rjnsdev.linklift.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

internal const val LargeFontScaleThreshold = 1.3f

@Composable
internal fun isLargeFont(): Boolean =
    LocalConfiguration.current.fontScale >= LargeFontScaleThreshold

internal fun linkLiftListContentPadding(): PaddingValues =
    PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 16.dp)

/** Keeps compact chip/pill labels at a stable size while body text still scales. */
@Composable
internal fun chipTextStyle(base: TextStyle): TextStyle {
    val fontScale = LocalDensity.current.fontScale
    return base.copy(
        fontSize = base.fontSize / fontScale,
        lineHeight = base.lineHeight / fontScale,
    )
}
