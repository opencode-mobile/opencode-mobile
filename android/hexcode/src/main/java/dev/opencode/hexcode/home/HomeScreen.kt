package dev.opencode.hexcode.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.RoundedCorner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.hexcode.R
import dev.opencode.hexcode.app.ColdStart
import dev.opencode.hexcode.designsystem.FullSpeed
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexMotion
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
import dev.opencode.hexcode.designsystem.components.HexFileThumbnail
import dev.opencode.hexcode.designsystem.components.HexGoalLine
import dev.opencode.hexcode.designsystem.components.HexGoalLineState
import dev.opencode.hexcode.designsystem.components.HexHoldHint
import dev.opencode.hexcode.designsystem.components.HexIconButton
import dev.opencode.hexcode.designsystem.components.HexIconButtonTone
import dev.opencode.hexcode.designsystem.components.HexReader
import dev.opencode.hexcode.designsystem.components.HexReaderFoot
import dev.opencode.hexcode.designsystem.components.HexRoundButton
import dev.opencode.hexcode.designsystem.components.HexRow
import dev.opencode.hexcode.designsystem.components.HexSegment
import dev.opencode.hexcode.designsystem.components.HexSegmented
import dev.opencode.hexcode.designsystem.components.HexSegmentedSize
import dev.opencode.hexcode.designsystem.components.HexSheet
import dev.opencode.hexcode.designsystem.components.HexSheetAction
import dev.opencode.hexcode.designsystem.components.HexSpinner
import dev.opencode.hexcode.designsystem.components.HexStopEdge
import dev.opencode.hexcode.designsystem.components.HexStopWarning
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
import dev.opencode.hexcode.designsystem.rememberScreenReaderOn
import dev.opencode.hexcode.session.AgentStatus
import dev.opencode.hexcode.session.GoalStatus
import dev.opencode.hexcode.session.PromptFiles
import dev.opencode.hexcode.session.SessionSetup
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    /** The paired computer's name, for "Signed out of …" and the browser's errors. */
    serverName: String? = null,
    /** Signed out's Pair again. */
    onPairAgain: () -> Unit = {},
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var micAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var micOff by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    // A `localhost` tile's page, in the in-app browser over home.
    var browser by remember { mutableStateOf<BrowserPage?>(null) }
    // The reader: the whole draft ("Draft") or the message just sent ("You said").
    var reader by remember { mutableStateOf<ReaderOpen?>(null) }
    val attach = rememberAttachPickers(state.draft.room, vm::attach, vm::attachMedia, vm::attachFiles)
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
    // Say why and own answers need the microphone too: asked for on the press, as the talk button does.
    val micReady = {
        if (!micAllowed) askMic.launch(Manifest.permission.RECORD_AUDIO)
        micAllowed
    }
    val edge = HexTheme.space.screenHome
    val still = HexMotion.reduced
    StopHaptics(state)
    Box(Modifier.fillMaxSize().background(HexTheme.colors.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            // Behind an open sheet or the browser, nothing of home is reachable with TalkBack.
            val covered =
                attachOpen || state.detailsOpen || state.needs != null || state.goalSheet != null || browser != null || reader != null
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
                    // TalkBack lands on the top bar, then the talk button, then the rest (Accessibility › Focus order).
                    .semantics { isTraversalGroup = true }
                    .padding(top = 10.dp, bottom = 6.dp),
            ) {
                val today = state.today
                val now = rememberNow(if (today.agent is AgentStatus.Working) 1_000 else 15_000)
                HomeTopBar(
                    today.agent,
                    now,
                    Modifier.padding(start = edge, end = edge - 6.dp).semantics { traversalIndex = -2f },
                    onNeedsInput = { vm.openNeeds() },
                ) {
                    // ◎ is always there: green while the goal is active.
                    HexRoundButton(
                        HexIcons.target,
                        stringResource(R.string.goal_button),
                        onClick = vm::openGoal,
                        selected = today.goal?.status == GoalStatus.Active,
                    )
                    HexRoundButton(HexIcons.gear, stringResource(R.string.home_settings), onClick = onSettings)
                }
                // The goal line (`.goalline`): hidden while a take records, while there's a draft, and while an Ask shows.
                // Its height is always held, also while it's empty or hidden, so nothing under it moves when it fills in
                // (app-plan.md, Home › Cold start).
                val goalLine = today.goalLine.takeIf { today.setup is SessionSetup.Ready }
                Box(Modifier.padding(start = edge, end = edge, top = 2.dp)) {
                    HexGoalLine(
                        HexGoalLineState.Empty,
                        stringResource(R.string.goal_line_empty),
                        onClick = {},
                        modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                    )
                    // Not the column's own AnimatedVisibility: this one is in the slot's Box.
                    androidx.compose.animation.AnimatedVisibility(
                        goalLine != null && !state.recording && state.draft.isEmpty && today.ask == null,
                        enter = fadeIn(HexTheme.motion.fade()),
                        exit = fadeOut(HexTheme.motion.fade()),
                    ) {
                        if (goalLine != null) GoalLineRow(goalLine, today.goal, vm::openGoal)
                    }
                }
                // The status card and the whole draft block are centred between the top bar and the dock
                // (`.fx-body.centred`, app-plan.md, Long drafts).
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize()) {
                        Crossfade(
                            state.draft.isEmpty,
                            Modifier.weight(1f).fillMaxWidth(),
                            HexTheme.motion.fade(),
                            label = "status-draft",
                        ) { empty ->
                            Column(Modifier.fillMaxSize().padding(horizontal = edge)) {
                                if (empty) {
                                    // The card fades in when it arrives after the splash went (Cold start).
                                    StatusArea(
                                        today,
                                        now,
                                        vm,
                                        computer = computer,
                                        onOpenLink = { url, label -> openLink(context, url, label, computer) { browser = it } },
                                        serverName = serverName,
                                        onPairAgain = onPairAgain,
                                        onReadYou = { reader = ReaderOpen.YouSaid },
                                        modifier = Modifier.fadeInOnArrival(ColdStart.cardStage(today)),
                                    )
                                } else {
                                    // The whole draft block is centred between the top bar and the dock (Long drafts).
                                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                                        DraftView(state, vm, onReadAll = { reader = ReaderOpen.Draft })
                                    }
                                }
                            }
                        }
                        // The Ask message, between the status card and the waiting line; hidden while a take records or
                        // there's a draft, and back after.
                        val ask = today.ask
                        AnimatedVisibility(
                            ask != null && state.draft.isEmpty && !state.recording,
                            enter = fadeIn(HexTheme.motion.fade()),
                            exit = fadeOut(HexTheme.motion.fade()),
                        ) {
                            if (ask != null) AskMessageView(ask, vm, Modifier.padding(start = edge, end = edge, top = 10.dp))
                        }
                        if (state.draft.isEmpty && today.waiting > 0) {
                            WaitingLine(
                                today.waiting,
                                idle = today.busySince == null && today.pendingInput == null,
                                onClick = vm::openDetails,
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
                // The dock (`.fx-dock`): attach, the talk button filling the rest, stop, 8 dp apart. Stop needs a 3 s hold;
                // it is dimmed, and does nothing, unless the agent is working or needs input.
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
                // The dock grows with the text size up to 1.5×, then stays (app-plan.md, Accessibility › Text size).
                val density = LocalDensity.current
                val dockDensity = remember(density) { Density(density.density, density.fontScale.coerceAtMost(DOCK_FONT_CAP)) }
                CompositionLocalProvider(LocalDensity provides dockDensity) {
                    Box(Modifier.padding(start = edge, end = edge, top = 12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val dock = HomeDock.of(state.talk)
                            DockSide(dock.left, state, vm = vm, onAttach = { if (vm.openAttach()) attachOpen = true })
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
                                        // As a duration: "Recording, 3 seconds", not "0:03".
                                        TalkMode.Locked ->
                                            stringResource(
                                                R.string.talk_a11y_recording_for,
                                                spokenDuration(
                                                    lockedNow - state.takeStartedAt,
                                                ),
                                            )
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
                                modifier = Modifier.weight(1f).semantics { traversalIndex = -1f },
                                hold = hold,
                                clock = if (state.talk == TalkMode.Locked) clock(lockedNow - state.takeStartedAt) else null,
                                dropActionLabel = stringResource(R.string.home_hold_drop),
                                onDrop = vm::dropTakeNow,
                            )
                            DockSide(
                                dock.right,
                                state,
                                vm = vm,
                                onAttach = { if (vm.openAttach()) attachOpen = true },
                            )
                        }
                        // 10 dp above the talk button, over where the waiting line and the draft actions are (both hidden while
                        // a take records), so nothing moves.
                        HoldHintLine(hintShown, hold, Modifier.align(Alignment.TopCenter))
                    }
                }
            }
            val micOffText = stringResource(R.string.home_mic_off)
            val deleted = stringResource(R.string.home_chunk_deleted)
            val dropped = stringResource(R.string.home_take_dropped)
            val newDayTitle = stringResource(R.string.home_newday_title)
            val newDayDetail = stringResource(if (state.today.newDayGoal) R.string.home_newday_detail_goal else R.string.home_newday_detail)
            val goalNotices = goalNoticeTexts()
            val askCopied = stringResource(R.string.ask_copied)
            val newSession = stringResource(R.string.settings_new_session_done)
            val newSessionSub = stringResource(R.string.settings_new_session_done_sub)
            val paired = stringResource(R.string.server_paired)
            val pairedSub = stringResource(R.string.server_paired_sub, state.noticeDetail.orEmpty())
            val undo = stringResource(R.string.common_undo)
            val needsGone = stringResource(R.string.needs_gone)
            val needsGoneSub = stringResource(R.string.needs_gone_sub)
            val needsFailed = stringResource(R.string.needs_failed)
            val needsFailedSub = stringResource(R.string.needs_failed_sub)
            val stopTap = stringResource(R.string.stop_hold)
            val stopTapSub = stringResource(R.string.stop_hold_sub)
            val stopCancelled = stringResource(R.string.stop_cancelled)
            val stopCancelledSub = stringResource(R.string.stop_cancelled_sub)
            val stopped = stringResource(R.string.stop_done)
            val stoppedSub = stringResource(R.string.stop_done_sub)
            val stoppedWaiting = stringResource(R.string.stop_done_waiting)
            val nothing = stringResource(R.string.stop_nothing)
            val nothingSub = stringResource(R.string.stop_nothing_sub)
            val stopFailed = stringResource(R.string.stop_failed)
            val stopFailedSub = stringResource(R.string.stop_failed_sub)
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
            val fileFailed = stringResource(R.string.home_attach_file_failed)
            val uploadFailed = stringResource(R.string.home_attach_upload_failed, state.noticeDetail.orEmpty())
            val uploadFailedSub = stringResource(R.string.home_attach_upload_failed_sub)
            val sentNoImages = stringResource(R.string.home_sent_no_images)
            val sentNoImagesSub = stringResource(R.string.home_sent_no_images_sub, state.imageInput?.model.orEmpty())
            // A message of only images or files says how many in place of its text.
            val messageDetail = quoted(state.noticeDetail) ?: attachmentCount(state.noticeImages, state.noticeFiles)
            val notice =
                remember(state.notice, messageDetail, micOff, sentNoImagesSub, newDayDetail, pairedSub, uploadFailed) {
                    when (state.notice) {
                        HomeNotice.ChunkDeleted ->
                            HexToastMessage(deleted, HexToastTone.Undo, actionLabel = undo, actionIcon = HexIcons.undo, durationMs = 3_500)
                        HomeNotice.TakeDropped -> HexToastMessage(dropped, HexToastTone.Undo)
                        HomeNotice.NewDay -> HexToastMessage(newDayTitle, HexToastTone.Sun, newDayDetail, durationMs = NEW_DAY_NOTICE_MS)
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
                        HomeNotice.SentIdle -> HexToastMessage(sentIdle, HexToastTone.Steer, messageDetail, durationMs = 2_600)
                        HomeNotice.SendingNow ->
                            HexToastMessage(
                                sendingNow,
                                HexToastTone.Steer,
                                messageDetail,
                                actionLabel = undo,
                                actionIcon = HexIcons.undo,
                                durationMs = Sending.HOLD_MS,
                                fixedDuration = true,
                            )
                        HomeNotice.SentNow -> HexToastMessage(sentNow, HexToastTone.Steer, messageDetail, durationMs = 2_600)
                        HomeNotice.Unsent -> HexToastMessage(unsent, HexToastTone.Undo, unsentSub, durationMs = 2_200)
                        HomeNotice.TakenBack -> HexToastMessage(takenBack, HexToastTone.Undo, takenBackSub, durationMs = 2_200)
                        HomeNotice.SendFailed -> HexToastMessage(sendFailed, HexToastTone.Stop, sendFailedSub, durationMs = 3_200)
                        HomeNotice.AttachLimit -> HexToastMessage(attachLimit, HexToastTone.Info)
                        HomeNotice.AttachFailed -> HexToastMessage(attachFailed, HexToastTone.Stop)
                        HomeNotice.FileFailed -> HexToastMessage(fileFailed, HexToastTone.Stop)
                        HomeNotice.UploadFailed -> HexToastMessage(uploadFailed, HexToastTone.Stop, uploadFailedSub, durationMs = 3_200)
                        HomeNotice.SentNoImages -> HexToastMessage(sentNoImages, HexToastTone.Info, sentNoImagesSub, durationMs = 2_600)
                        HomeNotice.QueuedNoImages ->
                            HexToastMessage(
                                sentNoImages,
                                HexToastTone.Info,
                                sentNoImagesSub,
                                actionLabel = undo,
                                actionIcon = HexIcons.undo,
                                durationMs = 3_200,
                            )
                        HomeNotice.NeedsGone -> HexToastMessage(needsGone, HexToastTone.Info, needsGoneSub)
                        HomeNotice.NeedsFailed -> HexToastMessage(needsFailed, HexToastTone.Stop, needsFailedSub)
                        HomeNotice.StopTap -> HexToastMessage(stopTap, HexToastTone.Stop, stopTapSub)
                        HomeNotice.StopCancelled -> HexToastMessage(stopCancelled, HexToastTone.Info, stopCancelledSub, durationMs = 2_200)
                        HomeNotice.Stopped -> HexToastMessage(stopped, HexToastTone.Stop, stoppedSub, durationMs = 3_200)
                        HomeNotice.StoppedWaiting -> HexToastMessage(stopped, HexToastTone.Stop, stoppedWaiting, durationMs = 3_200)
                        HomeNotice.NothingToStop -> HexToastMessage(nothing, HexToastTone.Info, nothingSub)
                        HomeNotice.StopFailed -> HexToastMessage(stopFailed, HexToastTone.Stop, stopFailedSub)
                        HomeNotice.AskCopied -> HexToastMessage(askCopied, HexToastTone.Steer, durationMs = 1_600)
                        HomeNotice.NewSession -> HexToastMessage(newSession, HexToastTone.Sun, newSessionSub, durationMs = 2_600)
                        HomeNotice.PairedAgain -> HexToastMessage(paired, HexToastTone.Steer, pairedSub, durationMs = 2_600)
                        HomeNotice.GoalSetIdle,
                        HomeNotice.GoalSetBusy,
                        HomeNotice.GoalReplacedIdle,
                        HomeNotice.GoalReplacedBusy,
                        HomeNotice.GoalPaused,
                        HomeNotice.GoalResumed,
                        HomeNotice.GoalCleared,
                        HomeNotice.GoalClearedBusy,
                        HomeNotice.GoalStale,
                        HomeNotice.GoalFailed,
                        HomeNotice.GoalRefusedWord,
                        HomeNotice.GoalRefusedLong,
                        -> goalNotices.of(state.notice)
                        null -> if (micOff) HexToastMessage(micOffText, HexToastTone.Info) else null
                    }
                }
            AttachSheet(
                attachOpen,
                filesAvailable = state.filesAvailable != false,
                onPhotos = {
                    attachOpen = false
                    attach.photos()
                },
                onCamera = {
                    attachOpen = false
                    attach.camera()
                },
                onFiles = {
                    attachOpen = false
                    attach.files()
                },
                onClose = { attachOpen = false },
            )
            DetailsSheet(state, vm)
            DraftReader(reader, state, vm, onClose = { reader = null })
            NeedsSheets(state, vm, micReady)
            GoalSheetView(state, vm, micReady)
            // Notices and the stop warning show over any open sheet.
            HexToastHost(
                message = notice,
                onDismissed = {
                    vm.noticeShown()
                    micOff = false
                },
                onAction = vm::noticeAction,
                // Toasts are hidden while Stop is held.
                // 12 dp under the top bar (10 dp above it, 46 dp tall), so it never covers or touches the bar.
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 68.dp).alpha(if (state.stopHolding) 0f else 1f),
            )
            // Stop's countdown card, under the top bar, while the button is held.
            AnimatedVisibility(
                state.stopHolding,
                enter = fadeIn(HexTheme.motion.fade()),
                exit = fadeOut(HexTheme.motion.fade()),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp, start = 12.dp, end = 12.dp),
            ) {
                StopCountdown(state.stopHeldAt, still)
            }
        }
        // The red edge round the whole screen while Stop is held, over the bars too.
        if (state.stopHolding) HexStopEdge(corner = screenCorner(), reduceMotion = still)
        // The in-app browser, full screen over home; it fades in (app-plan.md, Motion and Reduce Motion).
        AnimatedVisibility(browser != null, enter = fadeIn(HexTheme.motion.fade()), exit = fadeOut(HexTheme.motion.fade())) {
            val page = browser
            if (page != null) InAppBrowser(page, serverName ?: stringResource(R.string.server_this_computer), onClose = { browser = null })
        }
    }
}

