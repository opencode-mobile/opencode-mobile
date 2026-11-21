package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
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

/** What the turn status pill says. */
internal sealed interface OpenCodeTurnStatusPillPhase {
    /** The turn is running; [label] is "Working", "Compacting…" and so on. */
    data class Working(
        val label: String,
    ) : OpenCodeTurnStatusPillPhase

    /** The turn just ended. */
    data object Done : OpenCodeTurnStatusPillPhase
}

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
 * The turn's status on floating pill chrome. Working to Done is one pill: the spinner bends
 * into the check while the width follows and the label rolls. [compact] folds it to a round
 * mark and [leaving] fades it out. With animations off it is a short crossfade.
 */
@Composable
internal fun OpenCodeTurnStatusPill(
    phase: OpenCodeTurnStatusPillPhase,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    leaving: Boolean = false,
) {
    val animationsOn = rememberFloatingPillAnimationsOn()
    val isDone = phase == OpenCodeTurnStatusPillPhase.Done
    val doneLabel = stringResource(R.string.common_done)
    var workingLabel by remember { mutableStateOf((phase as? OpenCodeTurnStatusPillPhase.Working)?.label ?: "") }
    (phase as? OpenCodeTurnStatusPillPhase.Working)?.let { workingLabel = it.label }
    val spoken = if (isDone) doneLabel else workingLabel

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
                }.defaultMinSize(minHeight = FloatingPillMinHeight)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    if (!isDone) liveRegion = LiveRegionMode.Polite
                },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = FloatingPillShape,
            color = OpenCodeFloatingPillStyle.fill(),
            border = OpenCodeFloatingPillStyle.border(),
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {}
        Row(
            modifier = Modifier.padding(horizontal = paddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TurnStatusMark(isDone = isDone, animationsOn = animationsOn)
            TurnStatusLabels(
                workingLabel = workingLabel,
                doneLabel = doneLabel,
                reveal = reveal,
                swap = { swap.value },
                rollOut = rollOut,
                rollIn = rollIn,
                slides = animationsOn,
            )
        }
    }
}

/** Spinner, the morph while it runs, then the check. */
@Composable
private fun TurnStatusMark(
    isDone: Boolean,
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
                color = openCodeColor("text-base"),
                maxLines = 1,
                softWrap = false,
                modifier =
                    Modifier.graphicsLayer {
                        alpha = 1f - rollOut
                        if (slides) translationY = -distance.toPx() * rollOut
                    },
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
        val loose = Constraints()
        val placeables = measurables.map { it.measure(loose) }
        val gap = TurnPillIconSpacing.toPx()
        val first = placeables[0].width
        val last = placeables[1].width
        val labelWidth = first + (last - first) * swap()
        val width = ((labelWidth + gap) * reveal).roundToInt().coerceAtLeast(0)
        val height = max(placeables[0].height, placeables[1].height)
        layout(width, height) {
            placeables.forEach { it.placeRelative(gap.roundToInt(), (height - it.height) / 2) }
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
            OpenCodeTurnStatusPill(OpenCodeTurnStatusPillPhase.Working("Working"), compact = true)
            OpenCodeTurnStatusPill(OpenCodeTurnStatusPillPhase.Done, compact = true)
        }
    }
}
