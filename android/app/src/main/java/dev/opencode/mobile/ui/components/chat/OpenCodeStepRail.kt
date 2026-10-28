package dev.opencode.mobile.ui.components

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionElapsed
import kotlinx.coroutines.delay

/** Status of one step on an [OpenCodeStepRail]; drives the dot tone. */
enum class OpenCodeStepRailStatus { Pending, Running, Completed, Failed }

/**
 * One row of an [OpenCodeStepRail], already folded by the caller. [id] is
 * the stable key the row is composed under and the value tap callbacks get.
 */
sealed class OpenCodeStepRailItem {
    abstract val id: String

    /**
     * A tool or thought step. [isBranch] indents a running step that runs in
     * parallel with the one above it. [interrupted] renders a failure the user
     * caused (stopped, declined) in a neutral tone.
     */
    data class Step(
        override val id: String,
        val title: String,
        val detail: String? = null,
        val status: OpenCodeStepRailStatus,
        val additions: Int? = null,
        val deletions: Int? = null,
        val startedAtMs: Long? = null,
        val endedAtMs: Long? = null,
        val isBranch: Boolean = false,
        val interrupted: Boolean = false,
        val tappable: Boolean = true,
    ) : OpenCodeStepRailItem()

    /** A child session the agent started, as a nested card on the rail. */
    data class ChildSession(
        override val id: String,
        val agent: String,
        val description: String? = null,
        val status: OpenCodeStepRailStatus,
        val startedAtMs: Long? = null,
        val endedAtMs: Long? = null,
        val detail: String? = null,
        val tappable: Boolean = true,
    ) : OpenCodeStepRailItem()

    /** Leading `N earlier steps` row; [status] is the worst folded status. */
    data class Fold(
        val count: Int,
        val status: OpenCodeStepRailStatus = OpenCodeStepRailStatus.Completed,
        override val id: String = "fold",
    ) : OpenCodeStepRailItem()

    /** Trailing `Thinking…` row while the agent works with nothing running. */
    data object Thinking : OpenCodeStepRailItem() {
        override val id: String = "thinking"
    }
}

private val StepRailDotSize = 8.dp
private val StepRailLineWidth = 1.5.dp
private val StepRailTextInset = 16.dp
private val StepRailBranchInset = 30.dp
private val StepRailBranchDotX = 18.dp
private val StepRailRowMinHeight = 36.dp
private val StepRailRowVerticalPadding = 4.dp
private val StepRailContentSpacing = 8.dp
private val StepRailChildCardPaddingHorizontal = 10.dp
private val StepRailChildCardPaddingVertical = 6.dp
private val StepRailChevronSize = 14.dp
private val StepRailDetailMaxWidth = 140.dp

/** How far a tappable row's highlight extends past its text, clear of the rail line and dot. */
private val StepRailPressBleed = 8.dp
private const val STEP_RAIL_CHILD_TINT_ALPHA = 0.08f
private const val STEP_RAIL_CHILD_BORDER_ALPHA = 0.4f

/**
 * Step rail timeline (#779): one short line per step on a vertical rail with
 * a status dot. Running steps pulse and tick an elapsed `m:ss`; a running
 * step after another running step is an indented branch; a child session is
 * a nested card. [steps] arrive folded; [thinking] appends a `Thinking…` row.
 * [isLive] lets running dots pulse (static with system animations off).
 */
