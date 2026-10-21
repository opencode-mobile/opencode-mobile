package dev.opencode.mobile.ui.components

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionElapsed
import kotlinx.coroutines.delay

/**
 * Whether an [OpenCodeActivityCard] is the live card of a working turn
 * ([Live], timed from [Live.elapsedStartMs]) or a finished segment
 * ([Settled], [Settled.stepCount] steps in [Settled.worstStatus] tone).
 */
sealed interface OpenCodeActivityCardState {
    data class Live(
        val elapsedStartMs: Long?,
    ) : OpenCodeActivityCardState

    data class Settled(
        val worstStatus: OpenCodeActivityStatus,
        val stepCount: Int,
    ) : OpenCodeActivityCardState
}

/** Leading glyph of a step, by what it did. */
enum class OpenCodeActivityIcon { Read, Search, Edit, Command, Fetch, Agent, Thought, Skill }

/** Status of one step; [Interrupted] is a stop the user caused and renders neutral. */
enum class OpenCodeActivityStatus {
    Pending,
    Running,
    Completed,
    Failed,
    Interrupted,
    ;

    val isInFlight: Boolean
        get() = this == Pending || this == Running
}

/** One running child session on a live [OpenCodeActivityCard]. [id] is what `onAgentTap` gets. */
data class OpenCodeActivityAgentRow(
    val id: String,
    val agentName: String,
    val action: String,
    val startedAtMs: Long?,
)

/** The latest running tool in a live card's footer band; [extraRunning] more run beside it. */
data class OpenCodeActivityCurrentRow(
    val icon: OpenCodeActivityIcon,
    val title: String,
    val extraRunning: Int,
    val startedAtMs: Long?,
)

private val ActivityCardRadius = OpenCodeRadius.small
private val ActivityCardPaddingHorizontal = 12.dp
private val ActivitySpacing = 8.dp
private val ActivityDotSize = 8.dp
private val ActivityIconSize = 16.dp
private val ActivityChevronSize = 16.dp
private val ActivityAgentTileSize = 20.dp

/** Agent rows are tap targets; keep them at least this tall. */
private val ActivityAgentRowMinHeight = 44.dp

/** Footer band and settled line are tap targets too; Material's 48dp minimum. */
private val ActivityTapRowMinHeight = 48.dp

/** How far an agent row's press surface extends beyond its content on each side. */
private val ActivityAgentRowBleed = 8.dp
private val ActivityAgentTileRadius = 6.dp
private const val ACTIVITY_AGENT_TILE_ALPHA = 0.08f
private const val ACTIVITY_AGENT_BORDER_ALPHA = 0.4f

/**
 * Activity card timeline (#779): one compact card per run of steps.
 *
 * Live, top to bottom: a shimmering `Working` header with the run's elapsed
 * time, the [summary] line (`6 steps · 3 reads · 1 agent`), an Agent section
 * with one row per running child session in [agents] (tap: [onAgentTap]), and
 * a raised footer band with the [current] running tool, or [idleText] when
 * none runs. The band opens the live sheet ([onOpen]).
 *
 * Settled it is one line: a dot in the worst status tone, `Used N` and
 * [summary] (the deduplicated tool names); a tap opens the sheet.
 *
 * Only the header shimmers; nothing spins or pulses. With animations off the
 * header is a static `Working`. Agent rows are keyed by id so they join and
 * leave in place.
 */
@Composable
fun OpenCodeActivityCard(
    state: OpenCodeActivityCardState,
    summary: String,
    agents: List<OpenCodeActivityAgentRow>,
    current: OpenCodeActivityCurrentRow?,
    idleText: String?,
    onAgentTap: (id: String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ActivityCardRadius)
    val cardModifier =
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(openCodeColor("surface-base"))
            .border(1.dp, openCodeColor("border-weak-base"), shape)
    when (state) {
        is OpenCodeActivityCardState.Settled ->
            SettledLine(state = state, names = summary, onTap = onOpen, modifier = cardModifier)
        is OpenCodeActivityCardState.Live -> {
            val animationsOn = rememberActivityAnimationsOn()
            val now by rememberActivityClock(ticking = true)
            Column(modifier = cardModifier) {
                Header(
                    elapsed = SessionChatChildSessionElapsed.format(state.elapsedStartMs, now),
                    shimmer = animationsOn,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weak"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = ActivityCardPaddingHorizontal, end = ActivityCardPaddingHorizontal, bottom = 8.dp),
                )
                if (agents.isNotEmpty()) {
                    AgentSection(agents = agents, nowMs = now, onAgentTap = onAgentTap)
                }
                FooterBand(current = current, idleText = idleText, nowMs = now, onTap = onOpen)
            }
        }
    }
}

