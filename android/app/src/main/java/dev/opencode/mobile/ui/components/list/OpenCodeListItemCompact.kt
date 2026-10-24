package dev.opencode.mobile.ui.components

import android.provider.Settings
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.openCodeColor
import kotlin.math.ceil

private val CompactRowMinHeight = 66.dp
private val CompactRowLineSpacing = 5.dp
private val CompactTileSize = 30.dp
private val CompactTileGlyphSize = 13.sp
private val CompactStatusDotSize = 8.dp
private val CompactStatusDotRing = 2.5.dp
// Puts the dot's centre on the circle's edge at its top-end.
private val CompactStatusDotOutset = 2.dp
private val CompactPathSeparatorGap = 5.dp
private const val COMPACT_PATH_SEPARATOR = "/"
private const val STATUS_DOT_PULSE_HALF_CYCLE_MS = 1_200
private const val STATUS_DOT_PULSE_MIN_ALPHA = 0.3f

/** Line 2 of a [OpenCodeListItemVariant.Compact] row: the project, then a worktree when there is one. */
data class OpenCodeListItemPath(
    val project: String,
    val worktree: String? = null,
)

/**
 * How `project / worktree` shares line 2. Both keep their full width when they fit. Otherwise
 * the project keeps its width when it fits in 40% of the line, else the worktree keeps its width
 * when it fits in 60%, else 40/60. The worktree is cut in the middle to fit its part.
 */
internal object OpenCodeListItemPathSplit {
    private const val PROJECT_SHARE = 0.4f
    private const val WORKTREE_SHARE = 0.6f

    /** The shortest a middle-cut name gets, in kept characters. */
    private const val MIN_KEPT = 5
    const val ELLIPSIS = "…"

    data class Widths(
        val project: Float,
        val worktree: Float,
    )

    /** Widths given to each name; [available] excludes the separator. */
    fun widths(
        project: Float,
        worktree: Float,
        available: Float,
    ): Widths {
        if (project + worktree <= available) return Widths(project, worktree)
        val projectWidth =
            when {
                project <= PROJECT_SHARE * available -> project
                worktree <= WORKTREE_SHARE * available -> available - worktree
                else -> PROJECT_SHARE * available
            }
        return Widths(projectWidth, (available - projectWidth).coerceAtLeast(0f))
    }

    /** [text] with its middle replaced by an ellipsis so that [kept] characters remain, a little more of the start. */
    fun middleCut(
        text: String,
        kept: Int,
    ): String {
        if (kept >= text.length) return text
        val head = ceil(kept / 2.0).toInt()
        val tail = kept - head
        return text.take(head) + ELLIPSIS + text.takeLast(tail)
    }

    /** The longest start of [text] with an ellipsis that [fits]; the first character when none does. */
    fun endCutToFit(
        text: String,
        fits: (String) -> Boolean,
    ): String {
        if (fits(text)) return text
        var low = 1
        var high = text.length - 1
        var best: String? = null
        while (low <= high) {
            val kept = (low + high) / 2
            val candidate = text.take(kept) + ELLIPSIS
            if (fits(candidate)) {
                best = candidate
                low = kept + 1
            } else {
                high = kept - 1
            }
        }
        return best ?: (text.take(1) + ELLIPSIS)
    }

    /** The longest middle cut of [text] that [fits]; the shortest cut when none does. */
    fun middleCutToFit(
        text: String,
        fits: (String) -> Boolean,
    ): String {
        if (fits(text)) return text
        if (text.length - 1 <= MIN_KEPT) return text
        var low = MIN_KEPT
        var high = text.length - 1
        var best: String? = null
        while (low <= high) {
            val kept = (low + high) / 2
            val candidate = middleCut(text, kept)
            if (fits(candidate)) {
                best = candidate
                low = kept + 1
            } else {
                high = kept - 1
            }
        }
        return best ?: middleCut(text, MIN_KEPT)
    }
}

@Composable
internal fun CompactListItemContent(
    title: String,
    titleEmphasized: Boolean,
    path: OpenCodeListItemPath?,
    subtitle: String?,
    status: OpenCodeListItemStatus?,
    caption: String?,
    startDecorator: StartDecorator,
    enabled: Boolean,
) {
    val textStrong = openCodeColor("text-strong")
    val textBase = openCodeColor("text-base")
    val textWeak = openCodeColor("text-weak")
    val textWeaker = openCodeColor("text-weaker")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CompactRowMinHeight)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ListItemStartDecoration(decorator = startDecorator, enabled = enabled)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(CompactRowLineSpacing),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    color = if (enabled) textStrong else textWeaker,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (titleEmphasized) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!caption.isNullOrEmpty()) {
                    Text(
                        text = caption,
                        color = if (enabled) textWeak else textWeaker,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val lineModifier = Modifier.weight(1f)
                when {
                    path != null -> ListItemPathText(path = path, enabled = enabled, modifier = lineModifier)
                    !subtitle.isNullOrEmpty() ->
                        Text(
                            text = subtitle,
                            color = if (enabled) textBase else textWeaker,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = lineModifier,
                        )
                    else -> Box(lineModifier)
                }
                if (status != null) {
                    ListItemCompactStatusLabel(status = status, enabled = enabled)
                }
            }
        }
    }
}