/**
 * The warning's ring, bar and count over the 3 s from [heldAt] (the view model's clock: the wall clock), redrawn every
 * frame while it shows.
 */
@Composable
private fun StopCountdown(
    heldAt: Long,
    still: Boolean,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(heldAt) {
        while (true) {
            withFrameMillis { now = System.currentTimeMillis() }
        }
    }
    val progress = ((now - heldAt).toFloat() / STOP_HOLD_MS).coerceIn(0f, 1f)
    val count = StopCount.left(now - heldAt)
    HexStopWarning(
        progress = progress,
        count = count,
        title = stringResource(R.string.stop_warning, count),
        detail = stringResource(R.string.stop_warning_sub),
        reduceMotion = still,
    )
}

/** Stop's haptics: light at the press, a tick at each second, and a success one when the 3 s are reached. */
@Composable
private fun StopHaptics(state: HomeUiState) {
    val view = LocalView.current
    LaunchedEffect(state.stopHolding, state.stopHeldAt) {
        if (!state.stopHolding) return@LaunchedEffect
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        repeat(2) {
            delay(1_000)
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    LaunchedEffect(state.stopping) {
        if (state.stopping) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
            )
        }
    }
}

/** The screen's corner radius, for the stop edge; square where the system doesn't say (before Android 12). */
@Composable
private fun screenCorner(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0.dp
    val radius = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
    return with(density) { radius.toDp() }
}

