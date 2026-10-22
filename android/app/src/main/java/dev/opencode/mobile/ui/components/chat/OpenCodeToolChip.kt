package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val ToolChipCornerRadius = OpenCodeRadius.small
private val ToolChipPaddingStart = 8.dp
private val ToolChipPaddingEnd = 10.dp
private val ToolChipPaddingVertical = 6.dp
private val ToolChipIconSize = 12.dp
private val ToolChipContentSpacing = 6.dp
private val ToolChipBriefMaxWidth = 160.dp
private val ToolChipMinHeight = 36.dp
private val ToolChipAgentTileSize = 18.dp
private val ToolChipAgentTileCornerRadius = 5.dp
private const val TOOL_CHIP_AGENT_TILE_ALPHA = 0.14f
private val ToolChipMinTouchTarget = 48.dp
private const val TOOL_CHIP_RUNNING_REVEAL_DELAY_MS = 250L
private const val TOOL_CHIP_RUNNING_CYCLE_MS = 1300
private const val TOOL_CHIP_SHIMMER_HALF_WIDTH = 0.3f
private const val TOOL_CHIP_RUNNING_ICON_MIN_ALPHA = 0.35f

/** Variant-first content of a [OpenCodeToolChip]; colours and radius are not exposed. */
sealed class OpenCodeToolChipVariant {
    /** A file the agent read or changed; diff counts mark it as a change. */
    data class File(
        val label: String,
        val additions: Int? = null,
        val deletions: Int? = null,
    ) : OpenCodeToolChipVariant()

    data class Command(
        val label: String,
    ) : OpenCodeToolChipVariant()

    data class Agent(
        val name: String,
        val brief: String? = null,
    ) : OpenCodeToolChipVariant()

    data class Skill(
        val name: String,
    ) : OpenCodeToolChipVariant()

    /**
     * One chip for a paragraph's explore tools: `Explored · 4 reads · 2 greps`
     * (or `Exploring · …` while running). Completed shows no check mark.
     */
    data class Explore(
        val label: String,
    ) : OpenCodeToolChipVariant()

    /** `+N more` expander; [OpenCodeToolChipStatus] is ignored. */
    data class More(
        val count: Int,
    ) : OpenCodeToolChipVariant()
}

enum class OpenCodeToolChipStatus {
    Pending,
    Running,
    Completed,
    Error,

    /** Stopped by the user (Stop, Deny, Dismiss): neutral, not a failure. */
    Interrupted,
}

/**
 * Compact tool evidence under a chip-timeline paragraph. Tappable when [onClick]
 * is set; the tap target reaches 48dp without growing the visible chip.
 */