/** Section label: `Agent`, or `N agents` past one. */
fun openCodeActivityAgentSectionLabel(count: Int): String = if (count == 1) "Agent" else "$count agents"

@Composable
private fun Header(
    elapsed: String?,
    shimmer: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull("Working", elapsed).joinToString(", ")
                }.padding(start = ActivityCardPaddingHorizontal, end = ActivityCardPaddingHorizontal, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeActivityWorkingLabel(text = "Working", shimmer = shimmer, style = MaterialTheme.typography.bodyMedium)
        Box(modifier = Modifier.weight(1f))
        if (elapsed != null) {
            Text(text = elapsed, style = activityMonoCaption(), color = openCodeColor("text-weaker"), maxLines = 1)
        }
    }
}

@Composable
private fun AgentSection(
    agents: List<OpenCodeActivityAgentRow>,
    nowMs: Long,
    onAgentTap: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ActivityCardPaddingHorizontal)) {
        ActivityHairline()
        OpenCodeActivitySectionLabel(
            text = openCodeActivityAgentSectionLabel(agents.size),
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
        agents.forEachIndexed { index, agent ->
            key(agent.id) {
                if (index > 0) ActivityHairline()
                AgentRow(agent = agent, nowMs = nowMs, onTap = { onAgentTap(agent.id) })
            }
        }
    }
}

@Composable
private fun AgentRow(
    agent: OpenCodeActivityAgentRow,
    nowMs: Long,
    onTap: () -> Unit,
) {
    val elapsed = SessionChatChildSessionElapsed.format(agent.startedAtMs, nowMs)
    val tint = openCodeColor("info")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // The press surface bleeds past the content on both sides so the rounded
                // ripple has breathing room around the tile and chevron, while the content
                // itself stays aligned with the header text.
                .bleedHorizontally(ActivityAgentRowBleed)
                .clip(RoundedCornerShape(OpenCodeRadius.small))
                .clickable(onClick = onTap)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(agent.agentName, agent.action, elapsed).joinToString(", ")
                    role = Role.Button
                }.heightIn(min = ActivityAgentRowMinHeight)
                .padding(horizontal = ActivityAgentRowBleed, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(ActivitySpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeActivityAgentTile(agentName = agent.agentName)
        Text(
            text = agent.agentName,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
        )
        Text(
            text = agent.action,
            style = MaterialTheme.typography.bodyMedium,
            color = openCodeColor("text-weak"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (elapsed != null) {
            Text(text = elapsed, style = activityMonoCaption(), color = openCodeColor("text-weaker"), maxLines = 1)
        }
        ActivityChevron()
    }
}

@Composable
private fun FooterBand(
    current: OpenCodeActivityCurrentRow?,
    idleText: String?,
    nowMs: Long,
    onTap: () -> Unit,
) {
    val elapsed = current?.let { SessionChatChildSessionElapsed.format(it.startedAtMs, nowMs) }
    val label =
        if (current != null) {
            listOfNotNull(
                current.title,
                current.extraRunning.takeIf { it > 0 }?.let { "$it more running" },
                elapsed,
            ).joinToString(", ")
        } else {
            idleText.orEmpty()
        }
    Row(
        modifier =
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .background(openCodeColor("surface-raised-base"))
                .clickable(onClick = onTap)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$label, opens activity"
                    role = Role.Button
                }.heightIn(min = ActivityTapRowMinHeight)
                .padding(start = ActivityCardPaddingHorizontal, end = ActivityCardPaddingHorizontal, top = 12.dp, bottom = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(ActivitySpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current != null) {
            Icon(
                imageVector = current.icon.vector(),
                contentDescription = null,
                modifier = Modifier.size(ActivityIconSize),
                tint = openCodeColor("icon-base"),
            )
            Text(
                text = current.title,
                style = MaterialTheme.typography.bodyMedium,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (current.extraRunning > 0) {
                Text(
                    text = "+${current.extraRunning}",
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weak"),
                    maxLines = 1,
                )
            }
            if (elapsed != null) {
                Text(text = elapsed, style = activityMonoCaption(), color = openCodeColor("text-weaker"), maxLines = 1)
            }
        } else {
            Text(
                text = idleText.orEmpty(),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = openCodeColor("text-weaker"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        ActivityChevron()
    }
}

@Composable
private fun SettledLine(
    state: OpenCodeActivityCardState.Settled,
    names: String,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val label = listOf("Used ${state.stepCount}", names).filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        modifier =
            modifier
                .clickable(onClick = onTap)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$label, ${state.worstStatus.spoken()}, opens activity"
                    role = Role.Button
                }.heightIn(min = ActivityTapRowMinHeight)
                .padding(horizontal = ActivityCardPaddingHorizontal, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(ActivitySpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeActivityStatusDot(status = state.worstStatus, pulses = false)
        Text(
            text =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = openCodeColor("text-weak"))) { append("Used ") }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = openCodeColor("text-strong"))) {
                        append(state.stepCount.toString())
                    }
                    if (names.isNotEmpty()) {
                        withStyle(SpanStyle(color = openCodeColor("text-weak"))) { append(" · $names") }
                    }
                },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ActivityChevron()
    }
}

// Pieces shared with OpenCodeActivitySheet.

/** `Working` with the running text shimmer; static text-base with animations off. */
@Composable
internal fun OpenCodeActivityWorkingLabel(
    text: String,
    shimmer: Boolean,
    style: TextStyle,
) {
    val semiBold = style.copy(fontWeight = FontWeight.SemiBold)
    if (shimmer) {
        val phase = rememberRunningPhase()
        Text(text = text, style = semiBold.copy(brush = runningShimmerBrush(phase.value)), maxLines = 1)
    } else {
        Text(text = text, style = semiBold, color = openCodeColor("text-base"), maxLines = 1)
    }
}

/** Uppercase, tracked, weakest tone: `AGENT`, `STEPS`. */
@Composable
internal fun OpenCodeActivitySectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.08.em),
        color = openCodeColor("text-weak"),
        maxLines = 1,
        modifier = modifier,
    )
}