/** A message's text as a notice's second line: “in quotes”. */
private fun quoted(text: String?): String? = text?.takeIf { it.isNotBlank() }?.let { "“$it”" }

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
            // Fade only with Reduce Motion: it doesn't follow the drag.
            lean = if (HexMotion.reduced) 0f else hold.lean,
            armed = hold.armed,
            slideLabel = stringResource(R.string.home_hold_slide_lock),
            releaseLockLabel = stringResource(R.string.home_hold_release_lock),
            releaseDropLabel = stringResource(R.string.home_hold_release_drop),
        )
    }
}

/**
 * One end of the dock, crossfading as it changes: attach and the agent's stop; or, while a take is locked, ✕ to drop
 * the take and ✓ to finish it (app-plan.md, Dictation › Locked take). Attach calls [onAttach]. Stop needs a 3 s hold
 * (app-plan.md, Stop), and is dimmed, doing nothing, unless the agent works or needs input.
 */
@Composable
private fun DockSide(
    button: DockButton,
    state: HomeUiState,
    vm: HomeViewModel,
    onAttach: () -> Unit,
) {
    Crossfade(button, animationSpec = HexTheme.motion.fade(), label = "dock-side") { shown ->
        when (shown) {
            DockButton.Attach -> HexIconButton(HexIcons.clip, stringResource(R.string.home_attach), onClick = onAttach)
            DockButton.Stop -> StopButton(state, vm)
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

/**
 * The stop button and its 3 s hold: the ring fills while it's held; lifting the finger before the end, or sliding it
 * more than 44 dp off the button, cancels (app-plan.md, Stop › Cancel). The view model times the hold.
 */
@Composable
private fun StopButton(
    state: HomeUiState,
    vm: HomeViewModel,
) {
    val live = state.stopLive
    val ring = remember { Animatable(0f) }
    LaunchedEffect(state.stopHolding) {
        if (state.stopHolding) {
            ring.snapTo(0f)
            // The ring carries the time, so it fills with Reduce Motion too.
            withContext(FullSpeed) { ring.animateTo(1f, tween(STOP_HOLD_MS.toInt(), easing = LinearEasing)) }
        } else {
            ring.snapTo(0f)
        }
    }
    val slack = with(LocalDensity.current) { STOP_SLIDE_OFF.toPx() }
    val context = LocalContext.current
    val label = stringResource(if (live) R.string.home_stop else R.string.stop_dimmed)
    val title = stringResource(R.string.stop_confirm_title)
    val body = stringResource(R.string.stop_confirm_body)
    val stop = stringResource(R.string.stop_confirm_stop)
    val keep = stringResource(R.string.stop_confirm_keep)
    HexIconButton(
        HexIcons.stop,
        stringResource(R.string.home_stop),
        onClick = null,
        tone = HexIconButtonTone.Stop,
        enabled = live,
        hold = if (state.stopHolding) ring.value else null,
        modifier =
            Modifier
                // A screen reader can't hold for 3 s: activate asks first, which keeps the hold's safety, then runs
                // the same steps (app-plan.md, Accessibility › Stop). Dimmed, it says so and has no action.
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    if (live) {
                        onClick {
                            android.app.AlertDialog
                                .Builder(context)
                                .setTitle(title)
                                .setMessage(body)
                                .setPositiveButton(stop) { _, _ -> vm.stopConfirmed() }
                                .setNegativeButton(keep, null)
                                .show()
                            true
                        }
                    }
                }.pointerInput(live) {
                    if (!live) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!vm.stopPressed()) return@awaitEachGesture
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val p = change.position
                            val dx = maxOf(0f, -p.x, p.x - size.width)
                            val dy = maxOf(0f, -p.y, p.y - size.height)
                            if (dx * dx + dy * dy > slack * slack) break
                        }
                        vm.stopReleased()
                    }
                },
    )
}