@Composable
fun OpenCodeToolChip(
    variant: OpenCodeToolChipVariant,
    status: OpenCodeToolChipStatus = OpenCodeToolChipStatus.Completed,
    trailingText: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (variant) {
        is OpenCodeToolChipVariant.File -> {
            require(variant.label.isNotEmpty()) { "Tool Chip file label must not be empty" }
            require(variant.additions == null || variant.additions >= 0) { "Tool Chip additions must be nonnegative" }
            require(variant.deletions == null || variant.deletions >= 0) { "Tool Chip deletions must be nonnegative" }
        }
        is OpenCodeToolChipVariant.Command -> require(variant.label.isNotEmpty()) { "Tool Chip command label must not be empty" }
        is OpenCodeToolChipVariant.Agent -> require(variant.name.isNotEmpty()) { "Tool Chip agent name must not be empty" }
        is OpenCodeToolChipVariant.Skill -> require(variant.name.isNotEmpty()) { "Tool Chip skill name must not be empty" }
        is OpenCodeToolChipVariant.Explore -> require(variant.label.isNotEmpty()) { "Tool Chip explore label must not be empty" }
        is OpenCodeToolChipVariant.More -> require(variant.count > 0) { "Tool Chip more count must be positive" }
    }
    val effectiveStatus = if (variant is OpenCodeToolChipVariant.More) OpenCodeToolChipStatus.Completed else status
    val isRunning = effectiveStatus == OpenCodeToolChipStatus.Running
    // Until the chip has been running for the reveal delay it looks idle, so
    // commands that finish quickly never flash. Static previews skip the delay.
    val inspectionMode = LocalInspectionMode.current
    var runningRevealed by remember { mutableStateOf(inspectionMode) }
    LaunchedEffect(isRunning) {
        if (isRunning && !inspectionMode) {
            delay(TOOL_CHIP_RUNNING_REVEAL_DELAY_MS)
            runningRevealed = true
        } else {
            runningRevealed = inspectionMode
        }
    }
    val showsRunningTreatment = isRunning && runningRevealed
    val runningPhase = if (showsRunningTreatment) rememberRunningPhase() else null
    val colors = toolChipColors(variant, effectiveStatus, showsRunningTreatment)
    val shape = RoundedCornerShape(ToolChipCornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val accessibilityLabel = toolChipAccessibilityLabel(variant, status, trailingText)

    val chipModifier =
        modifier
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                if (onClick != null) role = Role.Button
            }.then(
                if (onClick != null) {
                    Modifier
                        .touchTargetShell()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        ).touchTargetCore(ToolChipMinTouchTarget)
                } else {
                    Modifier
                },
            )

    Row(
        modifier =
            chipModifier
                // Never shorter than the other pills (branch, review changes, action pill).
                .defaultMinSize(minHeight = ToolChipMinHeight)
                .clip(shape)
                .background(colors.background, shape)
                .then(
                    if (effectiveStatus == OpenCodeToolChipStatus.Pending) {
                        Modifier.dashedBorder(colors.border, ToolChipCornerRadius)
                    } else {
                        Modifier.border(1.dp, colors.border, shape)
                    },
                ).then(
                    if (onClick != null) Modifier.indication(interactionSource, ripple()) else Modifier,
                ).padding(
                    start = if (variant.leadingIcon() != null) ToolChipPaddingStart else ToolChipPaddingEnd,
                    end = ToolChipPaddingEnd,
                    top = ToolChipPaddingVertical,
                    bottom = ToolChipPaddingVertical,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolChipContentSpacing),
    ) {
        variant.leadingIcon()?.let { icon ->
            if (variant is OpenCodeToolChipVariant.Agent) {
                // Agents get a tinted tile so the chip reads as "someone else
                // did this" without tinting the whole chip.
                Box(
                    modifier =
                        Modifier
                            .size(ToolChipAgentTileSize)
                            .background(
                                openCodeColor("info").copy(alpha = TOOL_CHIP_AGENT_TILE_ALPHA),
                                RoundedCornerShape(ToolChipAgentTileCornerRadius),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    ToolChipLeadingIcon(icon, colors.icon, runningPhase)
                }
            } else {
                ToolChipLeadingIcon(icon, colors.icon, runningPhase)
            }
        }

        val labelStyle =
            when (variant) {
                is OpenCodeToolChipVariant.File,
                is OpenCodeToolChipVariant.Command,
                is OpenCodeToolChipVariant.Skill,
                -> MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 16.sp)
                is OpenCodeToolChipVariant.Agent,
                -> MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                is OpenCodeToolChipVariant.More,
                is OpenCodeToolChipVariant.Explore,
                -> MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, lineHeight = 16.sp)
            }
        Text(
            text = variant.primaryText(),
            style =
                if (runningPhase != null) {
                    labelStyle.copy(brush = runningShimmerBrush(runningPhase.value))
                } else {
                    labelStyle.copy(color = colors.label)
                },
            maxLines = 1,
            overflow =
                if (variant is OpenCodeToolChipVariant.File) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            // Shrinks before the other slots, so a long label truncates instead of
            // pushing the status glyph out; the chip may take the whole row.
            modifier = Modifier.weight(1f, fill = false),
        )

        if (variant is OpenCodeToolChipVariant.Agent && !variant.brief.isNullOrEmpty()) {
            Text(
                text = variant.brief,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = openCodeColor("text-base"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = ToolChipBriefMaxWidth),
            )
        }

        if (variant is OpenCodeToolChipVariant.File) {
            val countStyle =
                MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
            variant.additions?.takeIf { it > 0 }?.let { additions ->
                Text(text = "+$additions", style = countStyle, color = openCodeColor("diff-add"), maxLines = 1)
            }
            variant.deletions?.takeIf { it > 0 }?.let { deletions ->
                Text(text = "−$deletions", style = countStyle, color = openCodeColor("diff-delete"), maxLines = 1)
            }
        }

        if (!trailingText.isNullOrEmpty()) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = openCodeColor("text-base"),
                maxLines = 1,
            )
        }

        if (variant !is OpenCodeToolChipVariant.More) {
            when (effectiveStatus) {
                OpenCodeToolChipStatus.Error ->
                    Icon(
                        imageVector = OpenCodeIcons.Close,
                        contentDescription = null,
                        modifier = Modifier.size(ToolChipIconSize),
                        tint = openCodeColor("error"),
                    )
                OpenCodeToolChipStatus.Interrupted ->
                    Icon(
                        imageVector = OpenCodeIcons.StopCircle,
                        contentDescription = null,
                        modifier = Modifier.size(ToolChipIconSize),
                        tint = openCodeColor("text-weak"),
                    )
                OpenCodeToolChipStatus.Completed ->
                    if (variant is OpenCodeToolChipVariant.Command || variant is OpenCodeToolChipVariant.Agent) {
                        Icon(
                            imageVector = OpenCodeIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(ToolChipIconSize),
                            tint = openCodeColor("success"),
                        )
                    }
                OpenCodeToolChipStatus.Running, OpenCodeToolChipStatus.Pending -> Unit
            }
        }
    }
}

