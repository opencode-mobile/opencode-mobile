package dev.opencode.mobile.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionElapsed

/** A child session in an [OpenCodeActivitySheet]; [recent] are its latest steps, oldest first. */
data class OpenCodeActivitySheetAgent(
    val id: String,
    val agentName: String,
    val description: String?,
    /** `N tools so far` while running, the finished summary once done. */
    val caption: String?,
    val status: OpenCodeActivityStatus,
    val startedAtMs: Long?,
    val recent: List<OpenCodeActivitySheetSubStep> = emptyList(),
    val tappable: Boolean = true,
)

data class OpenCodeActivitySheetSubStep(
    val id: String,
    val title: String,
    val status: OpenCodeActivityStatus,
)

/** One non-child step in an [OpenCodeActivitySheet]. */
data class OpenCodeActivitySheetStep(
    val id: String,
    val icon: OpenCodeActivityIcon,
    val title: String,
    val detail: String? = null,
    val status: OpenCodeActivityStatus,
    val additions: Int? = null,
    val deletions: Int? = null,
    val startedAtMs: Long? = null,
    val tappable: Boolean = true,
)

/** A step's detail an [OpenCodeActivitySheet] shows in place of its list. */
data class OpenCodeActivitySheetDetail(
    val stepId: String,
    val title: String,
)

private class SheetPaneMemory {
    var switches = 0
    var lastDetailStepId: String? = null
}

/** Which pane of the sheet a scroll reset targets, and the how-manyth switch it was. */
private data class SheetPaneScrollKey(
    val pane: String,
    val switch: Int,
)

private val SheetRowHorizontal = 16.dp
private val SheetRowVertical = 14.dp

/** Comfortable tap target for a sheet row (Material minimum is 48dp). */
private val SheetRowMinHeight = 48.dp
private val SheetSpacing = 10.dp
private val SheetIconSize = 16.dp
private val SheetSubStepInset = 44.dp

/**
 * Activity sheet (#779): everything one activity card segment did, kept live
 * while open. The header reads `Working N` (shimmer and elapsed) while [live],
 * `N steps` once settled. An Agent section lists every child session in
 * [agents] with up to three recent steps under a running one; a Steps section
 * lists the other [steps] in order. Rows are keyed by id, so the list grows in
 * place. Composes [OpenCodeBottomSheet] at half height.
 *
 * With a [detail], the same sheet shows that step's [detailContent] instead:
 * a back arrow and the detail title lead the header, and back (arrow or
 * system back) calls [onDetailBack]. Close still dismisses the whole sheet.
 * The detail opens at its top; back scrolls the list to the step it came from.
 *
 * Steps stay oldest first. A [live] list opens at its newest step and keeps new
 * steps in view while it is parked at the bottom (#879); a settled one opens at
 * its top. Settling while open leaves the list where it is.
 */
