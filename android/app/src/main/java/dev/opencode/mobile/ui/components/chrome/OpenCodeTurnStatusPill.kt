package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.text.ResourcesUiTextLookup
import kotlin.math.max
import kotlin.math.roundToInt

private const val TURN_PILL_WIDTH_MS = 420
private const val TURN_PILL_ROLL_OUT_MS = 220
private const val TURN_PILL_ROLL_IN_MS = 300
private const val TURN_PILL_ROLL_IN_DELAY_MS = 110
private const val TURN_PILL_CROSSFADE_MS = 160
private const val TURN_PILL_LEAVE_MS = 240
private const val TURN_PILL_LEAVING_SCALE = 0.96f
private val TurnPillRollDistance = 9.dp
private val TurnPillIconSize = 16.dp
private val TurnPillIconSpacing = 6.dp
private val TurnPillPaddingHorizontal = 14.dp
private val TurnPillCompactPaddingHorizontal = 10.dp
private val TurnPillWidthEasing = CubicBezierEasing(0.3f, 1.18f, 0.5f, 1f)
private val TurnPillRollOutEasing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
private val TurnPillRollInEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** The mark of a turn pill that waits on the user. */
internal enum class OpenCodeTurnStatusAttentionMark {
    /** Waiting: a pulsing dot. */
    PulsingDot,

    /** A collapsed panel: an up arrow, since a tap brings it back. */
    Reopen,
}

/** What the turn status pill says. */
internal sealed interface OpenCodeTurnStatusPillPhase {
    /** The turn is running; [label] is "Working", "Compacting…" and so on. */
    data class Working(
        val label: String,
    ) : OpenCodeTurnStatusPillPhase

    /** The turn waits on the user: amber text and ring. */
    data class Attention(
        val label: String,
        val mark: OpenCodeTurnStatusAttentionMark,
    ) : OpenCodeTurnStatusPillPhase

    /** The turn failed: error text, no mark. */
    data class Failed(
        val label: String,
    ) : OpenCodeTurnStatusPillPhase

    /** The turn just ended. */
    data object Done : OpenCodeTurnStatusPillPhase
}

/** The label a phase shows; [doneLabel] for Done. */
internal fun OpenCodeTurnStatusPillPhase.label(doneLabel: String): String =
    when (this) {
        is OpenCodeTurnStatusPillPhase.Working -> label
        is OpenCodeTurnStatusPillPhase.Attention -> label
        is OpenCodeTurnStatusPillPhase.Failed -> label
        OpenCodeTurnStatusPillPhase.Done -> doneLabel
    }

/** Everything in a full turn pill except its label text. */
internal fun openCodeTurnStatusPillChromeWidth(
    phase: OpenCodeTurnStatusPillPhase,
    hasClose: Boolean,
): Dp = floatingPillChromeWidth(hasMark = phase !is OpenCodeTurnStatusPillPhase.Failed, hasClose = hasClose)

/**
 * The monochrome floating pill spinner: one round-capped arc in [color]. Clock-driven and read
 * in the draw phase, so a re-created composable does not restart it and a frame never
 * recomposes. A still arc with animations off.
 */
@Composable
internal fun OpenCodeArcSpinner(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val animationsOn = rememberFloatingPillAnimationsOn()
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (animationsOn) {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameMillis { clock.longValue = System.currentTimeMillis() }
            }
        }
    }
    Canvas(modifier.size(TurnPillIconSize)) {
        val pose = if (animationsOn) OpenCodeSpinnerGeometry.pose(clock.longValue) else OpenCodeSpinnerGeometry.StillPose
        drawPolyline(OpenCodeSpinnerGeometry.arcPoints(pose), color, OpenCodeSpinnerGeometry.STROKE_WIDTH)
    }
}

