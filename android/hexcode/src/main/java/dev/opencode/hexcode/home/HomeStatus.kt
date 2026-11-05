package dev.opencode.hexcode.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexAgentState
import dev.opencode.hexcode.designsystem.components.HexButton
import dev.opencode.hexcode.designsystem.components.HexButtonSize
import dev.opencode.hexcode.designsystem.components.HexProgress
import dev.opencode.hexcode.designsystem.components.HexStatusLine
import dev.opencode.hexcode.designsystem.components.HexStatusTone
import dev.opencode.hexcode.designsystem.components.HexTopBar
import dev.opencode.hexcode.session.AgentStatus
import dev.opencode.hexcode.session.PendingInput
import dev.opencode.hexcode.session.RefreshLabel
import dev.opencode.hexcode.session.SessionSetup
import dev.opencode.hexcode.session.StatusSummary
import dev.opencode.hexcode.session.TodayState
import kotlinx.coroutines.delay

/** The wall clock, read again every [periodMs]: for the turn's timer and ↻'s age. */
@Composable
internal fun rememberNow(periodMs: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

/** Home's top bar for the agent's [status]: Reconnecting…, Needs input, Working with the turn's clock, or Idle. */
@Composable
internal fun HomeTopBar(
    status: AgentStatus,
    now: Long,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    when (status) {
        AgentStatus.Reconnecting ->
            HexTopBar(HexAgentState.Reconnecting, stringResource(R.string.home_state_reconnecting), modifier, actions = actions)
        AgentStatus.NeedsInput ->
            HexTopBar(
                HexAgentState.NeedsInput,
                stringResource(R.string.home_state_needs_input),
                modifier,
                actions = actions,
            )
        is AgentStatus.Working ->
            HexTopBar(
                HexAgentState.Working,
                stringResource(R.string.home_state_working),
                modifier,
                timer = status.clock(now),
                actions = actions,
            )
        AgentStatus.Idle -> HexTopBar(HexAgentState.Idle, stringResource(R.string.home_state_idle), modifier, actions = actions)
    }
}

/**
 * The status area (`.fx-body`), centred in the space above the talk button: the status card, the fallback, or
 * "Couldn't open today's session" with Try again, and the Needs-you hint under the card.
 */
@Composable
internal fun StatusArea(
    today: TodayState,
    now: Long,
    vm: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        when (today.setup) {
            SessionSetup.Opening -> Unit
            SessionSetup.Failed -> SetupFailed(vm::retrySetup)
            is SessionSetup.Ready -> {
                val summary = today.summary
                val label = summary.label(now)
                val dim by animateFloatAsState(if (summary.loading) 0.35f else 1f, HexTheme.motion.fade(), label = "loading")
                when (val card = summary.summary) {
                    is StatusSummary.Card ->
                        Card(
                            card.title,
                            card.now,
                            card.next,
                            card.steps.map { it.done },
                            dim,
                            label,
                            vm::refreshSummary,
                        )
                    is StatusSummary.Empty ->
                        Card(
                            card.title,
                            stringResource(R.string.home_summary_empty_now),
                            stringResource(R.string.home_summary_empty_next),
                            emptyList(),
                            dim,
                            label,
                            vm::refreshSummary,
                        )
                    is StatusSummary.Fallback -> Fallback(card.text, dim, label, vm::refreshSummary)
                    null -> Meta(label, vm::refreshSummary) {}
                }
                today.pendingInput?.let { NeedsHint(it, onClick = {}) }
            }
        }
    }
}

/** The card: the task as a label, what it's doing now in large type, what's next, and the plan's progress. */
@Composable
private fun Card(
    title: String,
    now: String,
    next: String?,
    steps: List<Boolean>,
    dim: Float,
    label: RefreshLabel?,
    onRefresh: () -> Unit,
) {
    val colors = HexTheme.colors
    Text(title, style = HexTheme.type.caption, color = colors.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        now,
        style = HexTheme.type.display,
        color = colors.text1,
        maxLines = 5,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.alpha(dim),
    )
    if (next != null) {
        Text(
            stringResource(R.string.home_summary_next, next),
            style = HexTheme.type.body,
            color = colors.text2,
            modifier = Modifier.alpha(dim),
        )
    }
    Meta(label, onRefresh) {
        if (steps.isNotEmpty()) {
            // Details comes with M4; until then the progress does nothing.
            HexProgress(steps, stringResource(R.string.home_summary_progress, steps.count { it }, steps.size), onClick = {})
        }
    }
}

