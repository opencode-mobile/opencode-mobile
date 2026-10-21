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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionElapsed
import kotlinx.coroutines.delay

/** Whether an [OpenCodeActivityCard] is the live card of a working turn or a finished segment. */
enum class OpenCodeActivityCardState { Live, Settled }

enum class OpenCodeActivityLineKind { Tool, ChildSession, Thought }

/** Leading glyph of an [OpenCodeActivityLine], by what the step did. */
enum class OpenCodeActivityIcon { Read, Search, Edit, Command, Fetch, Agent, Thought, Skill }

/** Status of one line; [Interrupted] is a stop the user caused and renders neutral. */
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

/** One step on an [OpenCodeActivityCard]. [id] is the stable row key and the value tap callbacks get. */
data class OpenCodeActivityLine(
    val id: String,
    val kind: OpenCodeActivityLineKind,
    val icon: OpenCodeActivityIcon,
    val title: String,
    val detail: String? = null,
    val status: OpenCodeActivityStatus,
    val additions: Int? = null,
    val deletions: Int? = null,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val tappable: Boolean = true,
)

private val ActivityCardRadius = 14.dp
private val ActivityCardPaddingHorizontal = 12.dp
private val ActivityCardPaddingVertical = 10.dp
private val ActivityLineVerticalPadding = 5.dp
private val ActivityLineMinHeight = 26.dp
private val ActivityLineSpacing = 8.dp
private val ActivityStatusSlot = 12.dp
private val ActivityDotSize = 8.dp
private val ActivityIconSize = 16.dp
private val ActivityChevronSize = 16.dp
private val ActivityDetailMaxWidth = 120.dp

/**
 * Activity card timeline (#779): one card per run of steps. Live, it shows a
 * shimmering `Working` header with the segment's elapsed time, one line per
 * running step (at most three, then `+N more running`), and a
 * footer [summary] of what finished. Expanded, finished [doneLines] list above
 * the running ones. Settled and collapsed it is one line: a dot in
 * [worstStatus] tone and [summary] (`9 steps · 3 reads`). Rows are keyed by
 * line id so lines join and leave in place.
 */
