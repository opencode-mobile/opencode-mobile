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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexAgentState
import dev.opencode.hexcode.designsystem.components.HexButton
import dev.opencode.hexcode.designsystem.components.HexButtonSize
import dev.opencode.hexcode.designsystem.components.HexDot
import dev.opencode.hexcode.designsystem.components.HexDotTone
import dev.opencode.hexcode.designsystem.components.HexLinkKind
import dev.opencode.hexcode.designsystem.components.HexLinkTile
import dev.opencode.hexcode.designsystem.components.HexProgress
import dev.opencode.hexcode.designsystem.components.HexProgressStep
import dev.opencode.hexcode.designsystem.components.HexSpinner
import dev.opencode.hexcode.designsystem.components.HexStatusLine
import dev.opencode.hexcode.designsystem.components.HexStatusTone
import dev.opencode.hexcode.designsystem.components.HexTileRow
import dev.opencode.hexcode.designsystem.components.HexTopBar
import dev.opencode.hexcode.session.AgentStatus
import dev.opencode.hexcode.session.NowLine
import dev.opencode.hexcode.session.PendingItem
import dev.opencode.hexcode.session.RefreshLabel
import dev.opencode.hexcode.session.SessionSetup
import dev.opencode.hexcode.session.StatusSummary
import dev.opencode.hexcode.session.SummaryLink
import dev.opencode.hexcode.session.SummaryLinks
import dev.opencode.hexcode.session.SummaryState
import dev.opencode.hexcode.session.SummaryText
import dev.opencode.hexcode.session.TodayState
import dev.opencode.hexcode.session.TodoItem
import dev.opencode.hexcode.session.TodoLabel
import dev.opencode.hexcode.session.TodoList
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
    status: AgentStatus?,
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
        // Today's session is being set up: the slot is empty.
        null -> HexTopBar(null, "", modifier, actions = actions)
    }
}

/**
 * The status area (`.fx-body.centred`): the status card, the fallback, or "Couldn't open today's session" with Try
 * again, and the Needs-you hint under the card, centred between the top bar and the dock whatever their length. While
 * setup runs it shows only the card saved for today's session, if there is one. Now's size steps down with its
 * effective length, and on down while the card doesn't fit ([NowSize]); then whole lines go from the end.
 */
@Composable
internal fun StatusArea(
    today: TodayState,
    now: Long,
    vm: HomeViewModel,
    modifier: Modifier = Modifier,
    /** The paired computer's address, which a tile for a `localhost` link shows and opens. */
    computer: String? = null,
    onOpenLink: (String) -> Unit = {},
) {
    val summary = today.summary
    val links = Links(computer, onOpenLink)
    val phase =
        when {
            today.pendingInput != null -> CardPhase.Waiting
            today.busySince != null -> CardPhase.Working
            else -> null
        }
    val work = Work(today.todos, today.running(now), today.you, vm::addToDraft, phase)
    val card = summary.summary as? StatusSummary.Card
    val start = card?.let { NowSize.forLines(it.now) } ?: NowSize.Display
    val count = card?.now?.size ?: 1
    when (today.setup) {
        // The card saved for today's session shows while setup runs.
        SessionSetup.Opening ->
            if (summary.summary != null) {
                CentredFit(start, count, modifier) { size, lines -> Summary(summary, now, vm, size, lines, links, work) }
            }
        is SessionSetup.Failed -> CentredFit(NowSize.Display, 1, modifier) { _, _ -> SetupFailed(today.setup.retrying, vm::retrySetup) }
        is SessionSetup.Ready ->
            CentredFit(start, count, modifier) { size, lines ->
                Summary(summary, now, vm, size, lines, links, work)
                today.pendingInput?.let { NeedsHint(it, onClick = {}) }
            }
    }
}

/**
 * [content] centred in the space it's given, or from the top when it's taller (it never scrolls). The whole card is
 * measured, at its real height, at the Now size from [start] down until it fits ([NowSize.fit]); at [NowSize.Fit],
 * whole lines of Now (of [nowCount]) go from the end until it fits, the last one kept ending in "…". Measured again
 * whenever the content or the user's text size changes.
 */
