package dev.opencode.hexcode.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
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
import dev.opencode.hexcode.designsystem.components.HexHoldHint
import dev.opencode.hexcode.designsystem.components.HexIconButton
import dev.opencode.hexcode.designsystem.components.HexIconButtonTone
import dev.opencode.hexcode.designsystem.components.HexRoundButton
import dev.opencode.hexcode.designsystem.components.HexRow
import dev.opencode.hexcode.designsystem.components.HexSegment
import dev.opencode.hexcode.designsystem.components.HexSegmented
import dev.opencode.hexcode.designsystem.components.HexSegmentedSize
import dev.opencode.hexcode.designsystem.components.HexSheet
import dev.opencode.hexcode.designsystem.components.HexSheetAction
import dev.opencode.hexcode.designsystem.components.HexSpinner
import dev.opencode.hexcode.designsystem.components.HexTalkButton
import dev.opencode.hexcode.designsystem.components.HexTalkHold
import dev.opencode.hexcode.designsystem.components.HexTalkRelease
import dev.opencode.hexcode.designsystem.components.HexTalkState
import dev.opencode.hexcode.designsystem.components.HexThumbnail
import dev.opencode.hexcode.designsystem.components.HexThumbnailSize
import dev.opencode.hexcode.designsystem.components.HexToastHost
import dev.opencode.hexcode.designsystem.components.HexToastMessage
import dev.opencode.hexcode.designsystem.components.HexToastTone
import dev.opencode.hexcode.designsystem.components.rememberHexTalkHold
import dev.opencode.hexcode.session.AgentStatus
import dev.opencode.hexcode.session.InboxEntry
import kotlinx.coroutines.delay