@Composable
fun OpenCodeStepRail(
    steps: List<OpenCodeStepRailItem>,
    isLive: Boolean,
    thinking: Boolean,
    onStepTap: (stepId: String) -> Unit,
    onFoldTap: () -> Unit,
    onChildSessionTap: (stepId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows =
        remember(steps, thinking) {
            if (thinking && steps.none { it is OpenCodeStepRailItem.Thinking }) steps + OpenCodeStepRailItem.Thinking else steps
        }
    val context = LocalContext.current
    val animationsOn =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }
    val pulses = isLive && animationsOn
    Column(modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp)) {
        rows.forEachIndexed { index, item ->
            androidx.compose.runtime.key(item.id) {
                val railModifier =
                    Modifier.railLine(
                        isFirst = index == 0,
                        isLast = index == rows.lastIndex,
                        branch = (item as? OpenCodeStepRailItem.Step)?.isBranch == true,
                    )
                when (item) {
                    is OpenCodeStepRailItem.Step ->
                        StepRow(
                            item = item,
                            pulses = pulses,
                            onTap = { onStepTap(item.id) },
                            // A step that joins running fades in, so its arrival reads as one change.
                            modifier = railModifier.fadeInOnEnter(animationsOn && item.status.isRunningTone()),
                        )
                    is OpenCodeStepRailItem.ChildSession ->
                        ChildSessionRow(item = item, pulses = pulses, onTap = { onChildSessionTap(item.id) }, modifier = railModifier)
                    is OpenCodeStepRailItem.Fold -> FoldRow(item = item, onTap = onFoldTap, modifier = railModifier)
                    OpenCodeStepRailItem.Thinking -> ThinkingRow(pulses = animationsOn, modifier = railModifier)
                }
            }
        }
    }
}

/** Fades the row's content in over 200 ms on its first composition when [enabled]; the rail line stays. */
@Composable
private fun Modifier.fadeInOnEnter(enabled: Boolean): Modifier {
    val alpha = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(STEP_RAIL_ENTER_FADE_MS)) }
    return graphicsLayer { this.alpha = alpha.value }
}

private const val STEP_RAIL_ENTER_FADE_MS = 200

private fun OpenCodeStepRailStatus.isRunningTone(): Boolean =
    this == OpenCodeStepRailStatus.Running || this == OpenCodeStepRailStatus.Pending

/** The rail segment behind one row; it starts and ends at the dot for the first and last row. */
@Composable
private fun Modifier.railLine(
    isFirst: Boolean,
    isLast: Boolean,
    branch: Boolean,
): Modifier {
    val color = openCodeColor("border-weak-base")
    return drawBehind {
        val x = StepRailDotSize.toPx() / 2
        val stroke = StepRailLineWidth.toPx()
        val center = size.height / 2
        val top = if (isFirst) center else 0f
        val bottom = if (isLast) center else size.height
        if (bottom > top) drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = stroke)
        if (branch) drawLine(color, Offset(x, center), Offset(StepRailBranchDotX.toPx(), center), strokeWidth = stroke)
    }
}

@Composable
private fun StepRowContainer(
    label: String,
    onTap: (() -> Unit)?,
    modifier: Modifier,
    textInset: Dp = StepRailTextInset,
    dot: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = StepRailRowMinHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    if (onTap != null) role = Role.Button
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        dot()
        // The press highlight is a rounded, bounded ripple that starts just before the
        // text and bleeds past it, so it never covers the rail line and dot.
        Box(
            modifier =
                Modifier
                    .padding(start = textInset)
                    .fillMaxWidth()
                    .heightIn(min = StepRailRowMinHeight)
                    .bleedHorizontally(StepRailPressBleed)
                    .clip(RoundedCornerShape(OpenCodeRadius.small))
                    .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
                    .padding(horizontal = StepRailPressBleed),
            contentAlignment = Alignment.CenterStart,
        ) {
            content()
        }
    }
}

@Composable
private fun StepRow(
    item: OpenCodeStepRailItem.Step,
    pulses: Boolean,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val running = item.status.isInFlight
    val elapsed = rememberStepRailElapsed(item.startedAtMs, item.endedAtMs, ticking = running)?.takeIf { running }
    StepRowContainer(
        label = stepRailAccessibilityLabel(item.title, item.status, item.interrupted, elapsed),
        onTap = onTap.takeIf { item.tappable },
        modifier = modifier,
        textInset = if (item.isBranch) StepRailBranchInset else StepRailTextInset,
        dot = {
            StepRailDot(
                status = item.status,
                interrupted = item.interrupted,
                pulses = pulses,
                modifier = Modifier.offset(x = if (item.isBranch) StepRailBranchDotX else 0.dp),
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = StepRailRowVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(StepRailContentSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (running) openCodeColor("text-strong") else openCodeColor("text-weak"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!item.detail.isNullOrEmpty()) {
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = openCodeColor("text-weaker"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = StepRailDetailMaxWidth),
                    )
                }
            }
            StepTrailing(item = item, elapsed = elapsed)
        }
    }
}