/** The dock's text stops growing at this scale. */
private const val DOCK_FONT_CAP = 1.5f

/** "3 seconds", "1 minute, 12 seconds": a take's length as a screen reader says it. */
@Composable
private fun spokenDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000).toInt()
    val secs = pluralStringResource(R.plurals.a11y_seconds, seconds % 60, seconds % 60)
    if (seconds < 60) return secs
    val mins = pluralStringResource(R.plurals.a11y_minutes, seconds / 60, seconds / 60)
    return if (seconds % 60 == 0) mins else "$mins, $secs"
}

/** Sliding this far off the stop button cancels the hold. */
private val STOP_SLIDE_OFF = 44.dp

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
    onReadAll: () -> Unit,
) {
    val draft = state.draft
    DraftLabel(state)
    if (draft.images.isNotEmpty() || draft.files.isNotEmpty()) {
        AttachRow(draft, state.seesImages, state.imageInput?.model.orEmpty(), vm::removeImage, vm::removeFile)
    }
    // The draft takes the room between its line and the actions above the dock (app-plan.md, Long drafts).
    DraftBody(draft, vm, onReadAll, Modifier.padding(top = 14.dp).weight(1f, fill = false))
    if (!draft.isListening) DraftActions(state, vm)
}

/** Which text the reader shows. */
private enum class ReaderOpen { Draft, YouSaid }