/**
 * Home: the top bar's state slot and settings, today's status card (or the draft while there is one, as the
 * prototype's `viewFocus`), the waiting line, and the talk button. What you dictate stays in the draft, with any images
 * attached, until Send.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onSettings: () -> Unit,
    /** The paired computer's address, where link tiles for `localhost` open. */
    computer: String? = null,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var micAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var micOff by remember { mutableStateOf(false) }
    var waitingOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    val attach = rememberAttachPickers(state.draft.roomForImages, vm::attach)
    // Waiting to send closes once nothing is left in it (delivered, cancelled, or taken back elsewhere).
    LaunchedEffect(state.today.queued.isEmpty()) {
        if (state.today.queued.isEmpty()) waitingOpen = false
    }
    val askMic =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            micAllowed = it
            micOff = !it
            if (it) vm.homeShown()
        }
    // While home is on screen and the app in front, the microphone is ready for a press (not on: that's the press).
    LifecycleStartEffect(vm) {
        vm.homeShown()
        onStopOrDispose { vm.homeHidden() }
    }
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
            // The status card is centred between the top bar and the dock (`.fx-body.centred`); the draft starts at a
            // line 26 % down that space (the prototype's `.fx-sp`), so it grows downward while you talk.
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
                            if (empty) {
                                StatusArea(today, now, vm, computer = computer, onOpenLink = { openLink(context, it, computer) })
                            } else {
                                Spacer(Modifier.height(hang))
                                DraftView(state, vm)
                            }
                        }
                    }
                    if (state.draft.isEmpty && today.waiting > 0) {
                        WaitingLine(
                            today.waiting,
                            onClick = { waitingOpen = true },
                            modifier = Modifier.padding(start = edge, end = edge, top = 10.dp),
                        )
                    }
                }
            }
            val label =
                when {
                    state.draft.selected != null -> stringResource(R.string.talk_rerecord)
                    !state.draft.isEmpty -> stringResource(R.string.talk_more)
                    else -> stringResource(R.string.talk_hold)
                }
            // The dock (`.fx-dock`): attach, the talk button filling the rest, stop, 8 dp apart. Stop comes with M4:
            // until then a tap says it isn't in this version yet. It is dimmed, and does nothing, unless the agent is
            // working or needs input.
            val agent = today.agent
            val hold = rememberHexTalkHold()
            // The locked take's clock in the talk button ticks while it records.
            var lockedNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(state.talk) {
                while (state.talk == TalkMode.Locked) {
                    lockedNow = System.currentTimeMillis()
                    delay(250)
                }
            }
            // The hold hint fades in a quarter second into a hold, so a quick take never shows it.
            var hintShown by remember { mutableStateOf(false) }
            LaunchedEffect(hold.holding) {
                if (hold.holding) {
                    delay(HOLD_HINT_DELAY_MS)
                    hintShown = true
                } else {
                    hintShown = false
                }
            }
            Box(Modifier.padding(start = edge, end = edge, top = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val dock = HomeDock.of(state.talk)
                    DockSide(dock.left, stopLive = false, vm = vm, onAttach = { if (vm.openAttach()) attachOpen = true })
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
                                // The clock is part of the button's label, not an item of its own.
                                TalkMode.Locked -> stringResource(R.string.draft_recording, clock(lockedNow - state.takeStartedAt))
                                TalkMode.Holding -> stringResource(R.string.talk_a11y_recording)
                                TalkMode.Idle -> label
                            },
                        // Recording never waits for the speech model; only turning a take into text does.
                        enabled = true,
                        level = { vm.level },
                        onPress = {
                            if (micAllowed) {
                                vm.press()
                            } else {
                                askMic.launch(Manifest.permission.RECORD_AUDIO)
                                false
                            }
                        },
                        onRelease = { how -> vm.release(how.toTalkRelease()) },
                        onTapLocked = vm::tapLocked,
                        modifier = Modifier.weight(1f),
                        hold = hold,
                        clock = if (state.talk == TalkMode.Locked) clock(lockedNow - state.takeStartedAt) else null,
                        dropActionLabel = stringResource(R.string.home_hold_drop),
                        onDrop = vm::dropTakeNow,
                    )
                    DockSide(
                        dock.right,
                        stopLive = agent is AgentStatus.Working || agent == AgentStatus.NeedsInput,
                        vm = vm,
                        onAttach = { if (vm.openAttach()) attachOpen = true },
                    )
                }
                // 10 dp above the talk button, over where the waiting line and the draft actions are (both hidden while
                // a take records), so nothing moves.
                HoldHintLine(hintShown, hold, Modifier.align(Alignment.TopCenter))
            }
        }
        val micOffText = stringResource(R.string.home_mic_off)
        val deleted = stringResource(R.string.home_chunk_deleted)
        val dropped = stringResource(R.string.home_take_dropped)
        val newDayTitle = stringResource(R.string.home_newday_title)
        val newDayDetail = stringResource(R.string.home_newday_detail)
        val undo = stringResource(R.string.common_undo)
        val notYet = stringResource(R.string.settings_soon)
        val queued = stringResource(R.string.home_sent_queued)
        val queuedSub = stringResource(R.string.home_sent_queued_sub)
        val sentIdle = stringResource(R.string.home_sent_idle)
        val sendingNow = stringResource(R.string.home_sending_now)
        val sentNow = stringResource(R.string.home_sent_now)
        val unsent = stringResource(R.string.home_unsent)
        val unsentSub = stringResource(R.string.home_unsent_sub)
        val takenBack = stringResource(R.string.home_taken_back)
        val takenBackSub = stringResource(R.string.home_taken_back_sub)
        val sendFailed = stringResource(R.string.home_send_failed)
        val sendFailedSub = stringResource(R.string.home_send_failed_sub)
        val attachLimit = stringResource(R.string.home_attach_limit)
        val attachFailed = stringResource(R.string.home_attach_failed)
        val sentNoImages = stringResource(R.string.home_sent_no_images)
        val sentNoImagesSub = stringResource(R.string.home_sent_no_images_sub, state.imageInput?.model.orEmpty())
        val notice =
            remember(state.notice, state.noticeDetail, micOff, sentNoImagesSub) {
                when (state.notice) {
                    HomeNotice.ChunkDeleted ->
                        HexToastMessage(deleted, HexToastTone.Undo, actionLabel = undo, actionIcon = HexIcons.undo, durationMs = 3_500)
                    HomeNotice.TakeDropped -> HexToastMessage(dropped, HexToastTone.Undo)
                    HomeNotice.NewDay -> HexToastMessage(newDayTitle, HexToastTone.Sun, newDayDetail, durationMs = NEW_DAY_NOTICE_MS)
                    HomeNotice.NotInThisVersion -> HexToastMessage(notYet, HexToastTone.Info)
                    HomeNotice.MicUnavailable -> HexToastMessage(micOffText, HexToastTone.Info)
                    HomeNotice.Queued ->
                        HexToastMessage(
                            queued,
                            HexToastTone.Steer,
                            queuedSub,
                            actionLabel = undo,
                            actionIcon = HexIcons.undo,
                            durationMs = 3_200,
                        )
                    HomeNotice.SentIdle -> HexToastMessage(sentIdle, HexToastTone.Steer, quoted(state.noticeDetail), durationMs = 2_600)
                    HomeNotice.SendingNow ->
                        HexToastMessage(
                            sendingNow,
                            HexToastTone.Steer,
                            quoted(state.noticeDetail),
                            actionLabel = undo,
                            actionIcon = HexIcons.undo,
                            durationMs = Sending.HOLD_MS,
                        )
                    HomeNotice.SentNow -> HexToastMessage(sentNow, HexToastTone.Steer, quoted(state.noticeDetail), durationMs = 2_600)
                    HomeNotice.Unsent -> HexToastMessage(unsent, HexToastTone.Undo, unsentSub, durationMs = 2_200)
                    HomeNotice.TakenBack -> HexToastMessage(takenBack, HexToastTone.Undo, takenBackSub, durationMs = 2_200)
                    HomeNotice.SendFailed -> HexToastMessage(sendFailed, HexToastTone.Stop, sendFailedSub, durationMs = 3_200)
                    HomeNotice.AttachLimit -> HexToastMessage(attachLimit, HexToastTone.Info)
                    HomeNotice.AttachFailed -> HexToastMessage(attachFailed, HexToastTone.Stop)
                    HomeNotice.SentNoImages -> HexToastMessage(sentNoImages, HexToastTone.Info, sentNoImagesSub, durationMs = 3_200)
                    HomeNotice.QueuedNoImages ->
                        HexToastMessage(
                            sentNoImages,
                            HexToastTone.Info,
                            sentNoImagesSub,
                            actionLabel = undo,
                            actionIcon = HexIcons.undo,
                            durationMs = 3_200,
                        )
                    null -> if (micOff) HexToastMessage(micOffText, HexToastTone.Info) else null
                }
            }
        HexToastHost(
            message = notice,
            onDismissed = {
                vm.noticeShown()
                micOff = false
            },
            onAction = vm::noticeAction,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
        WaitingSheet(
            waitingOpen,
            state.today.queued,
            images = state.queuedImages,
            cancelling = state.cancelling,
            onCancel = vm::cancelQueued,
            onClose = { waitingOpen = false },
        )
        AttachSheet(
            attachOpen,
            onPhotos = {
                attachOpen = false
                attach.photos()
            },
            onCamera = {
                attachOpen = false
                attach.camera()
            },
            onClose = { attachOpen = false },
        )
    }
}

