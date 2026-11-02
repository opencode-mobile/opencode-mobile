package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.openCodeColor

/**
 * Fill and outline for pills that float over scrolling content. Android has no glass,
 * and `surface-raised-base` sits only a few percent off the page background, so the
 * fill is lifted toward white (like the composer dock) and gets a faint outline. A theme
 * whose page is already white keeps its raised surface, since lifting would erase the pill.
 */
object OpenCodeFloatingPillStyle {
    @Composable
    fun fill(enabled: Boolean = true): Color {
        val lift = if (LocalOpenCodeDarkTheme.current) DARK_LIFT else LIGHT_LIFT
        val base = liftedFill(openCodeColor("surface-raised-base"), openCodeColor("background-base"), lift)
        return if (enabled) base else base.copy(alpha = 0.7f)
    }

    /** Lifts [raised] toward white only when that moves it further from the [page] than [raised] already is. */
    internal fun liftedFill(
        raised: Color,
        page: Color,
        lift: Float,
    ): Color {
        val lifted = lerp(raised, Color.White, lift)
        val liftedGap = abs(lifted.luminance() - page.luminance())
        val raisedGap = abs(raised.luminance() - page.luminance())
        return if (liftedGap >= raisedGap) lifted else raised
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