/**
 * The reader (`HexReader`, app-plan.md, Long drafts and Just sent): the whole draft, one paragraph per take, with ✕ and
 * Send at its foot; or the message just sent, "You said", without them.
 */
@Composable
private fun BoxScope.DraftReader(
    open: ReaderOpen?,
    state: HomeUiState,
    vm: HomeViewModel,
    onClose: () -> Unit,
) {
    val draft = state.draft
    val you = state.today.you
    // Nothing left to read closes it: the draft was sent or cleared, or the You line went.
    LaunchedEffect(open, draft.isEmpty, you) {
        if ((open == ReaderOpen.Draft && draft.isEmpty) || (open == ReaderOpen.YouSaid && you == null)) onClose()
    }
    // The message without its files' path lines, and what went with it under the meta (app-plan.md, Attach files).
    val text = if (open == ReaderOpen.YouSaid) PromptFiles.strip(you.orEmpty()) else draft.text
    val words = Words.count(text)
    val wordsText = pluralStringResource(R.plurals.reader_words, words, words)
    HexReader(
        visible = open != null && text.isNotBlank(),
        title = stringResource(if (open == ReaderOpen.YouSaid) R.string.reader_you_said else R.string.draft_title),
        meta = if (open == ReaderOpen.YouSaid) wordsText else "${clock(draft.durationMs)} · $wordsText",
        paragraphs =
            if (open == ReaderOpen.YouSaid) {
                text.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                draft.chunks.mapNotNull { it.text?.trim()?.ifEmpty { null } }
            },
        doneLabel = stringResource(R.string.common_done),
        onDone = onClose,
        foot =
            if (open == ReaderOpen.Draft) {
                HexReaderFoot(
                    discardLabel = stringResource(R.string.draft_clear),
                    onDiscard = {
                        onClose()
                        vm.clearDraft()
                    },
                    sendLabel = stringResource(R.string.home_send),
                    onSend = {
                        onClose()
                        vm.send()
                    },
                    sendEnabled = state.canSend,
                )
            } else {
                null
            },
        note = if (open == ReaderOpen.YouSaid) youWith(state.today.youWith) else null,
    )
}