/** 20pt rounded tile with the agent's first letter, in the agent tint. */
@Composable
internal fun OpenCodeActivityAgentTile(agentName: String) {
    val tint = openCodeColor("info")
    val shape = RoundedCornerShape(ActivityAgentTileRadius)
    Box(
        modifier =
            Modifier
                .size(ActivityAgentTileSize)
                .clip(shape)
                .background(tint.copy(alpha = ACTIVITY_AGENT_TILE_ALPHA))
                .border(1.dp, tint.copy(alpha = ACTIVITY_AGENT_BORDER_ALPHA), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = SessionChatChildSessionElapsed.letter(agentName),
            color = tint,
            maxLines = 1,
            textAlign = TextAlign.Center,
            // Material body styles carry a tall line height and font padding that push a
            // single glyph below the tile's centre; pin both so the letter sits centred.
            style =
                TextStyle(
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle =
                        LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                ),
        )
    }
}

/** 8pt dot: success, error, neutral for interrupted, warning in flight (pulsing when [pulses]). */
@Composable
internal fun OpenCodeActivityStatusDot(
    status: OpenCodeActivityStatus,
    pulses: Boolean,
    size: androidx.compose.ui.unit.Dp = ActivityDotSize,
) {
    when (status) {
        OpenCodeActivityStatus.Pending, OpenCodeActivityStatus.Running ->
            OpenCodePulsingDot(color = openCodeColor("warning"), size = size, animated = pulses)
        else -> {
            val color =
                when (status) {
                    OpenCodeActivityStatus.Failed -> openCodeColor("error")
                    OpenCodeActivityStatus.Interrupted -> openCodeColor("text-weaker")
                    else -> openCodeColor("success")
                }
            Box(modifier = Modifier.size(size).background(color, CircleShape))
        }
    }
}

@Composable
internal fun ActivityHairline(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(openCodeColor("border-weak-base")))
}

@Composable
internal fun ActivityChevron() {
    Icon(
        imageVector = OpenCodeIcons.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(ActivityChevronSize),
        tint = openCodeColor("text-weaker"),
    )
}

