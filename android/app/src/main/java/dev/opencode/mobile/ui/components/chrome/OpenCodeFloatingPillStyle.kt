package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.openCodeColor

/**
 * Fill and outline for pills that float over scrolling content. Android has no glass,
 * and `surface-raised-base` sits only a few percent off the page background, so the
 * fill is lifted toward white (like the composer dock) and gets a faint outline.
 */
object OpenCodeFloatingPillStyle {
    @Composable
    fun fill(enabled: Boolean = true): Color {
        val lift = if (LocalOpenCodeDarkTheme.current) DARK_LIFT else LIGHT_LIFT
        val base = lerp(openCodeColor("surface-raised-base"), Color.White, lift)
        return if (enabled) base else base.copy(alpha = 0.7f)
    }

    @Composable
    fun border(): BorderStroke {
        val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = BORDER_ALPHA)
        return BorderStroke(1.dp, tint)
    }

    private const val LIGHT_LIFT = 1f
    private const val DARK_LIFT = 0.1f
    private const val BORDER_ALPHA = 0.1f
}
