package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors
import dev.opencode.mobile.theme.openCodeColor

private const val SKELETON_SHIMMER_CYCLE_MS = 1_400L
private const val SKELETON_SHIMMER_HALF_WIDTH = 0.35f
private const val SKELETON_ROW_OPACITY_STEP = 0.12f
private const val SKELETON_MIN_ROW_OPACITY = 0.2f
private const val SKELETON_AVATAR_ALPHA = 0.3f
private const val SKELETON_BAR_ALPHA = 0.35f
private const val SKELETON_HIGHLIGHT_ALPHA = 0.12f
private val SkeletonAvatarSize = 32.dp
private val SkeletonTitleHeight = 13.dp
private val SkeletonSubtitleHeight = 10.dp
private val SkeletonTrailingWidth = 44.dp
private val SkeletonTrailingHeight = 18.dp
private val SkeletonBarRadius = 4.dp

/** Title and subtitle bar widths (fraction of the text column) per row, cycled down the list. */
private val SkeletonBarWidths =
    listOf(
        0.72f to 0.46f,
        0.58f to 0.52f,
        0.80f to 0.40f,
        0.64f to 0.50f,
        0.50f to 0.44f,
        0.70f to 0.48f,
    )

/** Accessibility label the skeleton list announces. */
const val SESSION_LIST_SKELETON_LABEL = "Loading sessions"

/**
 * Placeholder session rows for a project whose sessions are still loading. Rows match the
 * session row layout (letter avatar, title, subtitle, trailing status pill), fade down the list,
 * and shimmer unless system animations are off.
 */
@Composable
fun OpenCodeSessionListSkeleton(
    modifier: Modifier = Modifier,
    rowCount: Int = 6,
    accent: LetterAccent? = null,
    caption: String? = null,
    /** Optional section header above the rows, e.g. the loading project's name. */
    title: String? = null,
) {
    require(rowCount > 0) { "OpenCodeSessionListSkeleton requires at least one row" }
    val phase = rememberSkeletonShimmerPhase()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = if (caption != null) "$SESSION_LIST_SKELETON_LABEL. $caption" else SESSION_LIST_SKELETON_LABEL
                },
    ) {
        if (title != null) {
            OpenCodeSectionHeader(title = title)
        }
        repeat(rowCount) { index ->
            SkeletonRow(
                index = index,
                accent = accent,
                phase = phase,
                modifier = Modifier.alpha((1f - index * SKELETON_ROW_OPACITY_STEP).coerceAtLeast(SKELETON_MIN_ROW_OPACITY)),
            )
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weaker"),
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

/** One placeholder session row; [index] picks the bar widths. */
@Composable
fun OpenCodeSessionListSkeletonRow(
    modifier: Modifier = Modifier,
    index: Int = 0,
    accent: LetterAccent? = null,
) {
    val phase = rememberSkeletonShimmerPhase()
    SkeletonRow(
        index = index,
        accent = accent,
        phase = phase,
        modifier = modifier.semantics { contentDescription = SESSION_LIST_SKELETON_LABEL },
    )
}

@Composable
private fun SkeletonRow(
    index: Int,
    accent: LetterAccent?,
    phase: State<Float>?,
    modifier: Modifier = Modifier,
) {
    val (titleWidth, subtitleWidth) = SkeletonBarWidths[index.mod(SkeletonBarWidths.size)]
    val bar = openCodeColor("text-weaker").copy(alpha = SKELETON_BAR_ALPHA)
    val highlight = openCodeColor("text-weaker").copy(alpha = SKELETON_HIGHLIGHT_ALPHA)
    val avatar =
        if (accent != null) {
            accent.badgeColors(isSystemInDarkTheme()).second.copy(alpha = SKELETON_AVATAR_ALPHA)
        } else {
            bar
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clearAndSetSemantics {}
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(SkeletonAvatarSize)
                    .background(avatar, CircleShape),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SkeletonBar(
                width = titleWidth,
                height = SkeletonTitleHeight,
                radius = SkeletonBarRadius,
                base = bar,
                highlight = highlight,
                phase = phase,
            )
            SkeletonBar(
                width = subtitleWidth,
                height = SkeletonSubtitleHeight,
                radius = SkeletonBarRadius,
                base = bar,
                highlight = highlight,
                phase = phase,
            )
        }
        Box(
            modifier =
                Modifier
                    .width(SkeletonTrailingWidth)
                    .height(SkeletonTrailingHeight)
                    .shimmerFill(base = bar, highlight = highlight, radius = OpenCodeRadius.pill, phase = phase),
        )
    }
}

@Composable
private fun SkeletonBar(
    width: Float,
    height: Dp,
    radius: Dp,
    base: Color,
    highlight: Color,
    phase: State<Float>?,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth(width)
                .height(height)
                .shimmerFill(base = base, highlight = highlight, radius = radius, phase = phase),
    )
}

/** Rounded fill with a highlight band sweeping left to right; static when [phase] is null. */
private fun Modifier.shimmerFill(
    base: Color,
    highlight: Color,
    radius: Dp,
    phase: State<Float>?,
): Modifier =
    drawBehind {
        val cornerRadius = CornerRadius(minOf(radius.toPx(), size.height / 2))
        if (phase == null) {
            drawRoundRect(color = base, cornerRadius = cornerRadius)
            return@drawBehind
        }
        // Read in the draw phase so the sweep never recomposes.
        val half = SKELETON_SHIMMER_HALF_WIDTH
        val center = -half + phase.value * (1 + 2 * half)
        drawRoundRect(
            brush =
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(size.width * (center - half), 0f),
                    end = Offset(size.width * (center + half), 0f),
                ),
            cornerRadius = cornerRadius,
        )
    }

/** 0…1 through one shimmer cycle from the frame clock, or null when system animations are off. */
@Composable
private fun rememberSkeletonShimmerPhase(): State<Float>? {
    val context = LocalContext.current
    val animationsOff =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
    if (animationsOff) return null
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMillis ->
                phase.floatValue = (frameMillis % SKELETON_SHIMMER_CYCLE_MS).toFloat() / SKELETON_SHIMMER_CYCLE_MS
            }
        }
    }
    return phase
}

@Preview(showBackground = true, name = "Session List Skeleton")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Session List Skeleton Dark")
@Composable
private fun OpenCodeSessionListSkeletonPreview() {
    OpenCodeTheme {
        OpenCodeSessionListSkeleton(
            accent = LetterAccent.Violet,
            title = "opencode-mobile",
            caption = "Still loading — large projects can take a moment",
        )
    }
}
