package dev.opencode.mobile.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.opencode.mobile.domain.preferences.AppearanceTextRegion
import dev.opencode.mobile.domain.preferences.AppearanceTextSizes

/** The app's text sizes, provided once at the theme root. */
val LocalOpenCodeTextSizes = staticCompositionLocalOf { AppearanceTextSizes.DEFAULT }

/** Density from outside every text region: the phone's own font scale. */
private val LocalOpenCodeBaseDensity = staticCompositionLocalOf<Density?> { null }

/** Text-size multiplier of the region this composable sits in; 1 outside every region. */
private val LocalOpenCodeTextScale = staticCompositionLocalOf { 1f }

/**
 * Draws [content] at [region]'s app text size (#1044). The size multiplies the phone's own font
 * scale, so every `sp` inside, from any style in [Typography] or [CodeTextStyle], scales with it
 * and nothing outside does. Regions do not compound: a Code region inside a Session Chat region
 * (a tool output sheet opened from the timeline) draws at the Code size alone.
 */
@Composable
fun OpenCodeTextRegion(
    region: AppearanceTextRegion,
    content: @Composable () -> Unit,
) {
    val multiplier = LocalOpenCodeTextSizes.current[region].multiplier
    val base = LocalOpenCodeBaseDensity.current ?: LocalDensity.current
    val scaled = remember(base, multiplier) { if (multiplier == 1f) base else ScaledTextDensity(base, multiplier) }
    CompositionLocalProvider(
        LocalOpenCodeBaseDensity provides base,
        LocalDensity provides scaled,
        LocalOpenCodeTextScale provides multiplier,
        content = content,
    )
}

/**
 * Scales a `dp` size that sits beside text and has to grow with it, such as a session row's
 * letter avatar. Outside a text region it returns [size] unchanged.
 */
@Composable
@ReadOnlyComposable
fun openCodeTextScaled(size: Dp): Dp = size * LocalOpenCodeTextScale.current

/**
 * [size] in dp as [region] draws it, for text Compose does not lay out, such as the terminal's
 * WebView.
 */
@Composable
@ReadOnlyComposable
fun openCodeRegionTextSize(
    region: AppearanceTextRegion,
    size: TextUnit,
): Dp {
    val base = LocalOpenCodeBaseDensity.current ?: LocalDensity.current
    return with(base) { size.toDp() } * LocalOpenCodeTextSizes.current[region].multiplier
}

/**
 * The phone's density with every `sp` multiplied by [multiplier] after the phone's own
 * (possibly non-linear) font scaling, so the app size multiplies what the phone would draw.
 */
internal class ScaledTextDensity(
    private val base: Density,
    private val multiplier: Float,
) : Density {
    override val density: Float
        get() = base.density

    override val fontScale: Float
        get() = base.fontScale * multiplier

    override fun TextUnit.toDp(): Dp = with(base) { this@toDp.toDp() } * multiplier

    override fun Dp.toSp(): TextUnit = with(base) { (this@toSp / multiplier).toSp() }
}