@Composable
fun OpenCodeActivitySheet(
    visible: Boolean,
    live: Boolean,
    stepCount: Int,
    startedAtMs: Long?,
    agents: List<OpenCodeActivitySheetAgent>,
    steps: List<OpenCodeActivitySheetStep>,
    onAgentTap: (id: String) -> Unit,
    onStepTap: (id: String) -> Unit,
    onDismissRequest: () -> Unit,
    detail: OpenCodeActivitySheetDetail? = null,
    onDetailBack: () -> Unit = {},
    detailContent: @Composable ColumnScope.() -> Unit = {},
) {
    val animationsOn = rememberActivityAnimationsOn()
    val now by rememberActivityClock(ticking = visible && live)
    val title = if (live) "Working $stepCount" else openCodeActivityStepCount(stepCount)
    val elapsed = if (live) SessionChatChildSessionElapsed.format(startedAtMs, now) else null
    // Each list/detail switch gets a fresh key, so the body scrolls once per switch.
    val panes = remember { SheetPaneMemory() }
    val paneKey =
        remember(visible, detail?.stepId) {
            panes.switches += 1
            // A sheet opened again starts fresh, not on the step it last showed.
            if (!visible) panes.lastDetailStepId = null
            if (detail != null) panes.lastDetailStepId = detail.stepId
            SheetPaneScrollKey(if (detail == null) "list" else "detail", panes.switches)
        }
    val lastDetailStepId = panes.lastDetailStepId
    val listTitle: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(title, elapsed).joinToString(", ")
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = MaterialTheme.typography.titleMedium
            if (live) {
                OpenCodeActivityWorkingLabel(text = "Working", shimmer = animationsOn, style = style)
                Text(text = stepCount.toString(), style = style, color = openCodeColor("text-strong"), maxLines = 1)
            } else {
                Text(text = title, style = style, color = openCodeColor("text-strong"), maxLines = 1)
            }
            Box(modifier = Modifier.weight(1f))
            if (elapsed != null) {
                Text(text = elapsed, style = activityMonoCaption(), color = openCodeColor("text-weak"), maxLines = 1)
            }
        }
    }
    OpenCodeBottomSheet(
        visible = visible,
        title = detail?.title ?: title,
        onDismissRequest = onDismissRequest,
        height = OpenCodeBottomSheetHeight.Half,
        scrollTargetKey = paneKey,
        followsBottom = live && detail == null,
        headerLeadingAction =
            detail?.let {
                OpenCodeBottomSheetHeaderAction(
                    imageVector = OpenCodeIcons.ArrowBack,
                    contentDescription = "Back to activity",
                    onClick = onDetailBack,
                )
            },
        titleContent = listTitle.takeIf { detail == null },
    ) {
        if (detail != null) {
            // Zero height at the top: scrolling it to center lands the detail at its start.
            Spacer(modifier = Modifier.openCodeBottomSheetScrollTarget(paneKey))
            detailContent()
            return@OpenCodeBottomSheet
        }
        if (agents.isNotEmpty()) {
            OpenCodeActivitySectionLabel(
                text = openCodeActivityAgentSectionLabel(agents.size),
                modifier = Modifier.padding(start = SheetRowHorizontal, end = SheetRowHorizontal, top = 10.dp, bottom = 4.dp),
            )
            agents.forEach { agent ->
                key(agent.id) {
                    AgentRow(agent = agent, nowMs = now, pulses = animationsOn, onTap = { onAgentTap(agent.id) })
                }
            }
            if (steps.isNotEmpty()) {
                OpenCodeActivitySectionLabel(
                    text = "Steps",
                    modifier = Modifier.padding(start = SheetRowHorizontal, end = SheetRowHorizontal, top = 10.dp, bottom = 4.dp),
                )
            }
        }
        steps.forEachIndexed { index, step ->
            key(step.id) {
                if (index > 0) ActivityHairline()
                StepRow(
                    step = step,
                    nowMs = now,
                    pulses = animationsOn,
                    onTap = { onStepTap(step.id) },
                    // Back from a detail centers the step it came from.
                    modifier = if (step.id == lastDetailStepId) Modifier.openCodeBottomSheetScrollTarget(paneKey) else Modifier,
                )
            }
        }
    }
    // Composed only while a detail shows, so it is registered after the sheet's own back handler and wins.
    if (visible && detail != null) BackHandler(onBack = onDetailBack)
}