@Composable
private fun CentredFit(
    start: NowSize,
    nowCount: Int,
    modifier: Modifier,
    content: @Composable ColumnScope.(size: NowSize, nowLines: Int) -> Unit,
) {
    SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val room = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val measured = HashMap<Pair<NowSize, Int>, Placeable>()

        fun measure(
            size: NowSize,
            lines: Int,
        ): Placeable =
            measured.getOrPut(size to lines) {
                subcompose(size to lines) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { content(size, lines) }
                }.first().measure(loose)
            }
        val fitted = NowSize.fit(start) { measure(it, Int.MAX_VALUE).height <= room }
        var card = measure(fitted.size, Int.MAX_VALUE)
        if (!fitted.fits) {
            // The most lines of Now that fit, found by halving.
            var low = 1
            var high = nowCount.coerceAtLeast(1)
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (measure(NowSize.Fit, mid).height <= room) low = mid else high = mid - 1
            }
            card = measure(NowSize.Fit, low)
        }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else card.height
        layout(constraints.maxWidth, height) { card.place(0, ((height - card.height) / 2).coerceAtLeast(0)) }
    }
}

/** The status card, its fallback or just ↻, with the card's age. */
@Composable
private fun Summary(
    summary: SummaryState,
    now: Long,
    vm: HomeViewModel,
    size: NowSize,
    nowLines: Int,
    links: Links,
    work: Work,
) {
    val label = summary.label(now)
    val dim by animateFloatAsState(if (summary.loading) 0.35f else 1f, HexTheme.motion.fade(), label = "loading")
    when (val card = summary.summary) {
        is StatusSummary.Card ->
            Card(
                card.title,
                card.now,
                card.next,
                work,
                dim,
                label,
                vm::refreshSummary,
                size,
                nowLines,
                card.links,
                links,
                nextTappable = true,
            )
        is StatusSummary.Empty ->
            Card(
                card.title,
                listOf(NowLine(stringResource(R.string.home_summary_empty_now))),
                stringResource(R.string.home_summary_empty_next),
                work,
                dim,
                label,
                vm::refreshSummary,
            )
        is StatusSummary.Working ->
            Card(
                card.title,
                listOf(NowLine(stringResource(R.string.home_summary_working_now))),
                stringResource(R.string.home_summary_working_next),
                work,
                dim,
                label,
                vm::refreshSummary,
            )
        is StatusSummary.Fallback -> Fallback(card.text, dim, label, vm::refreshSummary)
        null -> Meta(label, vm::refreshSummary) {}
    }
}

/** The small label over the card, in an 18 dp line like the draft's, taller only when the user's text size needs it. */
@Composable
private fun Label(content: @Composable () -> Unit) {
    Box(Modifier.heightIn(min = 18.dp), contentAlignment = Alignment.CenterStart) { content() }
}

/**
 * What the card shows besides the summary: the agent's to-do list (no row when empty), how many subagents and shells
 * run besides it, the message just sent ([you], in place of Next), and where a tapped Next goes ([onNext]).
 */
private class Work(
    val todos: List<TodoItem>,
    val running: Int,
    val you: String? = null,
    val onNext: (String) -> Unit = {},
    /** Busy or waiting for the user: the label over Now says so (app-plan.md, Card label while working). */
    val phase: CardPhase? = null,
)

/** What the label over Now adds while the session isn't idle. */
private enum class CardPhase {
    /** "Still working · <title>", with the top bar's green arc. */
    Working,

    /** "Waiting for you · <title>", with the amber dot. */
    Waiting,
}

/** Where link tiles open: the paired [computer]'s address for `localhost` links, and [open] for a tap. */
private class Links(
    val computer: String?,
    val open: (String) -> Unit,
)

/**
 * The card: the task as a label, what it's doing now in large type, what's next, its link tiles, the agent's to-do
 * row, then Details and ↻. The texts keep their inline Markdown ([summaryText]).
 */