@Composable
fun OpenCodeActivityCard(
    state: OpenCodeActivityCardState,
    startedAtMs: Long?,
    endedAtMs: Long?,
    thinking: Boolean,
    runningLines: List<OpenCodeActivityLine>,
    doneLines: List<OpenCodeActivityLine>,
    summary: String,
    worstStatus: OpenCodeActivityStatus,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onLineTap: (lineId: String) -> Unit,
    onChildSessionTap: (lineId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animationsOn =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }
    val live = state == OpenCodeActivityCardState.Live
    // One clock per card, so every elapsed label on it ticks together.
    val now by produceState(initialValue = System.currentTimeMillis(), live) {
        if (!live) return@produceState
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val shape = RoundedCornerShape(ActivityCardRadius)
    val cardModifier =
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(openCodeColor("surface-raised-base"))
            .border(1.dp, openCodeColor("border-weak-base"), shape)

    if (!live && !isExpanded) {
        SettledLine(summary = summary, worstStatus = worstStatus, onTap = onToggleExpanded, modifier = cardModifier)
        return
    }

    val elapsed =
        when {
            live -> SessionChatChildSessionElapsed.format(startedAtMs, now)
            endedAtMs != null -> SessionChatChildSessionElapsed.format(startedAtMs, endedAtMs)
            else -> null
        }
    Column(modifier = cardModifier) {
        Header(
            live = live,
            elapsed = elapsed,
            runningCount = runningLines.size,
            doneCount = doneLines.size,
            shimmer = animationsOn,
            onTap = onToggleExpanded,
        )
        Column(modifier = Modifier.padding(horizontal = ActivityCardPaddingHorizontal)) {
            if (isExpanded) {
                doneLines.forEach { line ->
                    key(line.id) {
                        ActivityLineRow(
                            line = line,
                            nowMs = now,
                            animated = animationsOn,
                            onTap = lineTap(line, onLineTap, onChildSessionTap),
                        )
                    }
                }
            }
            if (live && thinking && runningLines.isEmpty()) {
                Text(
                    text = "Thinking…",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = openCodeColor("text-weak"),
                    maxLines = 1,
                    modifier = Modifier.padding(vertical = ActivityLineVerticalPadding),
                )
            }
            val visibleRunning =
                if (runningLines.size > ACTIVITY_CARD_MAX_RUNNING) runningLines.take(ACTIVITY_CARD_MAX_RUNNING - 1) else runningLines
            visibleRunning.forEach { line ->
                key(line.id) {
                    ActivityLineRow(
                        line = line,
                        nowMs = now,
                        animated = animationsOn,
                        onTap = lineTap(line, onLineTap, onChildSessionTap),
                    )
                }
            }
            if (runningLines.size > visibleRunning.size) {
                Text(
                    text = "+${runningLines.size - visibleRunning.size} more running",
                    style = MaterialTheme.typography.bodyMedium,
                    color = openCodeColor("text-weaker"),
                    maxLines = 1,
                    modifier =
                        Modifier
                            .padding(vertical = ActivityLineVerticalPadding)
                            .padding(start = ActivityStatusSlot + ActivityLineSpacing),
                )
            }
        }
        Footer(summary = summary, isExpanded = isExpanded, onTap = onToggleExpanded)
    }
}

/** At most this many running lines show; more collapse into one `+N more running` line. */
private const val ACTIVITY_CARD_MAX_RUNNING = 3

private fun lineTap(
    line: OpenCodeActivityLine,
    onLineTap: (String) -> Unit,
    onChildSessionTap: (String) -> Unit,
): (() -> Unit)? {
    if (!line.tappable) return null
    return if (line.kind == OpenCodeActivityLineKind.ChildSession) {
        { onChildSessionTap(line.id) }
    } else {
        { onLineTap(line.id) }
    }
}

@Composable
private fun Header(
    live: Boolean,
    elapsed: String?,
    runningCount: Int,
    doneCount: Int,
    shimmer: Boolean,
    onTap: () -> Unit,
) {
    val label =
        if (live) {
            listOfNotNull("Working", "$runningCount running", "$doneCount done", elapsed).joinToString(", ")
        } else {
            listOfNotNull("Done", "$doneCount steps", elapsed).joinToString(", ")
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(
                    start = ActivityCardPaddingHorizontal,
                    end = ActivityCardPaddingHorizontal,
                    top = ActivityCardPaddingVertical,
                    bottom = 2.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        if (live && shimmer) {
            val phase = rememberRunningPhase()
            Text(text = "Working", style = style.copy(brush = runningShimmerBrush(phase.value)), maxLines = 1)
        } else {
            Text(
                text = if (live) "Working" else "Done",
                style = style,
                color = if (live) openCodeColor("text-base") else openCodeColor("text-weaker"),
                maxLines = 1,
            )
        }
        Box(modifier = Modifier.weight(1f))
        if (elapsed != null) {
            Text(text = elapsed, style = monoCaption(), color = openCodeColor("text-weaker"), maxLines = 1)
        }
    }
}

@Composable
private fun Footer(
    summary: String,
    isExpanded: Boolean,
    onTap: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$summary, ${if (isExpanded) "collapses" else "expands"}"
                    role = Role.Button
                }.padding(horizontal = ActivityCardPaddingHorizontal),
    ) {
        Box(
            modifier =
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(openCodeColor("border-weak-base")),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(ActivityLineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weaker"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Chevron(expanded = isExpanded)
        }
    }
}

@Composable
private fun SettledLine(
    summary: String,
    worstStatus: OpenCodeActivityStatus,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val count = summary.substringBefore(SUMMARY_SEPARATOR)
    val families = summary.substringAfter(SUMMARY_SEPARATOR, missingDelimiterValue = "")
    Row(
        modifier =
            modifier
                .clickable(onClick = onTap)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$summary, expands"
                    role = Role.Button
                }.heightIn(min = 36.dp)
                .padding(horizontal = ActivityCardPaddingHorizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ActivityLineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status = worstStatus)
        Text(
            text =
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = openCodeColor("text-strong"))) { append(count) }
                    if (families.isNotEmpty()) {
                        withStyle(SpanStyle(color = openCodeColor("text-weak"))) { append(SUMMARY_SEPARATOR + families) }
                    }
                },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Chevron(expanded = false)
    }
}