@Composable
private fun AgentRow(
    agent: OpenCodeActivitySheetAgent,
    nowMs: Long,
    pulses: Boolean,
    onTap: () -> Unit,
) {
    val running = agent.status.isInFlight
    val elapsed = if (running) SessionChatChildSessionElapsed.format(agent.startedAtMs, nowMs) else null
    val tint = openCodeColor("info")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (agent.tappable) Modifier.clickable(onClick = onTap) else Modifier)
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            listOfNotNull(agent.agentName, agent.description, agent.status.spoken(), agent.caption, elapsed)
                                .joinToString(", ")
                        if (agent.tappable) role = Role.Button
                    }.heightIn(min = SheetRowMinHeight)
                    .padding(horizontal = SheetRowHorizontal, vertical = SheetRowVertical),
            horizontalArrangement = Arrangement.spacedBy(SheetSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeActivityStatusDot(status = agent.status, pulses = pulses && running)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold)) { append(agent.agentName) }
                            if (!agent.description.isNullOrEmpty()) {
                                withStyle(SpanStyle(color = openCodeColor("text-weak"))) { append(" ${agent.description}") }
                            }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!agent.caption.isNullOrEmpty()) {
                    Text(
                        text = agent.caption,
                        style = activityMonoCaption(),
                        color = openCodeColor("text-weak"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (elapsed != null) {
                Text(text = elapsed, style = activityMonoCaption(), color = openCodeColor("text-weaker"), maxLines = 1)
            }
            if (agent.tappable) ActivityChevron()
        }
        if (running && agent.recent.isNotEmpty()) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                agent.recent.forEach { sub ->
                    key(sub.id) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = SheetSubStepInset, end = SheetRowHorizontal, top = 3.dp, bottom = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OpenCodeActivityStatusDot(status = sub.status, pulses = pulses, size = 6.dp)
                            Text(
                                text = sub.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = openCodeColor("text-weak"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        ActivityHairline()
    }
}

@Composable
private fun StepRow(
    step: OpenCodeActivitySheetStep,
    nowMs: Long,
    pulses: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = step.status.isInFlight
    val elapsed = if (running) SessionChatChildSessionElapsed.format(step.startedAtMs, nowMs) else null
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (step.tappable) Modifier.clickable(onClick = onTap) else Modifier)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(step.title, step.detail, step.status.spoken(), elapsed).joinToString(", ")
                    if (step.tappable) role = Role.Button
                }.heightIn(min = SheetRowMinHeight)
                    .padding(horizontal = SheetRowHorizontal, vertical = SheetRowVertical),
        horizontalArrangement = Arrangement.spacedBy(SheetSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeActivityStatusDot(status = step.status, pulses = pulses && running)
        Icon(
            imageVector = step.icon.vector(),
            contentDescription = null,
            modifier = Modifier.size(SheetIconSize),
            tint = openCodeColor("icon-base"),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (running) openCodeColor("text-strong") else openCodeColor("text-base"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!step.detail.isNullOrEmpty()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.status == OpenCodeActivityStatus.Failed) openCodeColor("error") else openCodeColor("text-weak"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        step.additions?.takeIf { it > 0 }?.let {
            Text(text = "+$it", style = activityMonoCaption(), color = openCodeColor("diff-add"), maxLines = 1)
        }
        step.deletions?.takeIf { it > 0 }?.let {
            Text(text = "−$it", style = activityMonoCaption(), color = openCodeColor("diff-delete"), maxLines = 1)
        }
        if (elapsed != null) {
            Text(text = elapsed, style = activityMonoCaption(), color = openCodeColor("text-weaker"), maxLines = 1)
        }
    }
}

/** Gallery and preview samples. */
object OpenCodeActivitySheetSamples {
    fun agents(nowMs: Long): List<OpenCodeActivitySheetAgent> =
        listOf(
            OpenCodeActivitySheetAgent(
                id = "c1",
                agentName = "Explore",
                description = "Find where the branch pill is rendered",
                caption = "2 tools so far",
                status = OpenCodeActivityStatus.Running,
                startedAtMs = nowMs - 64_000,
                recent =
                    listOf(
                        OpenCodeActivitySheetSubStep("s1", "GitStatusRepository.kt", OpenCodeActivityStatus.Completed),
                        OpenCodeActivitySheetSubStep("s2", "Searching \"branch\"", OpenCodeActivityStatus.Running),
                    ),
            ),
        )

    fun steps(nowMs: Long): List<OpenCodeActivitySheetStep> =
        listOf(
            OpenCodeActivitySheetStep("d1", OpenCodeActivityIcon.Read, "SessionChatRepoStrip.kt", status = OpenCodeActivityStatus.Completed),
            OpenCodeActivitySheetStep(
                "d2",
                OpenCodeActivityIcon.Search,
                "repoStrip",
                detail = "7 matches",
                status = OpenCodeActivityStatus.Completed,
            ),
            OpenCodeActivitySheetStep(
                "d3",
                OpenCodeActivityIcon.Command,
                "Ran ./gradlew :app:assembleDebug",
                detail = "Build failed",
                status = OpenCodeActivityStatus.Failed,
            ),
            OpenCodeActivitySheetStep(
                "r1",
                OpenCodeActivityIcon.Edit,
                "Editing SessionChatRepoStrip.kt",
                status = OpenCodeActivityStatus.Running,
                additions = 18,
                deletions = 4,
                startedAtMs = nowMs - 2_000,
            ),
        )
}

@Preview(showBackground = true, name = "Activity Sheet Dark")
@Composable
private fun OpenCodeActivitySheetPreviewDark() {
    val now = System.currentTimeMillis()
    OpenCodeTheme(darkTheme = true) {
        OpenCodeActivitySheet(
            visible = true,
            live = true,
            stepCount = 6,
            startedAtMs = now - 64_000,
            agents = OpenCodeActivitySheetSamples.agents(now),
            steps = OpenCodeActivitySheetSamples.steps(now),
            onAgentTap = {},
            onStepTap = {},
            onDismissRequest = {},
        )
    }
}