@Composable
private fun StepTrailing(
    item: OpenCodeStepRailItem.Step,
    elapsed: String?,
) {
    val mono = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    when {
        elapsed != null -> Text(text = elapsed, style = mono, color = openCodeColor("text-weaker"), maxLines = 1)
        item.status == OpenCodeStepRailStatus.Failed ->
            Text(
                text = if (item.interrupted) "stopped" else "failed",
                style = MaterialTheme.typography.labelSmall,
                color = if (item.interrupted) openCodeColor("text-weak") else openCodeColor("error"),
                maxLines = 1,
            )
        (item.additions ?: 0) > 0 || (item.deletions ?: 0) > 0 ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item.additions?.takeIf { it > 0 }?.let {
                    Text(text = "+$it", style = mono, color = openCodeColor("diff-add"), maxLines = 1)
                }
                item.deletions?.takeIf { it > 0 }?.let {
                    Text(text = "−$it", style = mono, color = openCodeColor("diff-delete"), maxLines = 1)
                }
            }
    }
}

@Composable
private fun ChildSessionRow(
    item: OpenCodeStepRailItem.ChildSession,
    pulses: Boolean,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val running = item.status.isInFlight
    val elapsed = rememberStepRailElapsed(item.startedAtMs, item.endedAtMs, ticking = running)
    val tint = openCodeColor("info")
    val shape = RoundedCornerShape(OpenCodeRadius.small)
    val secondLine = item.detail ?: item.description
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = StepRailRowMinHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        listOfNotNull(
                            stepRailAccessibilityLabel(item.agent, item.status, interrupted = false, elapsed = elapsed),
                            secondLine,
                        ).joinToString(", ")
                    if (item.tappable) role = Role.Button
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        StepRailDot(status = item.status, interrupted = false, pulses = pulses)
        Column(
            modifier =
                Modifier
                    .padding(start = StepRailTextInset, top = StepRailRowVerticalPadding, bottom = StepRailRowVerticalPadding)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tint.copy(alpha = STEP_RAIL_CHILD_TINT_ALPHA))
                    .border(BorderStroke(1.dp, tint.copy(alpha = STEP_RAIL_CHILD_BORDER_ALPHA)), shape)
                    .then(if (item.tappable) Modifier.clickable(onClick = onTap) else Modifier)
                    .padding(horizontal = StepRailChildCardPaddingHorizontal, vertical = StepRailChildCardPaddingVertical),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(StepRailContentSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.agent.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (item.status == OpenCodeStepRailStatus.Failed) openCodeColor("error") else tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (elapsed != null) {
                    Text(
                        text = elapsed,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = openCodeColor("text-weaker"),
                        maxLines = 1,
                    )
                }
                if (item.tappable) {
                    Icon(
                        imageVector = OpenCodeIcons.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(StepRailChevronSize),
                        tint = openCodeColor("icon-base"),
                    )
                }
            }
            if (!secondLine.isNullOrEmpty()) {
                Text(
                    text = secondLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weak"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FoldRow(
    item: OpenCodeStepRailItem.Fold,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val label = if (item.count == 1) "1 earlier step" else "${item.count} earlier steps"
    StepRowContainer(
        label = "$label, expands",
        onTap = onTap,
        modifier = modifier,
        dot = {
            Box(
                modifier =
                    Modifier
                        .size(StepRailDotSize)
                        .background(
                            if (item.status == OpenCodeStepRailStatus.Failed) {
                                openCodeColor("error")
                            } else {
                                openCodeColor("text-weaker")
                            },
                            CircleShape,
                        ),
            )
        },
    ) {
        Row(
            modifier = Modifier.padding(vertical = StepRailRowVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = openCodeColor("text-weaker"), maxLines = 1)
            Icon(
                imageVector = OpenCodeIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(StepRailChevronSize),
                tint = openCodeColor("text-weaker"),
            )
        }
    }
}

@Composable
private fun ThinkingRow(
    pulses: Boolean,
    modifier: Modifier,
) {
    StepRowContainer(
        label = "Thinking",
        onTap = null,
        modifier = modifier,
        dot = { OpenCodePulsingDot(color = openCodeColor("warning"), size = StepRailDotSize, animated = pulses) },
    ) {
        Text(
            text = "Thinking…",
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = openCodeColor("text-weak"),
            maxLines = 1,
            modifier = Modifier.padding(vertical = StepRailRowVerticalPadding),
        )
    }
}

@Composable
private fun StepRailDot(
    status: OpenCodeStepRailStatus,
    interrupted: Boolean,
    pulses: Boolean,
    modifier: Modifier = Modifier,
) {
    when (status) {
        OpenCodeStepRailStatus.Running ->
            OpenCodePulsingDot(modifier = modifier, color = openCodeColor("warning"), size = StepRailDotSize, animated = pulses)
        OpenCodeStepRailStatus.Pending -> {
            val color = openCodeColor("text-weaker")
            Canvas(modifier = modifier.size(StepRailDotSize)) {
                val stroke = 1.dp.toPx()
                drawCircle(
                    color = color,
                    radius = (size.minDimension - stroke) / 2,
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx()))),
                )
            }
        }
        OpenCodeStepRailStatus.Completed, OpenCodeStepRailStatus.Failed -> {
            val color: Color =
                when {
                    status == OpenCodeStepRailStatus.Completed -> openCodeColor("success")
                    interrupted -> openCodeColor("text-weak")
                    else -> openCodeColor("error")
                }
            Box(modifier = modifier.size(StepRailDotSize).background(color, CircleShape))
        }
    }
}