/** Draws [points], given in the 16-unit box, scaled to this canvas. */
private fun DrawScope.drawPolyline(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float,
) {
    val unit = size.minDimension / OpenCodeSpinnerGeometry.BOX_SIZE
    val path = Path()
    points.forEachIndexed { index, point ->
        if (index == 0) path.moveTo(point.x * unit, point.y * unit) else path.lineTo(point.x * unit, point.y * unit)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * The turn's status on floating pill chrome: one pill that changes state in place. Working to
 * Done bends the spinner into the check while the width follows and the label rolls. [compact]
 * folds it to a round mark and [leaving] fades it out. With animations off it is a short
 * crossfade. [onClick] makes it a button, and [onClose] adds the trailing ✕ for a failure.
 */
@Composable
internal fun OpenCodeTurnStatusPill(
    phase: OpenCodeTurnStatusPillPhase,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    leaving: Boolean = false,
    /** Caps the whole pill when the row cannot fit it: the label truncates. */
    maxWidthPx: Int? = null,
    accessibilityLabel: String? = null,
    onClick: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val animationsOn = rememberFloatingPillAnimationsOn()
    val chromeWidthPx = with(LocalDensity.current) { openCodeTurnStatusPillChromeWidth(phase, onClose != null).roundToPx() }
    val maxLabelPx = maxWidthPx?.let { (it - chromeWidthPx).coerceAtLeast(0) }
    val isDone = phase == OpenCodeTurnStatusPillPhase.Done
    val isFailed = phase is OpenCodeTurnStatusPillPhase.Failed
    val attentionMark = (phase as? OpenCodeTurnStatusPillPhase.Attention)?.mark
    val doneLabel = stringResource(R.string.common_done)
    // The last label that was not Done, so it can roll out while Done rolls in.
    var activeLabel by remember { mutableStateOf(if (isDone) "" else phase.label(doneLabel)) }
    if (!isDone) activeLabel = phase.label(doneLabel)
    val shownLabel = if (isDone) doneLabel else activeLabel
    val spoken = accessibilityLabel ?: shownLabel
    val warning = openCodeColor("warning")
    val activeColor by animateColorAsState(
        targetValue =
            when (phase) {
                is OpenCodeTurnStatusPillPhase.Attention -> warning
                is OpenCodeTurnStatusPillPhase.Failed -> MaterialTheme.colorScheme.error
                else -> openCodeColor("text-base")
            },
        animationSpec = tween(if (animationsOn) TURN_PILL_ROLL_OUT_MS else 0),
        label = "turnPillTone",
    )
    val markGap by animateDpAsState(
        targetValue = if (isFailed) 0.dp else TurnPillIconSpacing,
        animationSpec = if (animationsOn) tween(260, easing = FastOutSlowInEasing) else tween(0),
        label = "turnPillMarkGap",
    )

    val paddingHorizontal by animateDpAsState(
        targetValue = if (compact) TurnPillCompactPaddingHorizontal else TurnPillPaddingHorizontal,
        animationSpec = if (animationsOn) tween(260, easing = FastOutSlowInEasing) else tween(0),
        label = "turnPillPadding",
    )
    val reveal by animateFloatAsState(
        targetValue = if (compact) 0f else 1f,
        animationSpec = if (animationsOn) tween(260, easing = FastOutSlowInEasing) else tween(0),
        label = "turnPillReveal",
    )
    val leave by animateFloatAsState(
        targetValue = if (leaving) 1f else 0f,
        animationSpec = tween(TURN_PILL_LEAVE_MS),
        label = "turnPillLeave",
    )
    val swap = remember { Animatable(if (isDone) 1f else 0f) }
    LaunchedEffect(isDone, animationsOn) {
        val target = if (isDone) 1f else 0f
        if (animationsOn && isDone) {
            swap.animateTo(target, tween(TURN_PILL_WIDTH_MS, easing = TurnPillWidthEasing))
        } else {
            swap.snapTo(target)
        }
    }
    val rollOut by animateFloatAsState(
        targetValue = if (isDone) 1f else 0f,
        animationSpec =
            if (animationsOn) tween(TURN_PILL_ROLL_OUT_MS, easing = TurnPillRollOutEasing) else tween(TURN_PILL_CROSSFADE_MS),
        label = "turnPillRollOut",
    )
    val rollIn by animateFloatAsState(
        targetValue = if (isDone) 1f else 0f,
        animationSpec =
            if (animationsOn) {
                tween(TURN_PILL_ROLL_IN_MS, delayMillis = TURN_PILL_ROLL_IN_DELAY_MS, easing = TurnPillRollInEasing)
            } else {
                tween(TURN_PILL_CROSSFADE_MS)
            },
        label = "turnPillRollIn",
    )
    val latestOnClose by rememberUpdatedState(onClose)
    val closeSpec = tween<IntSize>(260, easing = FastOutSlowInEasing)

    // The surface sits behind the content, so its clip does not cut the ✕'s 48 dp touch target.
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    alpha = 1f - leave
                    if (animationsOn) {
                        val scale = 1f + (TURN_PILL_LEAVING_SCALE - 1f) * leave
                        scaleX = scale
                        scaleY = scale
                    }
                }.defaultMinSize(minHeight = FloatingPillMinHeight),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = FloatingPillShape,
            color = OpenCodeFloatingPillStyle.fill(),
            border = if (attentionMark != null) BorderStroke(1.dp, warning) else OpenCodeFloatingPillStyle.border(),
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {}
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier =
                    Modifier
                        .clip(FloatingPillShape)
                        .then(
                            if (onClick != null) {
                                Modifier
                                    .openCodeFocusRing(FloatingPillShape)
                                    .clickable(role = Role.Button, onClick = onClick)
                            } else {
                                Modifier
                            },
                        ).clearAndSetSemantics {
                            contentDescription = spoken
                            if (!isDone) liveRegion = LiveRegionMode.Polite
                        }.defaultMinSize(minHeight = FloatingPillMinHeight)
                        .padding(horizontal = paddingHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A failure has no mark, unless it is ever folded and the mark is all there is.
                AnimatedVisibility(
                    visible = !isFailed || compact,
                    enter = expandHorizontally(closeSpec, expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(closeSpec, shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    TurnStatusMark(isDone = isDone, attentionMark = attentionMark, tint = activeColor, animationsOn = animationsOn)
                }
                TurnStatusLabels(
                    workingLabel = activeLabel,
                    doneLabel = doneLabel,
                    workingColor = activeColor,
                    maxLabelPx = maxLabelPx,
                    gap = markGap,
                    reveal = reveal,
                    swap = { swap.value },
                    rollOut = rollOut,
                    rollIn = rollIn,
                    slides = animationsOn,
                )
            }
            AnimatedVisibility(
                visible = onClose != null && !compact,
                enter = expandHorizontally(closeSpec, expandFrom = Alignment.Start, clip = false) + fadeIn(),
                exit = shrinkHorizontally(closeSpec, shrinkTowards = Alignment.Start, clip = false) + fadeOut(),
            ) {
                FloatingPillClose(
                    description =
                        floatingPillCloseDescription(
                            OpenCodeFloatingPillLayout.Closable(activeLabel),
                            ResourcesUiTextLookup(LocalResources.current),
                        ),
                    onClose = { latestOnClose?.invoke() },
                )
            }
        }
    }
}

/** Spinner, the morph while it runs, then the check. */
@Composable
private fun TurnStatusMark(
    isDone: Boolean,
    attentionMark: OpenCodeTurnStatusAttentionMark?,
    tint: Color,
    animationsOn: Boolean,
) {
    val base = openCodeColor("text-base")
    val strong = openCodeColor("text-strong")
    var morph by remember { mutableStateOf<OpenCodeCheckMorph?>(null) }
    var wasDone by remember { mutableStateOf(isDone) }
    val elapsedMs = remember { mutableLongStateOf(0L) }
    if (isDone != wasDone) {
        // Set during composition so the first Done frame is already the morph, not a check.
        morph =
            if (isDone && animationsOn) {
                OpenCodeCheckMorph(OpenCodeSpinnerGeometry.pose(System.currentTimeMillis()))
            } else {
                null
            }
        elapsedMs.longValue = 0L
        wasDone = isDone
    }
    val running = morph
    LaunchedEffect(running) {
        if (running == null) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (elapsedMs.longValue < OpenCodeCheckMorph.TOTAL_MS) {
            withFrameMillis { elapsedMs.longValue = it - start }
        }
        morph = null
    }
    Box(modifier = Modifier.size(TurnPillIconSize), contentAlignment = Alignment.Center) {
        when {
            attentionMark == OpenCodeTurnStatusAttentionMark.PulsingDot -> FloatingPillPulsingDot()
            attentionMark == OpenCodeTurnStatusAttentionMark.Reopen ->
                Icon(OpenCodeIcons.ArrowUpward, contentDescription = null, tint = tint, modifier = Modifier.size(TurnPillIconSize))
            running != null ->
                Canvas(
                    Modifier
                        .size(TurnPillIconSize)
                        .graphicsLayer {
                            val scale = OpenCodeCheckMorph.scale(elapsedMs.longValue.toDouble()).toFloat()
                            scaleX = scale
                            scaleY = scale
                        },
                ) {
                    val ms = elapsedMs.longValue.toDouble()
                    val progress = OpenCodeCheckMorph.colorProgress(ms)
                    val points = running.points(ms)
                    val width = OpenCodeCheckMorph.strokeWidth(ms)
                    drawPolyline(points, base.copy(alpha = base.alpha * (1 - progress)), width)
                    drawPolyline(points, strong.copy(alpha = strong.alpha * progress), width)
                }
            isDone ->
                Icon(OpenCodeIcons.Check, contentDescription = null, tint = strong, modifier = Modifier.size(TurnPillIconSize))
            else -> OpenCodeArcSpinner(color = base)
        }
    }
}

/** Both labels stay measured, so the width can travel between them while they roll. */
@Composable
private fun TurnStatusLabels(
    workingLabel: String,
    doneLabel: String,
    workingColor: Color,
    maxLabelPx: Int?,
    gap: Dp,
    reveal: Float,
    swap: () -> Float,
    rollOut: Float,
    rollIn: Float,
    slides: Boolean,
) {
    val style = MaterialTheme.typography.labelLarge
    val distance = TurnPillRollDistance
    Layout(
        modifier =
            Modifier
                .graphicsLayer { alpha = reveal }
                // Clip sideways only, so the rolling label can travel past its own line.
                .drawWithContent {
                    clipRect(top = -distance.toPx(), bottom = size.height + distance.toPx()) {
                        this@drawWithContent.drawContent()
                    }
                },
        content = {
            Text(
                text = workingLabel,
                style = style,
                color = workingColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .graphicsLayer {
                            alpha = 1f - rollOut
                            if (slides) translationY = -distance.toPx() * rollOut
                        }
                        // A new label eases the pill's width instead of snapping it.
                        .animateContentSize(if (slides) tween(260, easing = FastOutSlowInEasing) else snap()),
            )
            Text(
                text = doneLabel,
                style = style,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                softWrap = false,
                modifier =
                    Modifier.graphicsLayer {
                        alpha = rollIn
                        if (slides) translationY = distance.toPx() * (1f - rollIn)
                    },
            )
        },
    ) { measurables, _ ->
        val loose = Constraints(maxWidth = maxLabelPx ?: Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        val gapPx = gap.toPx()
        val first = placeables[0].width
        val last = placeables[1].width
        val labelWidth = first + (last - first) * swap()
        val width = ((labelWidth + gapPx) * reveal).roundToInt().coerceAtLeast(0)
        val height = max(placeables[0].height, placeables[1].height)
        layout(width, height) {
            placeables.forEach { it.placeRelative(gapPx.roundToInt(), (height - it.height) / 2) }
        }
    }
}

@Preview(showBackground = true, name = "Turn Status Pill")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Turn Status Pill Dark")
@Composable
private fun OpenCodeTurnStatusPillPreview() {
    OpenCodeTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OpenCodeTurnStatusPill(OpenCodeTurnStatusPillPhase.Working("Working"))
            OpenCodeTurnStatusPill(OpenCodeTurnStatusPillPhase.Done)
            OpenCodeTurnStatusPill(OpenCodeTurnStatusPillPhase.Attention("Waiting for you", OpenCodeTurnStatusAttentionMark.PulsingDot))
            OpenCodeTurnStatusPill(OpenCodeTurnStatusPillPhase.Attention("Review permission", OpenCodeTurnStatusAttentionMark.Reopen), onClick = {})
            OpenCodeTurnStatusPill(OpenCodeTurnStatusPillPhase.Failed("Request failed"), onClick = {}, onClose = {})
            OpenCodeTurnStatusPill(OpenCodeTurnStatusPillPhase.Working("Working"), compact = true)
            OpenCodeTurnStatusPill(OpenCodeTurnStatusPillPhase.Done, compact = true)
        }
    }
}
