package dev.opencode.hexcode.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.hexcode.R
import dev.opencode.hexcode.ui.HexEdge
import dev.opencode.hexcode.ui.HexSpinner
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.voiceengine.VoiceEngine
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Home, until it talks to the agent: the top bar's state slot and settings, the draft, and the talk button. What you
 * dictate stays in the draft; there is no Send yet.
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
    Box(
        Modifier
            .fillMaxSize()
            .background(openCodeColor("background-base"))
            .systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onSettings)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val hang = maxHeight * 0.26f
                // The draft hangs from a fixed line about a third down and grows downward, so nothing above it moves.
                Column(Modifier.padding(horizontal = HexEdge)) {
                    Spacer(Modifier.height(hang))
                    if (state.draft.isEmpty) EmptyHome() else DraftView(state, vm)
                }
            }
            val label =
                when {
                    speech is VoiceEngine.ModelState.Preparing ->
                        stringResource(R.string.talk_preparing, (speech.fraction * 100).roundToInt())
                    speech is VoiceEngine.ModelState.Failed -> stringResource(R.string.talk_failed)
                    !ready -> stringResource(R.string.talk_waiting)
                    state.draft.selected != null -> stringResource(R.string.talk_rerecord)
                    !state.draft.isEmpty -> stringResource(R.string.talk_more)
                    else -> stringResource(R.string.talk_hold)
                }
            TalkButton(
                mode = state.talk,
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
                onRelease = vm::release,
                onTapLocked = vm::tapLocked,
                modifier = Modifier.padding(start = HexEdge, end = HexEdge, top = 8.dp, bottom = 10.dp),
            )
        }
        val micOffText = stringResource(R.string.home_mic_off)
        val deleted = stringResource(R.string.home_chunk_deleted)
        val dropped = stringResource(R.string.home_take_dropped)
        val undo = stringResource(R.string.common_undo)
        OpenCodeSnackbarHost(
            message =
                when (state.notice) {
                    HomeNotice.ChunkDeleted -> OpenCodeSnackbarMessage(deleted, actionLabel = undo)
                    HomeNotice.TakeDropped -> OpenCodeSnackbarMessage(dropped)
                    null -> if (micOff) OpenCodeSnackbarMessage(micOffText) else null
                },
            onDismissed = {
                vm.noticeShown()
                micOff = false
            },
            onAction = { vm.undoDelete() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
        )
    }
}

/** Before the first take: what to do, in two lines. */
@Composable
private fun EmptyHome() {
    Text(
        stringResource(R.string.home_empty_title),
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        color = openCodeColor("text-strong"),
    )
    Text(
        stringResource(R.string.home_empty_body),
        style = MaterialTheme.typography.bodyLarge,
        color = openCodeColor("text-base"),
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** The agent's state on the left (Idle until the session is connected), settings on the right. */
@Composable
private fun TopBar(onSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = HexEdge + 4.dp, end = HexEdge - 4.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(openCodeColor("text-weaker")),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.home_state_idle),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = openCodeColor("text-strong"),
        )
        Spacer(Modifier.weight(1f))
        OpenCodeButton(
            OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Settings, stringResource(R.string.home_settings)),
            onClick = onSettings,
            contrast = OpenCodeButtonContrast.Low,
        )
    }
}

@Composable
private fun DraftView(
    state: HomeUiState,
    vm: HomeViewModel,
) {
    val draft = state.draft
    DraftLabel(state)
    Box(Modifier.padding(top = 8.dp)) {
        if (draft.isLong) ChunkList(draft, vm::selectChunk) else DraftText(draft, vm::selectChunk)
    }
    if (!draft.isListening) DraftActions(draft, vm)
}