/** A message's text as a notice's second line: “in quotes”. */
private fun quoted(text: String?): String? = text?.let { "“$it”" }

/**
 * Waiting to send (app-plan.md, Sending): the queued messages, oldest first, each with Cancel, which shows a spinner
 * while the DELETE runs; the row leaves as soon as it works. A message delivered or cancelled elsewhere leaves the list
 * as the events say so.
 */
@Composable
private fun BoxScope.WaitingSheet(
    open: Boolean,
    queued: List<InboxEntry>,
    /** The phone's copy of the images each message took; a message from another device has none. */
    images: Map<String, List<DraftImage>>,
    cancelling: Set<String>,
    onCancel: (String) -> Unit,
    onClose: () -> Unit,
) {
    HexSheet(
        visible = open,
        onDismiss = onClose,
        title = stringResource(R.string.home_waiting_title),
        trailing = HexSheetAction(stringResource(R.string.common_close), onClose),
    ) {
        queued.forEach { item ->
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                Icon(
                    HexIcons.send,
                    null,
                    tint = HexTheme.colors.text3,
                    modifier = Modifier.padding(top = 3.dp).size(HexTheme.size.iconSmall),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.text.isNotEmpty()) {
                        Text(
                            item.text,
                            style = HexTheme.type.row,
                            color = HexTheme.colors.text1,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val own = images[item.id].orEmpty()
                    if (own.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            own.take(Draft.MAX_IMAGES).forEach { image ->
                                HexThumbnail(
                                    rememberThumbnail(image.file, HexThumbnailSize.Small.side),
                                    size = HexThumbnailSize.Small,
                                )
                            }
                        }
                    } else if (item.images > 0) {
                        Text(
                            pluralStringResource(R.plurals.home_waiting_images, item.images, item.images),
                            style = HexTheme.type.caption,
                            color = HexTheme.colors.text3,
                        )
                    }
                }
                HexButton(
                    onClick = { onCancel(item.id) },
                    text = stringResource(R.string.home_waiting_cancel),
                    loading = item.id in cancelling,
                )
            }
        }
    }
}