@Composable
private fun Card(
    title: String,
    now: List<NowLine>,
    next: String?,
    work: Work,
    dim: Float,
    label: RefreshLabel?,
    onRefresh: () -> Unit,
    size: NowSize = NowSize.Display,
    nowLines: Int = Int.MAX_VALUE,
    tiles: List<SummaryLink> = emptyList(),
    links: Links? = null,
    /** Next is the reply's suggestion, which a tap puts into the draft; the screen's own Next copy isn't. */
    nextTappable: Boolean = false,
) {
    val colors = HexTheme.colors
    Label { CardLabel(title, work.phase) }
    NowText(now, size, nowLines, Modifier.alpha(dim))
    val you = work.you
    when {
        // The message just sent stands in for Next until the card made after its run (Just sent); it isn't tappable.
        you != null ->
            Text(
                stringResource(R.string.home_summary_you, you),
                style = HexTheme.type.body,
                color = colors.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(dim),
            )
        next != null ->
            Text(
                summaryTextIn(stringResource(R.string.home_summary_next, SUMMARY_MARK), next),
                style = HexTheme.type.body,
                color = colors.text2,
                modifier =
                    Modifier
                        .alpha(dim)
                        .then(
                            // Next is what the user might say: a tap puts it into the draft, never sends it.
                            if (nextTappable) Modifier.clickable(role = Role.Button) { work.onNext(SummaryText.shown(next)) } else Modifier,
                        ),
            )
    }
    if (tiles.isNotEmpty() && links != null) {
        HexTileRow(Modifier.alpha(dim)) {
            tiles.forEach { link ->
                HexLinkTile(
                    label = link.label,
                    host = tileHost(link.url, links.computer),
                    kind = if (SummaryLinks.isLocal(link.url)) HexLinkKind.Local else HexLinkKind.Web,
                    accessibilityLabel = stringResource(R.string.home_link_open, link.label),
                    onClick = { links.open(link.url) },
                )
            }
        }
    }
    TodoRow(work.todos)
    // Details comes with M4; until then the link does nothing.
    Meta(label, onRefresh) { DetailsLink(work.running, onClick = {}) }
}

/**
 * The label over Now: the title, or while the session is busy "Still working · <title>" with the green arc, and while
 * it waits for an answer "Waiting for you · <title>" with the amber dot. The new card's plain title follows the run.
 */
