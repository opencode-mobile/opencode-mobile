package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

/** Distance the fade extends above the dock card top. */
val OpenCodeBottomFadeOverhang = 16.dp

@Composable
fun OpenCodeBottomFade(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val background = openCodeColor("background-base")
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height.coerceAtLeast(0.dp))
                .background(
                    Brush.verticalGradient(
                        colorStops =
                            arrayOf(
                                0f to background.copy(alpha = 0f),
                                0.5f to background.copy(alpha = 0.7f),
                                1f to background.copy(alpha = 0.7f),
                            ),
                    ),
                ),
    )
}