/** The hold hint, 10 dp above the talk button; it takes no room, so nothing moves when it shows. */
@Composable
private fun HoldHintLine(
    shown: Boolean,
    hold: HexTalkHold,
    modifier: Modifier,
) {
    AnimatedVisibility(
        shown,
        enter = fadeIn(HexTheme.motion.fade()),
        exit = fadeOut(HexTheme.motion.fade()),
        modifier =
            modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) { placeable.place(0, -placeable.height - 10.dp.roundToPx()) }
            },
    ) {
        HexHoldHint(
            lean = hold.lean,
            armed = hold.armed,
            slideLabel = stringResource(R.string.home_hold_slide_lock),
            releaseLockLabel = stringResource(R.string.home_hold_release_lock),
            releaseDropLabel = stringResource(R.string.home_hold_release_drop),
        )
    }
}

/**
 * One end of the dock, crossfading as it changes: attach and the agent's stop; or, while a take is locked, ✕ to drop
 * the take and ✓ to finish it (app-plan.md, Dictation › Locked take). Attach calls [onAttach]. Stop comes with M4; until
 * then a tap says so, and it is dimmed unless [stopLive].
 */
@Composable
private fun DockSide(
    button: DockButton,
    stopLive: Boolean,
    vm: HomeViewModel,
    onAttach: () -> Unit,
) {
    Crossfade(button, animationSpec = HexTheme.motion.fade(), label = "dock-side") { shown ->
        when (shown) {
            DockButton.Attach -> HexIconButton(HexIcons.clip, stringResource(R.string.home_attach), onClick = onAttach)
            DockButton.Stop ->
                HexIconButton(
                    HexIcons.stop,
                    stringResource(R.string.home_stop),
                    onClick = vm::notInThisVersion,
                    tone = HexIconButtonTone.Stop,
                    enabled = stopLive,
                )
            DockButton.DropTake -> HexIconButton(HexIcons.x, stringResource(R.string.home_locked_drop), onClick = vm::dropTakeNow)
            DockButton.Done ->
                HexIconButton(
                    HexIcons.check,
                    stringResource(R.string.home_locked_done),
                    onClick = vm::tapLocked,
                    tone = HexIconButtonTone.Strong,
                )
        }
    }
}

/** A hold this long shows the hold hint (app-plan.md, Dictation › Hold hint). */
private const val HOLD_HINT_DELAY_MS = 250L

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
private fun ColumnScope.DraftView(
    state: HomeUiState,
    vm: HomeViewModel,
) {
    val draft = state.draft
    DraftLabel(state)
    if (draft.images.isNotEmpty()) AttachRow(draft.images, state.imageInput?.takeIf { !it.sees }?.model, vm::removeImage)
    // The draft takes the room between its line and the actions above the dock (app-plan.md, Long drafts).
    DraftBody(draft, vm::selectChunk, Modifier.padding(top = 14.dp).weight(1f, fill = false))
    if (!draft.isListening) DraftActions(state, vm)
}

