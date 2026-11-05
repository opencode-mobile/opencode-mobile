package dev.opencode.hexcode.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexButton
import dev.opencode.hexcode.designsystem.components.HexButtonStyle
import dev.opencode.hexcode.designsystem.components.HexChunk
import dev.opencode.hexcode.designsystem.components.HexChunkItem
import dev.opencode.hexcode.designsystem.components.HexChunkKind
import dev.opencode.hexcode.designsystem.components.HexChunkRow
import dev.opencode.hexcode.designsystem.components.HexChunkRowState
import dev.opencode.hexcode.designsystem.components.HexDot
import dev.opencode.hexcode.designsystem.components.HexDotTone
import dev.opencode.hexcode.designsystem.components.HexRoundButton
import dev.opencode.hexcode.designsystem.components.HexSpinner
import dev.opencode.hexcode.designsystem.components.HexTalkButton
import dev.opencode.hexcode.designsystem.components.HexTalkRelease
import dev.opencode.hexcode.designsystem.components.HexTalkState
import dev.opencode.hexcode.designsystem.components.HexToastHost
import dev.opencode.hexcode.designsystem.components.HexToastMessage
import dev.opencode.hexcode.designsystem.components.HexToastTone
import dev.opencode.hexcode.session.AgentStatus
import dev.opencode.voiceengine.VoiceEngine
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Home: the top bar's state slot and settings, today's status card (or the draft while there is one, as the
 * prototype's `viewFocus`), the waiting line, and the talk button. What you dictate stays in the draft; there is no
 * Send yet.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    speech: VoiceEngine.ModelState,
    onSettings: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var micAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var micOff by remember { mutableStateOf(false) }
    val askMic =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            micAllowed = it
            micOff = !it
        }
    val ready = speech == VoiceEngine.ModelState.Ready
    val edge = HexTheme.space.screenHome
    Box(
        Modifier
            .fillMaxSize()
            .background(HexTheme.colors.background)
            .systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 10.dp, bottom = 6.dp)) {
            val today = state.today
            val now = rememberNow(if (today.agent is AgentStatus.Working) 1_000 else 15_000)
            HomeTopBar(today.agent, now, Modifier.padding(start = edge, end = edge - 6.dp)) {
                HexRoundButton(HexIcons.gear, stringResource(R.string.home_settings), onClick = onSettings)
            }
            // Status and draft start at the same line, 26 % down the space between the top bar and the dock (the
            // prototype's `.fx-sp`), so switching between them, or the waiting line appearing, moves nothing.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val hang = maxHeight * 0.26f
                Column(Modifier.fillMaxSize()) {
                    Crossfade(
                        state.draft.isEmpty,
                        Modifier.weight(1f).fillMaxWidth(),
                        HexTheme.motion.fade(),
                        label = "status-draft",
                    ) { empty ->
                        Column(Modifier.fillMaxSize().padding(horizontal = edge)) {
                            Spacer(Modifier.height(hang))
                            if (empty) StatusArea(today, now, vm) else DraftView(state, vm)
                        }
                    }
                    if (state.draft.isEmpty && today.waiting > 0) {
                        WaitingLine(today.waiting, onClick = {}, modifier = Modifier.padding(start = edge, end = edge, top = 10.dp))
                    }
                }
            }
            val label =
                when {
                    // A download shows its share; loading the model after it is "Getting speech ready", with no share.
                    speech is VoiceEngine.ModelState.Preparing && speech.fraction < 1.0 ->
                        stringResource(R.string.talk_preparing, (speech.fraction * 100).roundToInt())
                    speech is VoiceEngine.ModelState.Failed -> stringResource(R.string.talk_failed)
                    !ready -> stringResource(R.string.talk_waiting)
                    state.draft.selected != null -> stringResource(R.string.talk_rerecord)
                    !state.draft.isEmpty -> stringResource(R.string.talk_more)
                    else -> stringResource(R.string.talk_hold)
                }
            HexTalkButton(
                state =
                    when (state.talk) {
                        TalkMode.Holding -> HexTalkState.Holding
                        TalkMode.Locked -> HexTalkState.Locked
                        TalkMode.Idle -> if (state.draft.isEmpty) HexTalkState.Idle else HexTalkState.Draft
                    },
                label = label,
                accessibilityLabel =
                    when (state.talk) {
                        TalkMode.Locked -> stringResource(R.string.talk_a11y_locked)
                        TalkMode.Holding -> stringResource(R.string.talk_a11y_recording)
                        TalkMode.Idle -> label
                    },
                enabled = ready,
                level = { vm.level },
                onPress = { if (micAllowed) vm.press() else askMic.launch(Manifest.permission.RECORD_AUDIO) },
                onRelease = { how -> vm.release(how.toTalkRelease()) },
                onTapLocked = vm::tapLocked,
                modifier = Modifier.padding(start = edge, end = edge, top = 12.dp),
            )
        }
        val micOffText = stringResource(R.string.home_mic_off)
        val deleted = stringResource(R.string.home_chunk_deleted)
        val dropped = stringResource(R.string.home_take_dropped)
        val newDayTitle = stringResource(R.string.home_newday_title)
        val newDayDetail = stringResource(R.string.home_newday_detail)
        val undo = stringResource(R.string.common_undo)
        val notice =
            remember(state.notice, micOff) {
                when (state.notice) {
                    HomeNotice.ChunkDeleted ->
                        HexToastMessage(deleted, HexToastTone.Undo, actionLabel = undo, actionIcon = HexIcons.undo, durationMs = 3_500)
                    HomeNotice.TakeDropped -> HexToastMessage(dropped, HexToastTone.Undo)
                    HomeNotice.NewDay -> HexToastMessage(newDayTitle, HexToastTone.Sun, newDayDetail, durationMs = NEW_DAY_NOTICE_MS)
                    null -> if (micOff) HexToastMessage(micOffText, HexToastTone.Info) else null
                }
            }
        HexToastHost(
            message = notice,
            onDismissed = {
                vm.noticeShown()
                micOff = false
            },
            onAction = { vm.undoDelete() },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
    }
}