/** "● Recording 0:04", "Transcribing on this phone…", or "Draft · 2 chunks · 0:12". */
@Composable
private fun DraftLabel(state: HomeUiState) {
    val draft = state.draft
    val weak = openCodeColor("text-weak")
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
            val style = MaterialTheme.typography.labelMedium
            when (kind) {
                0 -> {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(openCodeColor("error")))
                    val elapsed = clock(now - state.takeStartedAt)
                    Text(
                        draft.rerecording?.let { stringResource(R.string.draft_rerecording, it + 1, elapsed) }
                            ?: stringResource(R.string.draft_recording, elapsed),
                        style = style,
                        color = weak,
                    )
                }
                1 -> {
                    HexSpinner(size = 12.dp)
                    Text(stringResource(R.string.draft_transcribing), style = style, color = weak)
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
                        color = weak,
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
    val strong = openCodeColor("text-strong")
    val weak = openCodeColor("text-weak")
    val selectedFill = openCodeColor("surface-raised-base")
    val listening = stringResource(R.string.draft_listening)
    val transcribing = stringResource(R.string.draft_transcribing_chunk)
    val firstTakeHint = stringResource(R.string.draft_first_take_hint)
    val text =
        buildAnnotatedString {
            draft.shown.forEachIndexed { index, chunk ->
                if (index > 0) append(" ")
                when (val s = chunk.state) {
                    ChunkState.Listening -> withStyle(SpanStyle(color = weak)) { append(listening) }
                    ChunkState.Transcribing -> withStyle(SpanStyle(color = weak.copy(alpha = 0.6f))) { append(transcribing) }
                    is ChunkState.Text -> {
                        val dim = draft.isListening || (draft.selected != null && draft.selected != index)
                        val span =
                            SpanStyle(
                                color = if (dim) weak else strong,
                                background =
                                    if (draft.selected ==
                                        index
                                    ) {
                                        selectedFill
                                    } else {
                                        androidx.compose.ui.graphics.Color.Unspecified
                                    },
                            )
                        withLink(LinkAnnotation.Clickable("chunk-$index", TextLinkStyles(span)) { onSelect(index) }) { append(s.text) }
                    }
                }
            }
            if (draft.isListening && draft.chunks.isEmpty()) {
                withStyle(SpanStyle(color = weak, fontSize = 14.sp, fontWeight = FontWeight.Normal)) { append("\n" + firstTakeHint) }
            }
        }
    Text(
        text,
        style =
            MaterialTheme.typography.headlineSmall.copy(
                fontSize = 24.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.015).em,
            ),
        color = strong,
        maxLines = 8,
        overflow = TextOverflow.Ellipsis,
        // Three lines of room, so the draft never jumps as it fills.
        modifier = Modifier.heightIn(min = 101.dp),
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
    val weak = openCodeColor("text-weak")
    Column {
        if (start > 0) {
            Text(
                stringResource(R.string.draft_earlier, start, clock(shown.take(start).sumOf { it.durationMs })),
                style = MaterialTheme.typography.labelMedium,
                color = weak,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { showEarlier = true }
                        .padding(6.dp),
            )
        }
        shown.drop(start).forEachIndexed { offset, chunk ->
            val index = start + offset
            val selected = draft.selected == index
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(if (selected) openCodeColor("surface-raised-base") else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled = chunk.text != null && !draft.isListening) { onSelect(index) }
                        .padding(6.dp),
            ) {
                val mono = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                Text("${index + 1}", style = mono, color = weak, modifier = Modifier.width(16.dp).padding(top = 2.dp))
                Text(
                    when (chunk.state) {
                        ChunkState.Listening -> stringResource(R.string.draft_listening)
                        ChunkState.Transcribing -> stringResource(R.string.draft_transcribing_chunk)
                        is ChunkState.Text -> chunk.state.text
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (chunk.text == null || (draft.selected != null && !selected)) weak else openCodeColor("text-strong"),
                    maxLines = if (selected) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(clock(chunk.durationMs), style = mono, color = weak, modifier = Modifier.padding(top = 2.dp))
            }
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
            Text(
                stringResource(R.string.draft_chunk_label, selected + 1),
                style = MaterialTheme.typography.labelMedium,
                color = openCodeColor("text-weak"),
            )
            Spacer(Modifier.weight(1f))
            OpenCodeButton(
                OpenCodeButtonVariant.CompactText(stringResource(R.string.draft_delete)),
                onClick = vm::deleteChunk,
                contrast = OpenCodeButtonContrast.Destructive,
            )
            OpenCodeButton(
                OpenCodeButtonVariant.CompactText(stringResource(R.string.draft_rerecord)),
                onClick = vm::rerecord,
                contrast = OpenCodeButtonContrast.Low,
            )
            OpenCodeButton(
                OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Close, stringResource(R.string.draft_deselect)),
                onClick = vm::deselectChunk,
                contrast = OpenCodeButtonContrast.Low,
            )
        } else {
            OpenCodeButton(
                OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Close, stringResource(R.string.draft_clear)),
                onClick = vm::clearDraft,
                enabled = draft.isSettled,
                contrast = OpenCodeButtonContrast.Low,
            )
        }
    }
}

/** 0:04, 1:12. */
private fun clock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