/**
 * The draft as running text as long as it can be (app-plan.md, Long drafts): its size steps down, `draft` (24), 21, 19,
 * 17, each measured as drawn against the room it has. More than 3 chunks, or 2 or more that don't fit at 17, are one
 * row per chunk; a single chunk too long even at 17 scrolls, kept at its newest words.
 */
@Composable
private fun DraftBody(
    draft: Draft,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val type = HexTheme.type
    val styles =
        DraftSize.entries.associateWith { size ->
            when (size) {
                DraftSize.Draft -> type.draft
                DraftSize.Medium -> type.nowMedium.copy(fontWeight = type.draft.fontWeight)
                DraftSize.Small -> type.nowSmall.copy(fontWeight = type.draft.fontWeight)
                DraftSize.Fit -> type.nowFit.copy(fontWeight = type.draft.fontWeight)
            }
        }
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val room = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val shape =
            DraftShape.choose(draft.shown.size, draft.characters) { size ->
                val measured = subcompose(size) { DraftText(draft, onSelect, styles.getValue(size)) }.first().measure(loose)
                measured.height <= room
            }
        val shown =
            subcompose("shown") {
                when (shape) {
                    is DraftShape.Running -> DraftText(draft, onSelect, styles.getValue(shape.size))
                    DraftShape.Scrolling -> ScrollingDraft(draft, onSelect, styles.getValue(DraftSize.Fit))
                    DraftShape.Rows -> ChunkList(draft, onSelect)
                }
            }.first().measure(constraints.copy(minHeight = 0))
        layout(shown.width, shown.height) { shown.place(0, 0) }
    }
}