/**
 * The draft as running text as long as it can be (app-plan.md, Long drafts): its size steps down, `draft` (24), 21, 19,
 * 17, each measured as drawn against the room it has. More than 3 chunks, or 2 or more that don't fit at 17, are one
 * row per chunk; a single chunk too long even at 17 scrolls, kept at its newest words.
 */
@Composable
private fun DraftBody(
    draft: Draft,
    vm: HomeViewModel,
    onReadAll: () -> Unit,
    modifier: Modifier,
) {
    val onSelect: (Int) -> Unit = vm::selectChunk
    // With TalkBack on, the draft is one row per chunk, each with Delete and Re-record (app-plan.md, Accessibility).
    val screenReader = rememberScreenReaderOn()
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
        val medium = styles.getValue(DraftSize.Medium)
        // The line height is in em (1.28), or sp, as the type tokens give it.
        val line =
            medium.lineHeight.let { height ->
                when {
                    height.isEm -> medium.fontSize.toPx() * height.value
                    height.isSp -> height.toPx()
                    else -> medium.fontSize.toPx() * 1.3f
                }
            }
        val windowLines = line * DraftShape.WINDOW_LINES
        val shape =
            if (screenReader && draft.shown.isNotEmpty()) {
                DraftShape.Rows
            } else {
                null
            } ?: DraftShape.choose(
                draft.shown.size,
                draft.characters,
                beyondWindow = {
                    subcompose("window") { DraftText(draft, onSelect, medium) }.first().measure(loose).height > windowLines * 1.04f
                },
            ) { size ->
                val measured = subcompose(size) { DraftText(draft, onSelect, styles.getValue(size)) }.first().measure(loose)
                measured.height <= room
            }
        val shown =
            subcompose("shown") {
                when (shape) {
                    is DraftShape.Running -> DraftText(draft, onSelect, styles.getValue(shape.size))
                    DraftShape.Window -> DraftWindow(draft, onSelect, medium, windowLines.toDp(), onReadAll)
                    DraftShape.Rows -> ChunkList(draft, onSelect, vm)
                }
            }.first().measure(constraints.copy(minHeight = 0))
        layout(shown.width, shown.height) { shown.place(0, 0) }
    }
}

/**
 * A long single take (app-plan.md, Long drafts): a 7-line window at 21 resting on the newest words, which scrolls
 * back; its top edge fades while there's more above, never a hard clip. Under it "Read all · N words" opens the reader.
 */