@Composable
private fun ToolChipLeadingIcon(
    icon: ImageVector,
    tint: Color,
    runningPhase: State<Float>?,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier =
            Modifier
                .size(ToolChipIconSize)
                .then(
                    // Read in the draw phase so the breathing never recomposes.
                    if (runningPhase != null) Modifier.graphicsLayer { alpha = runningIconAlpha(runningPhase.value) } else Modifier,
                ),
        tint = tint,
    )
}

/**
 * 0…1 through one running cycle, derived from the shared frame clock rather
 * than a per-chip animation, so chips that start running at different times
 * still sweep in step (iOS keys off wall-clock time the same way).
 */
@Composable
internal fun rememberRunningPhase(): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMillis ->
                phase.floatValue = (frameMillis % TOOL_CHIP_RUNNING_CYCLE_MS).toFloat() / TOOL_CHIP_RUNNING_CYCLE_MS
            }
        }
    }
    return phase
}

/**
 * A warning-coloured band, `2 × TOOL_CHIP_SHIMMER_HALF_WIDTH` label widths wide,
 * sweeping left to right across `text-base` text.
 */
@Composable
internal fun runningShimmerBrush(phase: Float): ShaderBrush {
    val base = openCodeColor("text-base")
    val highlight = openCodeColor("warning")
    val half = TOOL_CHIP_SHIMMER_HALF_WIDTH
    val center = -half + phase * (1 + 2 * half)
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader =
            LinearGradientShader(
                from = Offset(size.width * (center - half), 0f),
                to = Offset(size.width * (center + half), 0f),
                colors = listOf(base, highlight, base),
            )
    }
}

/** Full opacity at the start of the cycle, dimmest halfway through. */
private fun runningIconAlpha(phase: Float): Float {
    val mid = (1 + TOOL_CHIP_RUNNING_ICON_MIN_ALPHA) / 2
    return mid + (1 - mid) * cos(2 * PI * phase).toFloat()
}

private data class ToolChipColors(
    val background: Color,
    val border: Color,
    val icon: Color,
    val label: Color,
)