@Composable
private fun ActivityLineRow(
    line: OpenCodeActivityLine,
    nowMs: Long,
    animated: Boolean,
    onTap: (() -> Unit)?,
) {
    val running = line.status.isInFlight
    val elapsed = if (running) SessionChatChildSessionElapsed.format(line.startedAtMs, nowMs) else null
    val isChild = line.kind == OpenCodeActivityLineKind.ChildSession
    val tint =
        when {
            isChild && line.status != OpenCodeActivityStatus.Failed -> openCodeColor("info")
            running -> openCodeColor("text-strong")
            else -> openCodeColor("text-weak")
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ActivityLineMinHeight)
                .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(line.title, line.status.spoken(), elapsed).joinToString(", ")
                    if (onTap != null) role = Role.Button
                }.padding(vertical = ActivityLineVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(ActivityLineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(ActivityStatusSlot), contentAlignment = Alignment.Center) {
            if (running && animated) {
                CircularProgressIndicator(
                    modifier = Modifier.size(ActivityStatusSlot),
                    color = openCodeColor("warning"),
                    trackColor = openCodeColor("border-weaker-base"),
                    strokeWidth = 1.5.dp,
                )
            } else {
                StatusDot(status = line.status)
            }
        }
        Icon(
            imageVector = line.icon.vector(),
            contentDescription = null,
            modifier = Modifier.size(ActivityIconSize),
            tint = if (isChild) openCodeColor("info") else openCodeColor("icon-base"),
        )
        Text(
            text = line.title,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!running && !line.detail.isNullOrEmpty() && line.status == OpenCodeActivityStatus.Completed) {
            Text(
                text = line.detail,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weaker"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = ActivityDetailMaxWidth),
            )
        }
        LineTrailing(line = line, elapsed = elapsed)
    }
}

@Composable
private fun LineTrailing(
    line: OpenCodeActivityLine,
    elapsed: String?,
) {
    when {
        elapsed != null -> Text(text = elapsed, style = monoCaption(), color = openCodeColor("text-weaker"), maxLines = 1)
        line.status == OpenCodeActivityStatus.Failed ->
            Text(text = "failed", style = MaterialTheme.typography.labelSmall, color = openCodeColor("error"), maxLines = 1)
        line.status == OpenCodeActivityStatus.Interrupted ->
            Text(text = "stopped", style = MaterialTheme.typography.labelSmall, color = openCodeColor("text-weak"), maxLines = 1)
        (line.additions ?: 0) > 0 || (line.deletions ?: 0) > 0 ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                line.additions?.takeIf { it > 0 }?.let {
                    Text(text = "+$it", style = monoCaption(), color = openCodeColor("diff-add"), maxLines = 1)
                }
                line.deletions?.takeIf { it > 0 }?.let {
                    Text(text = "−$it", style = monoCaption(), color = openCodeColor("diff-delete"), maxLines = 1)
                }
            }
    }
}

/** 8pt dot: success, error, neutral for interrupted, warning while in flight (the Reduce Motion spinner). */
@Composable
private fun StatusDot(status: OpenCodeActivityStatus) {
    val color =
        when (status) {
            OpenCodeActivityStatus.Completed -> openCodeColor("success")
            OpenCodeActivityStatus.Failed -> openCodeColor("error")
            OpenCodeActivityStatus.Interrupted -> openCodeColor("text-weaker")
            OpenCodeActivityStatus.Pending, OpenCodeActivityStatus.Running -> openCodeColor("warning")
        }
    Box(modifier = Modifier.size(ActivityDotSize).background(color, CircleShape))
}

@Composable
private fun Chevron(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) OpenCodeIcons.KeyboardArrowDown else OpenCodeIcons.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(ActivityChevronSize).then(if (expanded) Modifier.rotate(180f) else Modifier),
        tint = openCodeColor("text-weaker"),
    )
}

@Composable
private fun monoCaption() = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

private fun OpenCodeActivityIcon.vector(): ImageVector =
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

private fun OpenCodeActivityStatus.spoken(): String =
    when (this) {
        OpenCodeActivityStatus.Pending -> "pending"
        OpenCodeActivityStatus.Running -> "running"
        OpenCodeActivityStatus.Completed -> "completed"
        OpenCodeActivityStatus.Failed -> "failed"
        OpenCodeActivityStatus.Interrupted -> "stopped"
    }

private const val SUMMARY_SEPARATOR = " · "