/** A single chunk too long even at 17: it scrolls inside the draft's room, kept at its newest words as it grows. */
@Composable
private fun ScrollingDraft(
    draft: Draft,
    onSelect: (Int) -> Unit,
    style: TextStyle,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(draft.characters) { scroll.scrollTo(scroll.maxValue) }
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Box(Modifier.verticalScroll(scroll)) { DraftText(draft, onSelect, style) }
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
    val label = DraftLabel.of(draft, state.speech)
    AnimatedContent(
        // The download's share changes without animating the whole line.
        targetState = if (label is DraftLabel.SpeechDownloading) DraftLabel.SpeechDownloading(0) else label,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "draft-label",
    ) { kind ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.height(18.dp).semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            when (kind) {
                DraftLabel.Recording -> {
                    HexDot(HexDotTone.Red)
                    val elapsed = clock(now - state.takeStartedAt)
                    Text(
                        draft.rerecording?.let { stringResource(R.string.draft_rerecording, it + 1, elapsed) }
                            ?: stringResource(R.string.draft_recording, elapsed),
                        style = style,
                        color = color,
                    )
                }
                DraftLabel.Transcribing -> {
                    HexSpinner(size = HexTheme.size.iconSmall)
                    Text(stringResource(R.string.draft_transcribing), style = style, color = color)
                }
                DraftLabel.SpeechLoading -> {
                    HexSpinner(size = HexTheme.size.iconSmall)
                    Text(stringResource(R.string.home_speech_loading), style = style, color = color)
                }
                is DraftLabel.SpeechDownloading -> {
                    HexSpinner(size = HexTheme.size.iconSmall)
                    val percent = (label as? DraftLabel.SpeechDownloading)?.percent ?: 0
                    Text(stringResource(R.string.home_speech_downloading, percent), style = style, color = color)
                }
                DraftLabel.SpeechFailed ->
                    Text(stringResource(R.string.home_speech_failed), style = style, color = HexTheme.colors.amber)
                DraftLabel.Summary -> {
                    // What Send will do, at the end of the line: queue, steer (amber) or send now.
                    val steer = state.busy && state.sendMode == SendMode.Now
                    val caption =
                        stringResource(
                            when {
                                !state.busy -> R.string.home_mode_idle
                                steer -> R.string.home_mode_steer
                                else -> R.string.home_mode_queue
                            },
                        )
                    // A draft of only images has no chunks to count: "Draft · Sends now".
                    val parts =
                        listOf(stringResource(R.string.draft_title)) +
                            when (draft.chunks.size) {
                                0 -> emptyList()
                                1 -> listOf(stringResource(R.string.draft_one_chunk), clock(draft.durationMs))
                                else -> listOf(stringResource(R.string.draft_chunks, draft.chunks.size), clock(draft.durationMs))
                            }
                    Text(
                        buildAnnotatedString {
                            append(parts.joinToString(" · ", postfix = " · "))
                            if (steer) withStyle(SpanStyle(color = HexTheme.colors.amber)) { append(caption) } else append(caption)
                        },
                        style = style,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Running text, at most a few chunks: each chunk can be tapped once it has text. */
@Composable
private fun DraftText(
    draft: Draft,
    onSelect: (Int) -> Unit,
    style: TextStyle = HexTheme.type.draft,
) {
    val listening = stringResource(R.string.draft_listening)
    val transcribing = stringResource(R.string.draft_transcribing_chunk)
    val failed = stringResource(R.string.home_transcribe_failed)
    HexChunk(
        chunks =
            draft.shown.map { chunk ->
                when (val s = chunk.state) {
                    ChunkState.Listening -> HexChunkItem(listening, HexChunkKind.Listening)
                    // A take waiting for the speech model looks like one being transcribed; the label line says why.
                    ChunkState.Waiting, ChunkState.Transcribing -> HexChunkItem(transcribing, HexChunkKind.Transcribing)
                    is ChunkState.Text -> HexChunkItem(s.text, HexChunkKind.Text)
                    is ChunkState.Failed -> HexChunkItem(failed, HexChunkKind.Text)
                }
            },
        selected = draft.selected,
        recording = draft.isListening,
        onSelect = onSelect,
        hint = if (draft.isListening && draft.chunks.isEmpty()) stringResource(R.string.draft_first_take_hint) else null,
        maxLines = Int.MAX_VALUE,
        style = style,
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
            val tappable = chunk.selectable && !draft.isListening
            HexChunkRow(
                number = index + 1,
                text =
                    when (chunk.state) {
                        ChunkState.Listening -> stringResource(R.string.draft_listening)
                        ChunkState.Waiting, ChunkState.Transcribing -> stringResource(R.string.draft_transcribing_chunk)
                        is ChunkState.Text -> chunk.state.text
                        is ChunkState.Failed -> stringResource(R.string.home_transcribe_failed)
                    },
                duration = clock(chunk.durationMs),
                state =
                    when {
                        draft.selected == index -> HexChunkRowState.Selected
                        draft.isListening || !chunk.selectable -> HexChunkRowState.Past
                        draft.selected != null -> HexChunkRowState.Dim
                        else -> HexChunkRowState.Normal
                    },
                onClick = if (tappable) ({ onSelect(index) }) else null,
            )
        }
    }
}

/**
 * The draft's images (app-plan.md, Attach › Thumbnails on the draft): 40 dp squares, each with ✕ and a spinner while
 * it's prepared, then one caption: "2 images · go with your next message", or, for a model that can't see images
 * ([noVision] is its name), dimmed squares and "<Model> can't see images · they won't be sent".
 */
@Composable
private fun AttachRow(
    images: List<DraftImage>,
    noVision: String?,
    onRemove: (Long) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        val remove = stringResource(R.string.home_attach_remove)
        images.forEach { image ->
            HexThumbnail(
                rememberThumbnail(image.file, HexThumbnailSize.Regular.side),
                preparing = image.preparing,
                dimmed = noVision != null,
                onRemove = { onRemove(image.id) },
                removeLabel = remove,
            )
        }
        Text(
            if (noVision != null) {
                stringResource(R.string.home_attach_no_vision, noVision)
            } else {
                pluralStringResource(R.plurals.home_attach_caption, images.size, images.size)
            },
            style = HexTheme.type.caption,
            color = HexTheme.colors.text3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
    }
}

/** The Attach sheet (app-plan.md, Attach): Photos opens the system photo picker, Take photo the camera. */
@Composable
private fun BoxScope.AttachSheet(
    open: Boolean,
    onPhotos: () -> Unit,
    onCamera: () -> Unit,
    onClose: () -> Unit,
) {
    HexSheet(
        visible = open,
        onDismiss = onClose,
        title = stringResource(R.string.home_attach_title),
        trailing = HexSheetAction(stringResource(R.string.common_close), onClose),
    ) {
        HexRow(stringResource(R.string.home_attach_photos), icon = HexIcons.img, onClick = onPhotos)
        HexRow(stringResource(R.string.home_attach_camera), icon = HexIcons.scan, onClick = onCamera)
    }
}

/** Opens the photo picker or the camera; what they return goes to [onPicked] as `content://` URIs. */
private class AttachPickers(
    val photos: () -> Unit,
    val camera: () -> Unit,
)

/**
 * The system pickers (app-plan.md, Attach › Pickers): the Photo Picker, images only, up to [room] (one at a time when
 * only one fits, since the multiple picker takes two or more); and the camera, writing into the cache. Hex Code declares
 * CAMERA for pairing, and an app that declares it must hold it to open the camera, so it's asked for first.
 */
@Composable
private fun rememberAttachPickers(
    room: Int,
    onPicked: (List<String>) -> Unit,
): AttachPickers {
    val context = LocalContext.current
    val images = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    val many =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(room.coerceAtLeast(2))) { uris ->
            onPicked(uris.map { it.toString() })
        }
    val one =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { onPicked(listOf(it.toString())) } }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val uri = target
            target = null
            if (taken && uri != null) onPicked(listOf(uri))
        }
    val openCamera = {
        val uri = AndroidImagePreparer.cameraTarget(context)
        target = uri.toString()
        takePicture.launch(uri)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) openCamera() }
    return AttachPickers(
        photos = { if (room >= 2) many.launch(images) else one.launch(images) },
        camera = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                askCamera.launch(Manifest.permission.CAMERA)
            }
        },
    )
}