/** Status as coloured text, the path's size one weight up, without the pill's chip. */
@Composable
private fun ListItemCompactStatusLabel(
    status: OpenCodeListItemStatus,
    enabled: Boolean,
) {
    Text(
        text = status.text,
        color = if (enabled) status.tone.foreground() else openCodeColor("text-weaker"),
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
    )
}

/** `project / worktree` on one line, shared by [OpenCodeListItemPathSplit]. */
@Composable
private fun ListItemPathText(
    path: OpenCodeListItemPath,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodySmall
    val projectStyle = style.copy(fontWeight = FontWeight.Medium)
    val projectColor = if (enabled) openCodeColor("text-strong").copy(alpha = 0.8f) else openCodeColor("text-weaker")
    val worktreeColor = if (enabled) openCodeColor("text-base") else openCodeColor("text-weaker")
    val separatorColor = if (enabled) openCodeColor("text-weak") else openCodeColor("text-weaker")
    val worktree = path.worktree
    if (worktree.isNullOrEmpty()) {
        Text(
            text = path.project,
            color = projectColor,
            style = projectStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth
        val fitted =
            remember(path, maxWidthPx, style, density) {
                fun width(
                    text: String,
                    textStyle: TextStyle,
                ): Int = measurer.measure(text, textStyle, maxLines = 1, softWrap = false).size.width
                val gapPx = with(density) { CompactPathSeparatorGap.roundToPx() }
                val available = (maxWidthPx - width(COMPACT_PATH_SEPARATOR, style) - 2 * gapPx).coerceAtLeast(0)
                val widths =
                    OpenCodeListItemPathSplit.widths(
                        project = width(path.project, projectStyle).toFloat(),
                        worktree = width(worktree, style).toFloat(),
                        available = available.toFloat(),
                    )
                val projectBudget = widths.project.toInt()
                val worktreeBudget = widths.worktree.toInt()
                // Cut by hand rather than with an ellipsis overflow, which would keep the full box
                // and leave a gap before the separator.
                FittedPath(
                    project = OpenCodeListItemPathSplit.endCutToFit(path.project) { width(it, projectStyle) <= projectBudget },
                    worktree = OpenCodeListItemPathSplit.middleCutToFit(worktree) { width(it, style) <= worktreeBudget },
                )
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CompactPathSeparatorGap),
        ) {
            Text(
                text = fitted.project,
                color = projectColor,
                style = projectStyle,
                maxLines = 1,
                softWrap = false,
            )
            Text(text = COMPACT_PATH_SEPARATOR, color = separatorColor, style = style, maxLines = 1)
            Text(
                text = fitted.worktree,
                color = worktreeColor,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                softWrap = false,
            )
        }
    }
}

private data class FittedPath(
    val project: String,
    val worktree: String,
)

/** Compact round letter badge with an optional status dot on its top-end edge. */
@Composable
internal fun ListItemTile(
    decorator: StartDecorator.Tile,
    modifier: Modifier = Modifier,
) {
    val backgroundBase = openCodeColor("background-base")
    Box(modifier = modifier.size(CompactTileSize)) {
        ListItemLetterCircle(
            character = decorator.character,
            accent = decorator.accent,
            glyphSize = CompactTileGlyphSize,
            modifier = Modifier.size(CompactTileSize),
        )
        val tone = decorator.statusDot
        if (tone != null) {
            val alpha = rememberStatusDotAlpha(decorator.pulsing)
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = CompactStatusDotOutset, y = -CompactStatusDotOutset)
                        .size(CompactStatusDotSize + CompactStatusDotRing * 2)
                        .background(backgroundBase, CircleShape)
                        .padding(CompactStatusDotRing)
                        .graphicsLayer { this.alpha = alpha() }
                        .background(tone.foreground(), CircleShape)
                        .semantics { hideFromAccessibility() },
            )
        }
    }
}

/** Running fades 1 → 0.3 → 1 over 2.4s; still when system animations are off. */
@Composable
private fun rememberStatusDotAlpha(pulsing: Boolean): () -> Float {
    val context = LocalContext.current
    val animationsOff =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
    if (!pulsing || animationsOff) return { 1f }
    val transition = rememberInfiniteTransition(label = "listItemStatusDot")
    val alpha =
        transition.animateFloat(
            initialValue = 1f,
            targetValue = STATUS_DOT_PULSE_MIN_ALPHA,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = STATUS_DOT_PULSE_HALF_CYCLE_MS, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "listItemStatusDotAlpha",
        )
    return { alpha.value }
}