/** How long the new-day notice shows (app-plan.md, The daily session). */
private const val NEW_DAY_NOTICE_MS = 3_600L

private fun HexTalkRelease.toTalkRelease(): TalkRelease =
    when (this) {
        HexTalkRelease.Lock -> TalkRelease.Lock
        HexTalkRelease.Drop -> TalkRelease.Drop
        HexTalkRelease.End -> TalkRelease.End
        HexTalkRelease.Tap -> TalkRelease.Tap
    }

@Composable
private fun DraftView(
    state: HomeUiState,
    vm: HomeViewModel,
) {
    val draft = state.draft
    DraftLabel(state)
    Box(Modifier.padding(top = 14.dp)) {
        if (draft.isLong) ChunkList(draft, vm::selectChunk) else DraftText(draft, vm::selectChunk)
    }
    if (!draft.isListening) DraftActions(draft, vm)
}

/** "● Recording 0:04", "Transcribing on this phone…", or "Draft · 2 chunks · 0:12". */
@Composable
private fun DraftLabel(state: HomeUiState) {
    val draft = state.draft
    val color = HexTheme.colors.text3
    val style = HexTheme.type.caption
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(draft.isListening) {
        while (draft.isListening) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    AnimatedContent(
        targetState =
            if (draft.isListening) {
                0
            } else if (draft.isTranscribing) {
                1
            } else {
                2
            },
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "draft-label",
    ) { kind ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.height(18.dp).semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            when (kind) {
                0 -> {
                    HexDot(HexDotTone.Red)
                    val elapsed = clock(now - state.takeStartedAt)
                    Text(
                        draft.rerecording?.let { stringResource(R.string.draft_rerecording, it + 1, elapsed) }
                            ?: stringResource(R.string.draft_recording, elapsed),
                        style = style,
                        color = color,
                    )
                }
                1 -> {
                    HexSpinner(size = HexTheme.size.iconSmall)
                    Text(stringResource(R.string.draft_transcribing), style = style, color = color)
                }
                else ->
                    Text(
                        listOf(
                            stringResource(R.string.draft_title),
                            if (draft.chunks.size == 1) {
                                stringResource(R.string.draft_one_chunk)
                            } else {
                                stringResource(R.string.draft_chunks, draft.chunks.size)
                            },
                            clock(draft.durationMs),
                        ).joinToString(" · "),
                        style = style,
                        color = color,
                    )
            }
        }
    }
}