@Composable
private fun DraftWindow(
    draft: Draft,
    onSelect: (Int) -> Unit,
    style: TextStyle,
    height: Dp,
    onReadAll: () -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(draft.characters) { scroll.scrollTo(scroll.maxValue) }
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val fade = with(LocalDensity.current) { (style.fontSize * 2.6f).toPx() }
    Column {
        Box(
            Modifier
                .weight(1f, fill = false)
                .heightIn(max = height)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    // No fade once scrolled to the start.
                    if (scroll.value > 0) {
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }.verticalScroll(scroll),
        ) { DraftText(draft, onSelect, style) }
        if (!draft.isListening) {
            val words = Words.count(draft.text)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(HexTheme.radius.s))
                        .clickable(role = Role.Button, onClick = onReadAll)
                        .heightIn(min = 32.dp)
                        .padding(end = 6.dp),
            ) {
                Icon(HexIcons.chevU, null, tint = HexTheme.colors.text3, modifier = Modifier.size(HexTheme.size.iconSmall))
                Text(
                    pluralStringResource(R.plurals.draft_read_all, words, words),
                    style = HexTheme.type.footnote,
                    color = HexTheme.colors.text3,
                )
            }
        }
    }
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
            modifier = Modifier.heightIn(min = 18.dp).semantics { liveRegion = LiveRegionMode.Polite },
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
                    // A locked take shows its text so far before "Listening…", and before "Transcribing…" after release.
                    ChunkState.Listening -> HexChunkItem(listening, HexChunkKind.Listening, chunk.soFar)
                    // A take waiting for the speech model looks like one being transcribed; the label line says why.
                    ChunkState.Waiting, ChunkState.Transcribing -> HexChunkItem(transcribing, HexChunkKind.Transcribing, chunk.soFar)
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
    vm: HomeViewModel,
) {
    val deleteLabel = stringResource(R.string.a11y_chunk_delete)
    val rerecordLabel = stringResource(R.string.a11y_chunk_rerecord)
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
                        // A locked take shows its text so far, once it has some.
                        ChunkState.Listening -> chunk.soFar.ifEmpty { stringResource(R.string.draft_listening) }
                        ChunkState.Waiting, ChunkState.Transcribing ->
                            chunk.soFar.ifEmpty {
                                stringResource(
                                    R.string.draft_transcribing_chunk,
                                )
                            }
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
                accessibilityLabel = stringResource(R.string.a11y_chunk, index + 1, shown.size),
                actions =
                    if (tappable) {
                        listOf(
                            CustomAccessibilityAction(deleteLabel) {
                                onSelect(index)
                                vm.deleteChunk()
                                true
                            },
                            CustomAccessibilityAction(rerecordLabel) {
                                onSelect(index)
                                vm.rerecord()
                                true
                            },
                        )
                    } else {
                        emptyList()
                    },
            )
        }
    }
}

/**
 * The draft's attachments (app-plan.md, Attach and Attach files › Draft row): images as 40 dp squares, each with ✕ and
 * a spinner while it's prepared, then files as tiles with their extension and, while they upload, a ring. One caption
 * after them ([AttachCaption]): "2 images · go with your next message", "report.pdf · goes with your next message",
 * "Uploading · 45%"; or, for a model that can't see images ([model] is its name), dimmed images and "<Model> can't
 * see images · they won't be sent".
 */
@Composable
private fun AttachRow(
    draft: Draft,
    seesImages: Boolean,
    model: String,
    onRemoveImage: (Long) -> Unit,
    onRemoveFile: (Long) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        val remove = stringResource(R.string.home_attach_remove)
        draft.images.forEach { image ->
            HexThumbnail(
                rememberThumbnail(image.file, HexThumbnailSize.Regular.side),
                preparing = image.preparing,
                dimmed = !seesImages,
                onRemove = { onRemoveImage(image.id) },
                removeLabel = remove,
            )
        }
        draft.files.forEach { file ->
            HexFileThumbnail(
                file.extension,
                video = file.video,
                progress = if (file.uploading) (if (file.total > 0) file.sent.toFloat() / file.total else 0f) else null,
                label = stringResource(R.string.home_attach_file_label, file.name),
                onRemove = { onRemoveFile(file.id) },
                removeLabel = stringResource(R.string.home_attach_remove_file, file.name),
            )
        }
        val caption = AttachCaption.of(draft, seesImages)
        Text(
            when (caption) {
                is AttachCaption.Uploading -> stringResource(R.string.home_attach_uploading, caption.percent)
                AttachCaption.NoVision -> stringResource(R.string.home_attach_no_vision, model)
                is AttachCaption.Images -> pluralStringResource(R.plurals.home_attach_caption, caption.count, caption.count)
                is AttachCaption.File -> stringResource(R.string.home_attach_caption_file, MiddleEllipsis.of(caption.name, 28))
                is AttachCaption.Files -> pluralStringResource(R.plurals.home_attach_caption_files, caption.count, caption.count)
                is AttachCaption.Mixed ->
                    stringResource(
                        R.string.home_attach_caption_mixed,
                        pluralStringResource(R.plurals.home_attach_count_images, caption.images, caption.images),
                        pluralStringResource(R.plurals.home_attach_count_files, caption.files, caption.files),
                    )
            },
            style = HexTheme.type.caption,
            color = HexTheme.colors.text3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
    }
}

/**
 * "2 images", "1 file" or "2 images, 1 file" for a message's attachments, in place of its text when it has none;
 * null with none.
 */
@Composable
fun attachmentCount(
    images: Int,
    files: Int,
): String? =
    when {
        images > 0 && files > 0 ->
            pluralStringResource(R.plurals.home_attach_count_images, images, images) + ", " +
                pluralStringResource(R.plurals.home_attach_count_files, files, files)
        images > 0 -> pluralStringResource(R.plurals.home_waiting_images, images, images)
        files > 0 -> pluralStringResource(R.plurals.home_waiting_files, files, files)
        else -> null
    }