@Composable
private fun CardLabel(
    title: String,
    phase: CardPhase?,
) {
    val colors = HexTheme.colors
    val style = HexTheme.type.caption
    val text =
        when (phase) {
            null -> summaryText(title)
            CardPhase.Working ->
                if (title.isBlank()) {
                    AnnotatedString(stringResource(R.string.home_summary_working_bare))
                } else {
                    summaryTextIn(stringResource(R.string.home_summary_working, SUMMARY_MARK), title)
                }
            CardPhase.Waiting ->
                if (title.isBlank()) {
                    AnnotatedString(stringResource(R.string.home_summary_waiting_bare))
                } else {
                    summaryTextIn(stringResource(R.string.home_summary_waiting, SUMMARY_MARK), title)
                }
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (phase) {
            CardPhase.Working -> HexSpinner(size = 12.dp, tint = colors.green)
            CardPhase.Waiting -> HexDot(HexDotTone.Amber)
            null -> Unit
        }
        Text(text, style = style, color = colors.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Now (app-plan.md, Formatting): plain lines are paragraphs 0.35 em apart; bullets a list with a hanging "•" in `text3`
 * and a 0.9 em indent, 0.15 em between items and 0.25 em above the list. Each line keeps its inline Markdown. Only the
 * first [kept] lines show, the last of them ending in "…" when some were left out to fit.
 */
@Composable
private fun NowText(
    lines: List<NowLine>,
    size: NowSize,
    kept: Int,
    modifier: Modifier = Modifier,
) {
    val type = HexTheme.type
    val style =
        when (size) {
            NowSize.Display -> type.display
            NowSize.Large -> type.nowLarge
            NowSize.Medium -> type.nowMedium
            NowSize.Small -> type.nowSmall
            NowSize.Fit -> type.nowFit
        }
    // Bold is bold at the two largest sizes, semibold below (`.md-b`).
    val bold = if (size <= NowSize.Large) FontWeight.Bold else FontWeight.SemiBold
    val shown = lines.take(kept.coerceAtLeast(1))
    val cut = shown.size < lines.size
    val em = with(LocalDensity.current) { style.fontSize.toDp() }
    Column(modifier) {
        shown.forEachIndexed { index, line ->
            val previous = shown.getOrNull(index - 1)
            val gap =
                when {
                    previous == null -> 0f
                    line.bullet && previous.bullet -> 0.15f
                    line.bullet -> 0.25f
                    else -> 0.35f
                }
            if (gap > 0f) Spacer(Modifier.height(em * gap))
            val text = summaryText(line.text, bold, suffix = if (cut && index == shown.lastIndex) "…" else "")
            if (line.bullet) {
                Row {
                    Text("•", style = style, color = HexTheme.colors.text3, modifier = Modifier.width(em * 0.9f))
                    Text(text, style = style, color = HexTheme.colors.text1, modifier = Modifier.weight(1f))
                }
            } else {
                Text(text, style = style, color = HexTheme.colors.text1)
            }
        }
    }
}

/**
 * The agent's to-do row (`HexProgress`): a segment per item, then "3 of 4 · <the item in progress>" or "All 4 done",
 * and "›". No row without a list. Tapping opens Details from M4; until then it does nothing.
 */
@Composable
private fun TodoRow(todos: List<TodoItem>) {
    val label = TodoList.label(todos) ?: return
    val text =
        when (label) {
            is TodoLabel.AllDone -> AnnotatedString(stringResource(R.string.home_todo_all_done, label.count))
            is TodoLabel.Current ->
                summaryTextIn(stringResource(R.string.home_todo_progress, label.position, label.count, SUMMARY_MARK), label.text)
        }
    HexProgress(
        todos.map {
            when (it.status) {
                TodoItem.Status.Pending -> HexProgressStep.Pending
                TodoItem.Status.InProgress -> HexProgressStep.Current
                TodoItem.Status.Completed -> HexProgressStep.Done
            }
        },
        text,
        onClick = {},
    )
}

/** "Details ›" (`.fx-det`) in `mono`, with a green dot and "N running · " in front while subagents or shells run. */
@Composable
private fun DetailsLink(
    running: Int,
    onClick: () -> Unit,
) {
    val colors = HexTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val color = if (pressed) colors.text1 else colors.text3
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .semantics(mergeDescendants = true) {}
                .clickable(source, null, role = Role.Button, onClick = onClick)
                .padding(vertical = 6.dp),
    ) {
        if (running > 0) HexDot(HexDotTone.Green)
        val text =
            (if (running > 0) stringResource(R.string.home_details_running, running) else "") + stringResource(R.string.home_details)
        Text(text, style = HexTheme.type.mono, color = color, maxLines = 1)
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
    Label { Text(stringResource(R.string.home_summary_fallback_title), style = HexTheme.type.caption, color = colors.text3) }
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
private fun SetupFailed(
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    Text(stringResource(R.string.home_setup_failed), style = HexTheme.type.display, color = HexTheme.colors.text1)
    HexButton(
        onClick = onRetry,
        text = stringResource(R.string.home_setup_retry),
        icon = HexIcons.refresh,
        size = HexButtonSize.Regular,
        loading = retrying,
    )
}

/** The amber hint (`.needs-hint`) while a question or approval waits: what it is, and its text. */
@Composable
private fun NeedsHint(
    input: PendingItem,
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
                .border(1.dp, colors.amberLine, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Icon(HexIcons.alert, null, tint = colors.amber, modifier = Modifier.size(HexTheme.size.icon))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                stringResource(if (input.kind == PendingItem.Kind.Approval) R.string.home_needs_approval else R.string.home_needs_input),
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