/** With a chunk selected: Delete, Re-record, deselect. Otherwise ✕ empties the draft. */
@Composable
private fun DraftActions(
    state: HomeUiState,
    vm: HomeViewModel,
) {
    val draft = state.draft
    val busy = state.busy
    val mode = state.sendMode
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
                enabled = draft.canClear,
            )
            if (draft.canRetry) {
                // Takes that couldn't be turned into text, with their audio kept.
                HexButton(onClick = vm::retryTakes, text = stringResource(R.string.home_take_retry), icon = HexIcons.refresh)
            }
            Spacer(Modifier.weight(1f))
            if (busy) {
                HexSegmented(
                    listOf(
                        HexSegment(SendMode.AfterTurn.name, stringResource(R.string.home_mode_after)),
                        HexSegment(SendMode.Now.name, stringResource(R.string.home_mode_now), warning = true),
                    ),
                    mode.name,
                    { vm.setSendMode(SendMode.valueOf(it)) },
                    size = HexSegmentedSize.Small,
                )
            }
            // Send waits for every chunk's text and every image: it reads "Transcribing" while a chunk is still on its
            // way, else "Preparing" while an image is.
            val transcribing = draft.isListening || draft.isTranscribing || draft.isWaiting
            val pending = transcribing || draft.isPreparing
            HexButton(
                onClick = vm::send,
                text =
                    stringResource(
                        when {
                            transcribing -> R.string.home_send_transcribing
                            draft.isPreparing -> R.string.home_send_preparing
                            else -> R.string.home_send
                        },
                    ),
                icon = if (pending) null else HexIcons.send,
                style = HexButtonStyle.Primary,
                enabled = state.canSend,
                iconAfter = true,
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