/**
 * The Attach sheet (app-plan.md, Attach files): Photos and videos opens the system photo picker, Take photo the
 * camera, and Files the system file picker; Files is dimmed, reading "Needs a newer OpenCode", when the server can't
 * take uploads ([filesAvailable]).
 */
@Composable
private fun BoxScope.AttachSheet(
    open: Boolean,
    filesAvailable: Boolean,
    onPhotos: () -> Unit,
    onCamera: () -> Unit,
    onFiles: () -> Unit,
    onClose: () -> Unit,
) {
    HexSheet(
        visible = open,
        onDismiss = onClose,
        title = stringResource(R.string.home_attach_title),
        trailing = HexSheetAction(stringResource(R.string.common_close), onClose),
    ) {
        HexRow(stringResource(R.string.home_attach_photos), icon = HexIcons.img, onClick = onPhotos)
        // Dimmed, and doing nothing, on a device without a camera.
        val camera = LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        HexRow(
            stringResource(R.string.home_attach_camera),
            icon = HexIcons.scan,
            onClick = if (camera) onCamera else null,
            modifier = Modifier.alpha(if (camera) 1f else 0.35f),
        )
        HexRow(
            stringResource(R.string.home_attach_files),
            icon = HexIcons.doc,
            value = if (filesAvailable) null else stringResource(R.string.home_attach_files_unavailable),
            onClick = if (filesAvailable) onFiles else null,
            modifier = Modifier.alpha(if (filesAvailable) 1f else 0.35f),
        )
    }
}

/** Opens the photo picker, the camera or the file picker. */
private class AttachPickers(
    val photos: () -> Unit,
    val camera: () -> Unit,
    val files: () -> Unit,
)

/**
 * The system pickers (app-plan.md, Attach files › Pickers): the Photo Picker, images and videos, up to [room] (one at a
 * time when only one fits, since the multiple picker takes two or more), each with the type it reports so a video can
 * be told from an image; the camera, writing into the cache ([onTaken]); and the file picker, any file ([onFiles]).
 * None needs a permission, but Hex Code declares CAMERA for pairing, and an app that declares it must hold it to open
 * the camera, so it's asked for first.
 */
@Composable
private fun rememberAttachPickers(
    room: Int,
    onTaken: (List<String>) -> Unit,
    onMedia: (List<PickedMedia>) -> Unit,
    onFiles: (List<String>) -> Unit,
): AttachPickers {
    val context = LocalContext.current
    val media = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
    val picked = { uris: List<android.net.Uri> -> onMedia(uris.map { PickedMedia(it.toString(), context.contentResolver.getType(it)) }) }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(room.coerceAtLeast(2))) { picked(it) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { picked(listOf(it)) } }
    val documents =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onFiles(uris.map { it.toString() }) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val uri = target
            target = null
            if (taken && uri != null) onTaken(listOf(uri))
        }
    val openCamera = {
        val uri = AndroidImagePreparer.cameraTarget(context)
        target = uri.toString()
        takePicture.launch(uri)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) openCamera() }
    return AttachPickers(
        photos = { if (room >= 2) many.launch(media) else one.launch(media) },
        files = { documents.launch(arrayOf("*/*")) },
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
    // The ⋯ menu opens above the whole row, at its right edge (`.dmenu`).
    Box(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        DraftMenu(state, vm)
        DraftActionRow(state, vm, draft, busy, mode)
    }
}

@Composable
private fun DraftActionRow(
    state: HomeUiState,
    vm: HomeViewModel,
    draft: Draft,
    busy: Boolean,
    mode: SendMode,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
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
            DraftMore(vm)
            // Send waits for every chunk's text, every image and every file: it reads "Transcribing" while a chunk is
            // still on its way, else "Preparing" while an image is, else "Uploading" while a file is.
            val transcribing = draft.isListening || draft.isTranscribing || draft.isWaiting
            val pending = transcribing || draft.isPreparing || draft.isUploading
            HexButton(
                onClick = vm::send,
                text =
                    stringResource(
                        when {
                            transcribing -> R.string.home_send_transcribing
                            draft.isPreparing -> R.string.home_send_preparing
                            draft.isUploading -> R.string.home_send_uploading
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

/**
 * Fades the content in over 0.2 s each time [stage] steps up after the first composition (instant with Reduce Motion);
 * what's there at the first composition, as when the splash goes, is just there.
 */
@Composable
private fun Modifier.fadeInOnArrival(stage: Int): Modifier {
    val reduced = HexMotion.reduced
    val fade = HexTheme.motion.fade<Float>()
    val alpha = remember { Animatable(1f) }
    var last by remember { mutableIntStateOf(stage) }
    LaunchedEffect(stage) {
        val up = stage > last
        last = stage
        if (up && !reduced) {
            alpha.snapTo(0f)
            alpha.animateTo(1f, fade)
        } else {
            alpha.snapTo(1f)
        }
    }
    return graphicsLayer { this.alpha = alpha.value }
}