/** Gallery and preview samples for the states the contract lists. */
object OpenCodeActivityCardSamples {
    fun oneRunning(nowMs: Long): List<OpenCodeActivityLine> =
        listOf(
            OpenCodeActivityLine(
                id = "r1",
                kind = OpenCodeActivityLineKind.Tool,
                icon = OpenCodeActivityIcon.Command,
                title = "Running ./gradlew :app:testDebugUnitTest",
                status = OpenCodeActivityStatus.Running,
                startedAtMs = nowMs - 12_000,
            ),
        )

    fun threeRunningWithChild(nowMs: Long): List<OpenCodeActivityLine> =
        listOf(
            OpenCodeActivityLine(
                id = "r1",
                kind = OpenCodeActivityLineKind.Tool,
                icon = OpenCodeActivityIcon.Read,
                title = "Reading OpenCodeToolChip.kt",
                status = OpenCodeActivityStatus.Running,
                startedAtMs = nowMs - 3_000,
            ),
            OpenCodeActivityLine(
                id = "r2",
                kind = OpenCodeActivityLineKind.Tool,
                icon = OpenCodeActivityIcon.Search,
                title = "Searching statusDot",
                status = OpenCodeActivityStatus.Running,
                startedAtMs = nowMs - 2_000,
            ),
            OpenCodeActivityLine(
                id = "c1",
                kind = OpenCodeActivityLineKind.ChildSession,
                icon = OpenCodeActivityIcon.Agent,
                title = "Explore · Find where the branch pill is rendered",
                status = OpenCodeActivityStatus.Running,
                startedAtMs = nowMs - 64_000,
            ),
        )

    val someDone: List<OpenCodeActivityLine> =
        listOf(
            OpenCodeActivityLine(
                id = "d1",
                kind = OpenCodeActivityLineKind.Tool,
                icon = OpenCodeActivityIcon.Read,
                title = "SessionChatScreen.kt",
                status = OpenCodeActivityStatus.Completed,
            ),
            OpenCodeActivityLine(
                id = "d2",
                kind = OpenCodeActivityLineKind.Tool,
                icon = OpenCodeActivityIcon.Search,
                title = "branchPill",
                detail = "4 matches",
                status = OpenCodeActivityStatus.Completed,
            ),
            OpenCodeActivityLine(
                id = "d3",
                kind = OpenCodeActivityLineKind.Tool,
                icon = OpenCodeActivityIcon.Edit,
                title = "SessionHeader.kt",
                status = OpenCodeActivityStatus.Completed,
                additions = 18,
                deletions = 4,
            ),
        )

    val settledWithFailure: List<OpenCodeActivityLine> =
        someDone +
            listOf(
                OpenCodeActivityLine(
                    id = "d4",
                    kind = OpenCodeActivityLineKind.Tool,
                    icon = OpenCodeActivityIcon.Command,
                    title = "Ran ./gradlew :app:assembleDebug",
                    status = OpenCodeActivityStatus.Failed,
                ),
                OpenCodeActivityLine(
                    id = "d5",
                    kind = OpenCodeActivityLineKind.ChildSession,
                    icon = OpenCodeActivityIcon.Agent,
                    title = "Review · Standards review of the diff",
                    status = OpenCodeActivityStatus.Completed,
                ),
            )

    const val SOME_DONE_SUMMARY = "3 done · 1 read · 1 search · 1 edit"
    const val SETTLED_SUMMARY = "5 steps · 1 read · 1 search · 1 edit · 1 command · 1 agent"
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
            state = OpenCodeActivityCardState.Live,
            startedAtMs = now - 64_000,
            endedAtMs = null,
            thinking = false,
            runningLines = OpenCodeActivityCardSamples.threeRunningWithChild(now),
            doneLines = OpenCodeActivityCardSamples.someDone,
            summary = OpenCodeActivityCardSamples.SOME_DONE_SUMMARY,
            worstStatus = OpenCodeActivityStatus.Completed,
            isExpanded = true,
            onToggleExpanded = {},
            onLineTap = {},
            onChildSessionTap = {},
        )
        OpenCodeActivityCard(
            state = OpenCodeActivityCardState.Settled,
            startedAtMs = null,
            endedAtMs = null,
            thinking = false,
            runningLines = emptyList(),
            doneLines = OpenCodeActivityCardSamples.settledWithFailure,
            summary = OpenCodeActivityCardSamples.SETTLED_SUMMARY,
            worstStatus = OpenCodeActivityStatus.Failed,
            isExpanded = false,
            onToggleExpanded = {},
            onLineTap = {},
            onChildSessionTap = {},
        )
    }
}