/** A reply that isn't in the card's shape, as plain text, with a note and ↻. */
@Composable
private fun Fallback(
    text: String,
    dim: Float,
    label: RefreshLabel?,
    onRefresh: () -> Unit,
) {
    val colors = HexTheme.colors
    Text(stringResource(R.string.home_summary_fallback_title), style = HexTheme.type.caption, color = colors.text3)
    Text(
        text,
        style = HexTheme.type.body,
        color = colors.text2,
        maxLines = 8,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.alpha(dim),
    )
    Meta(label, onRefresh) {
        HexStatusLine(stringResource(R.string.home_summary_fallback_note), HexStatusTone.Warning)
    }
}

/** The card's last line (`.fx-meta`): [leading] (progress, or the fallback's note), then ↻ on the right. */
@Composable
private fun Meta(
    label: RefreshLabel?,
    onRefresh: () -> Unit,
    leading: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp),
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        if (label != null) Refresh(label, onRefresh)
    }
}

/** ↻ (`.fx-upd`): the refresh icon, turning while a request runs, and "now", "3m ago", "updating…" or "Couldn't update". */
@Composable
private fun Refresh(
    label: RefreshLabel,
    onClick: () -> Unit,
) {
    val colors = HexTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val text =
        when (label) {
            RefreshLabel.Updating -> stringResource(R.string.home_summary_updating)
            RefreshLabel.Failed -> stringResource(R.string.home_summary_failed)
            RefreshLabel.Now -> stringResource(R.string.home_summary_updated_now)
            is RefreshLabel.MinutesAgo -> stringResource(R.string.home_summary_updated_ago, label.minutes)
        }
    val color =
        when {
            pressed -> colors.text1
            label == RefreshLabel.Failed -> colors.amber
            else -> colors.text3
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier =
            Modifier
                .clip(RoundedCornerShape(HexTheme.radius.s))
                .clickable(source, null, role = Role.Button, onClick = onClick)
                .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        val turn =
            if (label == RefreshLabel.Updating) {
                val transition = rememberInfiniteTransition(label = "refresh")
                transition
                    .animateFloat(
                        0f,
                        360f,
                        infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
                        label = "turn",
                    ).value
            } else {
                0f
            }
        Icon(HexIcons.refresh, null, tint = color, modifier = Modifier.size(HexTheme.size.iconSmall).rotate(turn))
        Text(text, style = HexTheme.type.mono, color = color, maxLines = 1)
    }
}

/** "Couldn't open today's session", with Try again. */
@Composable
private fun SetupFailed(onRetry: () -> Unit) {
    Text(stringResource(R.string.home_setup_failed), style = HexTheme.type.display, color = HexTheme.colors.text1)
    HexButton(onClick = onRetry, text = stringResource(R.string.home_setup_retry), icon = HexIcons.refresh, size = HexButtonSize.Regular)
}

/** The amber hint (`.needs-hint`) while a question or approval waits: what it is, and its text. */
@Composable
private fun NeedsHint(
    input: PendingInput,
    onClick: () -> Unit,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel)
                .background(colors.amberSoft)
                .border(1.dp, colors.amber.copy(alpha = 0.3f), shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Icon(HexIcons.alert, null, tint = colors.amber, modifier = Modifier.size(HexTheme.size.icon))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                stringResource(if (input.kind == PendingInput.Kind.Approval) R.string.home_needs_approval else R.string.home_needs_input),
                style = HexTheme.type.button,
                color = colors.text1,
            )
            Text(input.text, style = HexTheme.type.caption, color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(HexIcons.chevR, null, tint = colors.text3, modifier = Modifier.size(HexTheme.size.icon))
    }
}

/** "N waiting · sends after this turn ›" (`.waitline`), while the inbox has queued messages. */
@Composable
internal fun WaitingLine(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.m)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel)
                .border(1.dp, colors.line, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(HexIcons.send, null, tint = colors.text3, modifier = Modifier.size(HexTheme.size.iconSmall))
        // The text ends in "›", so there is no chevron icon after it, unlike the prototype's `.waitline`.
        Text(
            stringResource(R.string.home_waiting, count),
            style = HexTheme.type.small,
            color = colors.text2,
            modifier = Modifier.weight(1f),
        )
    }
}