/** Running text, at most a few chunks: each chunk can be tapped once it has text. */
@Composable
private fun DraftText(
    draft: Draft,
    onSelect: (Int) -> Unit,
) {
    val listening = stringResource(R.string.draft_listening)
    val transcribing = stringResource(R.string.draft_transcribing_chunk)
    HexChunk(
        chunks =
            draft.shown.map { chunk ->
                when (val s = chunk.state) {
                    ChunkState.Listening -> HexChunkItem(listening, HexChunkKind.Listening)
                    ChunkState.Transcribing -> HexChunkItem(transcribing, HexChunkKind.Transcribing)
                    is ChunkState.Text -> HexChunkItem(s.text, HexChunkKind.Text)
                }
            },
        selected = draft.selected,
        recording = draft.isListening,
        onSelect = onSelect,
        hint = if (draft.isListening && draft.chunks.isEmpty()) stringResource(R.string.draft_first_take_hint) else null,
    )
}

/** A long draft: one row per chunk with its length, at most eight rows, earlier ones folded away. */
@Composable
private fun ChunkList(
    draft: Draft,
    onSelect: (Int) -> Unit,
) {
    val shown = draft.shown
    var showEarlier by remember { mutableStateOf(false) }
    val start = if (showEarlier) 0 else (shown.size - Draft.MAX_ROWS).coerceAtLeast(0)
    // The rows reach 6 pt past the text edge on each side, so their text lines up with the label above.
    Column(Modifier.bleed(6.dp)) {
        if (start > 0) {
            Text(
                stringResource(R.string.draft_earlier, start, clock(shown.take(start).sumOf { it.durationMs })),
                style = HexTheme.type.footnote,
                color = HexTheme.colors.text3,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(HexTheme.radius.s))
                        .clickable { showEarlier = true }
                        .padding(4.dp),
            )
        }
        shown.drop(start).forEachIndexed { offset, chunk ->
            val index = start + offset
            val tappable = chunk.text != null && !draft.isListening
            HexChunkRow(
                number = index + 1,
                text =
                    when (chunk.state) {
                        ChunkState.Listening -> stringResource(R.string.draft_listening)
                        ChunkState.Transcribing -> stringResource(R.string.draft_transcribing_chunk)
                        is ChunkState.Text -> chunk.state.text
                    },
                duration = clock(chunk.durationMs),
                state =
                    when {
                        draft.selected == index -> HexChunkRowState.Selected
                        draft.isListening || chunk.text == null -> HexChunkRowState.Past
                        draft.selected != null -> HexChunkRowState.Dim
                        else -> HexChunkRowState.Normal
                    },
                onClick = if (tappable) ({ onSelect(index) }) else null,
            )
        }
    }
}

/** With a chunk selected: Delete, Re-record, deselect. Otherwise ✕ empties the draft. */
@Composable
private fun DraftActions(
    draft: Draft,
    vm: HomeViewModel,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
    ) {
        val selected = draft.selected
        if (selected != null) {
            Text(stringResource(R.string.draft_chunk_label, selected + 1), style = HexTheme.type.caption, color = HexTheme.colors.text2)
            Spacer(Modifier.weight(1f))
            HexButton(
                onClick = vm::deleteChunk,
                text = stringResource(R.string.draft_delete),
                icon = HexIcons.trash,
                style = HexButtonStyle.Danger,
            )
            HexButton(onClick = vm::rerecord, text = stringResource(R.string.draft_rerecord), icon = HexIcons.mic)
            HexButton(onClick = vm::deselectChunk, icon = HexIcons.x, contentDescription = stringResource(R.string.draft_deselect))
        } else {
            HexButton(
                onClick = vm::clearDraft,
                icon = HexIcons.x,
                contentDescription = stringResource(R.string.draft_clear),
                enabled = draft.isSettled,
            )
        }
    }
}

/** Widens what it modifies by [each] on both sides, past its parent's padding. */
private fun Modifier.bleed(each: Dp): Modifier =
    layout { measurable, constraints ->
        val extra = each.roundToPx() * 2
        val placeable =
            measurable.measure(
                constraints.copy(
                    minWidth = (constraints.minWidth + extra).coerceAtMost(constraints.maxWidth + extra),
                    maxWidth = constraints.maxWidth + extra,
                ),
            )
        layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
    }

/** 0:04, 1:12. */
private fun clock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