@Composable
internal fun activityMonoCaption() = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/** False with the system animator scale at 0 (Remove animations). */
@Composable
internal fun rememberActivityAnimationsOn(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/** One clock per card or sheet, so every elapsed label on it ticks together. */
@Composable
internal fun rememberActivityClock(ticking: Boolean): State<Long> =
    produceState(initialValue = System.currentTimeMillis(), ticking) {
        value = System.currentTimeMillis()
        if (!ticking) return@produceState
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

internal fun OpenCodeActivityIcon.vector(): ImageVector =
    when (this) {
        OpenCodeActivityIcon.Read -> OpenCodeIcons.Description
        OpenCodeActivityIcon.Search -> OpenCodeIcons.Search
        OpenCodeActivityIcon.Edit -> OpenCodeIcons.Edit
        OpenCodeActivityIcon.Command -> OpenCodeIcons.Terminal
        OpenCodeActivityIcon.Fetch -> OpenCodeIcons.Cloud
        OpenCodeActivityIcon.Agent -> OpenCodeIcons.Agent
        OpenCodeActivityIcon.Thought -> OpenCodeIcons.Architecture
        OpenCodeActivityIcon.Skill -> OpenCodeIcons.List
    }

internal fun OpenCodeActivityStatus.spoken(): String =
    when (this) {
        OpenCodeActivityStatus.Pending -> "pending"
        OpenCodeActivityStatus.Running -> "running"
        OpenCodeActivityStatus.Completed -> "completed"
        OpenCodeActivityStatus.Failed -> "failed"
        OpenCodeActivityStatus.Interrupted -> "stopped"
    }


/** Lets a row draw (and receive presses) [amount] wider than its slot on each side without moving its content. */
private fun Modifier.bleedHorizontally(amount: Dp): Modifier =
    layout { measurable, constraints ->
        val extra = (amount * 2).roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(
                    minWidth = (constraints.minWidth + extra).coerceAtMost(Constraints.Infinity),
                    maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
                ),
            )
        layout(width = (placeable.width - extra).coerceAtLeast(0), height = placeable.height) {
            placeable.place(x = -extra / 2, y = 0)
        }
    }

/** Gallery and preview samples for the states the contract lists. */
object OpenCodeActivityCardSamples {
    const val LIVE_SUMMARY = "6 steps · 3 reads · 1 search · 1 agent · 1 edit"

    fun twoAgents(nowMs: Long): List<OpenCodeActivityAgentRow> =
        listOf(
            OpenCodeActivityAgentRow(id = "c1", agentName = "Explore", action = "Searching \"branchPill\"", startedAtMs = nowMs - 64_000),
            OpenCodeActivityAgentRow(id = "c2", agentName = "Review", action = "Reading SessionHeader.kt", startedAtMs = nowMs - 21_000),
        )

    fun oneTool(nowMs: Long): OpenCodeActivityCurrentRow =
        OpenCodeActivityCurrentRow(
            icon = OpenCodeActivityIcon.Command,
            title = "Running ./gradlew :app:testDebugUnitTest",
            extraRunning = 0,
            startedAtMs = nowMs - 12_000,
        )

    fun editPlusOne(nowMs: Long): OpenCodeActivityCurrentRow =
        OpenCodeActivityCurrentRow(
            icon = OpenCodeActivityIcon.Edit,
            title = "Editing SessionChatRepoStrip.kt",
            extraRunning = 1,
            startedAtMs = nowMs - 3_000,
        )

    const val SETTLED_NAMES = "Read, Grep, Edit, Agent"
    const val SETTLED_FAILED_NAMES = "Read, Grep, Edit, Shell, Agent"
}

@Preview(showBackground = true, name = "Activity Card Light")
@Composable
private fun OpenCodeActivityCardPreviewLight() {
    OpenCodeTheme(darkTheme = false) { ActivityCardPreviewColumn() }
}

@Preview(showBackground = true, name = "Activity Card Dark")
@Composable
private fun OpenCodeActivityCardPreviewDark() {
    OpenCodeTheme(darkTheme = true) { ActivityCardPreviewColumn() }
}

@Composable
private fun ActivityCardPreviewColumn() {
    val now = System.currentTimeMillis()
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OpenCodeActivityCard(
            state = OpenCodeActivityCardState.Live(elapsedStartMs = now - 64_000),
            summary = OpenCodeActivityCardSamples.LIVE_SUMMARY,
            agents = OpenCodeActivityCardSamples.twoAgents(now),
            current = OpenCodeActivityCardSamples.editPlusOne(now),
            idleText = null,
            onAgentTap = {},
            onOpen = {},
        )
        OpenCodeActivityCard(
            state = OpenCodeActivityCardState.Settled(worstStatus = OpenCodeActivityStatus.Failed, stepCount = 7),
            summary = OpenCodeActivityCardSamples.SETTLED_FAILED_NAMES,
            agents = emptyList(),
            current = null,
            idleText = null,
            onAgentTap = {},
            onOpen = {},
        )
    }
}