@Composable
private fun toolChipColors(
    variant: OpenCodeToolChipVariant,
    status: OpenCodeToolChipStatus,
    showsRunningTreatment: Boolean,
): ToolChipColors {
    val base =
        ToolChipColors(
            background = openCodeColor("surface-raised-base"),
            border = openCodeColor("border-weak-base"),
            icon = openCodeColor("text-weak"),
            label = openCodeColor("text-strong"),
        )
    val byVariant =
        when (variant) {
            is OpenCodeToolChipVariant.Agent -> {
                val info = openCodeColor("info")
                base.copy(border = info.copy(alpha = 0.4f), icon = info)
            }
            is OpenCodeToolChipVariant.Skill ->
                base.copy(
                    background = openCodeColor("surface-interactive-weak"),
                    icon = openCodeColor("text-interactive-base"),
                    label = openCodeColor("text-interactive-base"),
                )
            is OpenCodeToolChipVariant.More -> base.copy(label = openCodeColor("text-base"))
            is OpenCodeToolChipVariant.File, is OpenCodeToolChipVariant.Command, is OpenCodeToolChipVariant.Explore -> base
        }
    return when (status) {
        OpenCodeToolChipStatus.Error -> {
            val error = openCodeColor("error")
            byVariant.copy(
                background = openCodeColor("surface-critical-base"),
                border = error.copy(alpha = 0.4f),
                icon = error,
                label = if (variant is OpenCodeToolChipVariant.Agent) error else byVariant.label,
            )
        }
        // Fill and outline stay put; the label shimmer and icon carry "running".
        OpenCodeToolChipStatus.Running ->
            if (showsRunningTreatment) byVariant.copy(icon = openCodeColor("warning")) else byVariant
        OpenCodeToolChipStatus.Pending -> byVariant.copy(label = openCodeColor("text-base"))
        OpenCodeToolChipStatus.Interrupted -> byVariant.copy(label = openCodeColor("text-base"))
        OpenCodeToolChipStatus.Completed -> byVariant
    }
}

private fun OpenCodeToolChipVariant.leadingIcon(): ImageVector? =
    when (this) {
        is OpenCodeToolChipVariant.File ->
            if (additions != null || deletions != null) OpenCodeIcons.Edit else OpenCodeIcons.Description
        is OpenCodeToolChipVariant.Command -> OpenCodeIcons.Terminal
        is OpenCodeToolChipVariant.Agent -> OpenCodeIcons.CallSplit
        is OpenCodeToolChipVariant.Skill -> OpenCodeIcons.List
        is OpenCodeToolChipVariant.Explore -> OpenCodeIcons.Search
        is OpenCodeToolChipVariant.More -> null
    }

private fun OpenCodeToolChipVariant.primaryText(): String =
    when (this) {
        is OpenCodeToolChipVariant.File -> label
        is OpenCodeToolChipVariant.Command -> label
        is OpenCodeToolChipVariant.Agent -> displayAgentName(name)
        is OpenCodeToolChipVariant.Skill -> name
        is OpenCodeToolChipVariant.Explore -> label
        is OpenCodeToolChipVariant.More -> "+$count more"
    }

/** "<kind> <label>[, +a −d][, <status>][, <trailing>]" */
internal fun toolChipAccessibilityLabel(
    variant: OpenCodeToolChipVariant,
    status: OpenCodeToolChipStatus,
    trailingText: String?,
): String {
    val parts = mutableListOf<String>()
    when (variant) {
        is OpenCodeToolChipVariant.File -> {
            parts += "file ${variant.label}"
            val counts =
                listOfNotNull(
                    variant.additions?.takeIf { it > 0 }?.let { "+$it" },
                    variant.deletions?.takeIf { it > 0 }?.let { "−$it" },
                )
            if (counts.isNotEmpty()) parts += counts.joinToString(" ")
        }
        is OpenCodeToolChipVariant.Command -> parts += "command ${variant.label}"
        is OpenCodeToolChipVariant.Agent ->
            parts += listOfNotNull("agent ${variant.name}", variant.brief?.takeIf { it.isNotEmpty() }).joinToString(" ")
        is OpenCodeToolChipVariant.Skill -> parts += "skill ${variant.name}"
        is OpenCodeToolChipVariant.Explore -> parts += variant.label
        is OpenCodeToolChipVariant.More -> parts += "${variant.count} more tools"
    }
    if (variant !is OpenCodeToolChipVariant.More) parts += status.name.lowercase()
    if (!trailingText.isNullOrEmpty()) parts += trailingText
    return parts.joinToString(", ")
}

private val ToolChipContentWidthLine = VerticalAlignmentLine(::maxOf)
private val ToolChipContentHeightLine = HorizontalAlignmentLine(::maxOf)

/**
 * Inner half of the touch-target expansion: lays the chip content out inside a
 * box at least [minSize] on each axis so the enclosing clickable's pointer
 * bounds reach the minimum target, and publishes the content size as
 * alignment lines for [touchTargetShell].
 */