private val OpenCodeStepRailStatus.isInFlight: Boolean
    get() = this == OpenCodeStepRailStatus.Pending || this == OpenCodeStepRailStatus.Running

/** `m:ss` that ticks each second while [ticking]; a settled span when both ends are known. */
@Composable
private fun rememberStepRailElapsed(
    startedAtMs: Long?,
    endedAtMs: Long?,
    ticking: Boolean,
): String? {
    val now by produceState(initialValue = System.currentTimeMillis(), startedAtMs, ticking) {
        if (startedAtMs == null || !ticking) return@produceState
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return when {
        startedAtMs == null -> null
        ticking -> SessionChatChildSessionElapsed.format(startedAtMs, now)
        endedAtMs != null -> SessionChatChildSessionElapsed.format(startedAtMs, endedAtMs)
        else -> null
    }
}

internal fun stepRailAccessibilityLabel(
    title: String,
    status: OpenCodeStepRailStatus,
    interrupted: Boolean,
    elapsed: String?,
): String {
    val state =
        when {
            status == OpenCodeStepRailStatus.Failed && interrupted -> "stopped"
            status == OpenCodeStepRailStatus.Failed -> "failed"
            status == OpenCodeStepRailStatus.Completed -> "completed"
            status == OpenCodeStepRailStatus.Pending -> "pending"
            else -> "running"
        }
    return listOfNotNull(title, state, elapsed).joinToString(", ")
}

/** Gallery and preview samples: the four states the contract lists. */
object OpenCodeStepRailSamples {
    private const val NOW_OFFSET_MS = 12_000L

    fun liveOneRunning(nowMs: Long): List<OpenCodeStepRailItem> =
        listOf(
            OpenCodeStepRailItem.Step(id = "a", title = "SessionChatScreen.kt", status = OpenCodeStepRailStatus.Completed),
            OpenCodeStepRailItem.Step(id = "b", title = "branchPill", detail = "4 matches", status = OpenCodeStepRailStatus.Completed),
            OpenCodeStepRailItem.Step(
                id = "c",
                title = "Running ./gradlew :app:testDebugUnitTest",
                status = OpenCodeStepRailStatus.Running,
                startedAtMs = nowMs - NOW_OFFSET_MS,
            ),
        )

    fun liveParallelWithChild(nowMs: Long): List<OpenCodeStepRailItem> =
        listOf(
            OpenCodeStepRailItem.Step(id = "a", title = "Thought · 3.2s", status = OpenCodeStepRailStatus.Completed),
            OpenCodeStepRailItem.Step(
                id = "b",
                title = "Reading OpenCodeToolChip.kt",
                status = OpenCodeStepRailStatus.Running,
                startedAtMs = nowMs - 3_000,
            ),
            OpenCodeStepRailItem.Step(
                id = "c",
                title = "Searching statusDot",
                status = OpenCodeStepRailStatus.Running,
                startedAtMs = nowMs - 2_000,
                isBranch = true,
            ),
            OpenCodeStepRailItem.ChildSession(
                id = "d",
                agent = "explore",
                description = "Find where the branch pill is rendered",
                status = OpenCodeStepRailStatus.Running,
                startedAtMs = nowMs - 64_000,
                detail = "Find where the branch pill is rendered · 6 tools so far",
            ),
        )

    val foldedWithFailure: List<OpenCodeStepRailItem> =
        listOf(
            OpenCodeStepRailItem.Fold(count = 6, status = OpenCodeStepRailStatus.Failed),
            OpenCodeStepRailItem.Step(
                id = "a",
                title = "OpenCodeStepRail.kt",
                status = OpenCodeStepRailStatus.Completed,
                additions = 18,
                deletions = 4,
            ),
            OpenCodeStepRailItem.Step(
                id = "b",
                title = "Ran ./gradlew :app:assembleDebug",
                detail = "Exited with code 1",
                status = OpenCodeStepRailStatus.Failed,
            ),
            OpenCodeStepRailItem.Step(id = "c", title = "build.gradle.kts", status = OpenCodeStepRailStatus.Completed, additions = 2),
        )

    val finished: List<OpenCodeStepRailItem> =
        listOf(
            OpenCodeStepRailItem.Step(id = "a", title = "README.md", status = OpenCodeStepRailStatus.Completed),
            OpenCodeStepRailItem.Step(id = "b", title = "Ran npm test", detail = "12 passed", status = OpenCodeStepRailStatus.Completed),
            OpenCodeStepRailItem.ChildSession(
                id = "c",
                agent = "review",
                description = "Standards review of the diff",
                status = OpenCodeStepRailStatus.Completed,
                startedAtMs = 0L,
                endedAtMs = 84_000L,
            ),
            OpenCodeStepRailItem.Step(id = "d", title = "Thought · 1.4s", status = OpenCodeStepRailStatus.Completed),
        )
}

@Preview(showBackground = true, name = "Step Rail Light")
@Composable
private fun OpenCodeStepRailPreviewLight() {
    OpenCodeTheme(darkTheme = false) { StepRailPreviewColumn() }
}

@Preview(showBackground = true, name = "Step Rail Dark")
@Composable
private fun OpenCodeStepRailPreviewDark() {
    OpenCodeTheme(darkTheme = true) { StepRailPreviewColumn() }
}

@Composable
private fun StepRailPreviewColumn() {
    val now = System.currentTimeMillis()
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OpenCodeStepRail(OpenCodeStepRailSamples.liveOneRunning(now), isLive = true, thinking = false, {}, {}, {})
        OpenCodeStepRail(OpenCodeStepRailSamples.liveParallelWithChild(now), isLive = true, thinking = false, {}, {}, {})
        OpenCodeStepRail(OpenCodeStepRailSamples.foldedWithFailure, isLive = true, thinking = true, {}, {}, {})
        OpenCodeStepRail(OpenCodeStepRailSamples.finished, isLive = false, thinking = false, {}, {}, {})
    }
}