private fun Modifier.touchTargetCore(minSize: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val minPx = minSize.roundToPx()
        val width = maxOf(placeable.width, minPx)
        val height = maxOf(placeable.height, minPx)
        layout(
            width = width,
            height = height,
            alignmentLines =
                mapOf(
                    ToolChipContentWidthLine to placeable.width,
                    ToolChipContentHeightLine to placeable.height,
                ),
        ) {
            placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }

/**
 * Outer half: reports the content size (from [touchTargetCore]'s alignment
 * lines) upward so the chip keeps its visual size and flow spacing, placing
 * the larger clickable box centred on it.
 */
private fun Modifier.touchTargetShell(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val width =
            placeable[ToolChipContentWidthLine].takeIf { it != AlignmentLine.Unspecified } ?: placeable.width
        val height =
            placeable[ToolChipContentHeightLine].takeIf { it != AlignmentLine.Unspecified } ?: placeable.height
        layout(width, height) {
            placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }

private fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp,
): Modifier =
    drawBehind {
        val strokeWidth = 1.dp.toPx()
        val inset = strokeWidth / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style =
                Stroke(
                    width = strokeWidth,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
        )
    }

enum class ToolChipPreviewCase(
    val variant: OpenCodeToolChipVariant,
    val status: OpenCodeToolChipStatus,
    val trailingText: String? = null,
) {
    ReadFile(OpenCodeToolChipVariant.File("SessionChatView.swift"), OpenCodeToolChipStatus.Completed),
    EditedFile(OpenCodeToolChipVariant.File("SessionChatTimeline.kt", 12, 3), OpenCodeToolChipStatus.Completed),
    RunningCommand(OpenCodeToolChipVariant.Command("./gradlew :app:testDebugUnitTest"), OpenCodeToolChipStatus.Running),
    CompletedCommand(OpenCodeToolChipVariant.Command("xcodebuild test"), OpenCodeToolChipStatus.Completed, "42s"),
    FailedCommand(OpenCodeToolChipVariant.Command("npm test"), OpenCodeToolChipStatus.Error),
    StoppedCommand(OpenCodeToolChipVariant.Command("./deploy.sh"), OpenCodeToolChipStatus.Interrupted),
    PendingFile(OpenCodeToolChipVariant.File("Navigation.kt"), OpenCodeToolChipStatus.Pending),
    Agent(OpenCodeToolChipVariant.Agent("Explore", "Find where the branch pill is rendered"), OpenCodeToolChipStatus.Completed, "1m 4s"),
    FailedAgent(OpenCodeToolChipVariant.Agent("Review", "Standards review"), OpenCodeToolChipStatus.Error),
    Skill(OpenCodeToolChipVariant.Skill("/feature"), OpenCodeToolChipStatus.Completed),
    Explore(OpenCodeToolChipVariant.Explore("Explored \u00b7 4 reads \u00b7 2 greps"), OpenCodeToolChipStatus.Completed),
    Exploring(OpenCodeToolChipVariant.Explore("Exploring \u00b7 3 reads"), OpenCodeToolChipStatus.Running),
    More(OpenCodeToolChipVariant.More(3), OpenCodeToolChipStatus.Completed),
}

@Preview(showBackground = true, name = "Tool Chip Light")
@Composable
private fun OpenCodeToolChipPreviewLight() {
    OpenCodeTheme(darkTheme = false) {
        ToolChipPreviewColumn()
    }
}

@Preview(showBackground = true, name = "Tool Chip Dark")
@Composable
private fun OpenCodeToolChipPreviewDark() {
    OpenCodeTheme(darkTheme = true) {
        ToolChipPreviewColumn()
    }
}

@Composable
private fun ToolChipPreviewColumn() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolChipPreviewCase.entries.forEach { case ->
            OpenCodeToolChip(
                variant = case.variant,
                status = case.status,
                trailingText = case.trailingText,
                onClick = {},
            )
        }
    }
}

/** Server agent names are lowercase identifiers (`explore`, `build`); the chip shows them as a name. */
internal fun displayAgentName(name: String): String = name.replaceFirstChar { it.uppercase() }
