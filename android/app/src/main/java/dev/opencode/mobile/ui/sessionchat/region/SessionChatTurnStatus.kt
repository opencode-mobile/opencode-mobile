package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.sessionchat.PendingPrompt
import dev.opencode.mobile.ui.sessionchat.PendingPromptsStripGate
import dev.opencode.mobile.ui.sessionchat.SessionChatBackgroundWork
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionCandidate
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionLinker
import dev.opencode.mobile.ui.sessionchat.SessionChatInboxRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatStepRail
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagent
import dev.opencode.mobile.ui.sessionchat.SessionConnectionStatus
import dev.opencode.mobile.ui.sessionchat.SessionInfoRegionState
import dev.opencode.mobile.ui.sessionchat.WorkingTurnIndicator
import dev.opencode.mobile.ui.sessionchat.pendingPromptsFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the open session's turn shows, resolved once per publish. */
data class SessionChatTurnStatusResolution(
    val workingIndicatorLabel: String?,
    val workingIndicatorCollapsed: Boolean,
    val workingIndicatorFading: Boolean,
    val pendingPrompts: List<PendingPrompt>,
    val subagents: List<SessionChatSubagent>,
    val backgroundWorkDoneUnseen: Boolean,
)

/** A pending-prompts strip action on one waiting prompt. */
enum class SessionChatPendingPromptAction { Steer, Queue, Cancel }

/**
 * Session Chat region for what the open session's turn is doing: the working footer (with its
 * hold and wake-up), the pending-prompts strip (its gate, wake-up, and the steer / queue /
 * cancel requests), and the background work rows (subagent finish times and the Done tab's
 * seen rows).
 *
 * It is resolved inside the facade's publish, so it has no state flow: [resolve] returns the
 * values and remembers what the next publish needs; its timers call [onWake] to republish.
 */
class SessionChatTurnStatus(
    private val sessionRepository: SessionRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    /** Shows a strip action the server accepted before the next history load confirms it. */
    private val applyOptimistic: (id: String, delivery: FollowUpDelivery?, removed: Boolean) -> Unit,
    private val reportError: (UiText) -> Unit,
    private val onWake: () -> Unit,
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private var loggedWorkingLamp = false
    private var lastWorkingLamp: String? = null
    private var workingChrome: WorkingTurnIndicator.Chrome = WorkingTurnIndicator.Chrome.Unmounted
    private var workingHold: WorkingTurnIndicator.Hold? = null
    private var workingHoldJob: Job? = null
    private val pendingPromptsGate = PendingPromptsStripGate()
    private var pendingPromptsWakeJob: Job? = null

    /**
     * Done rows of the background work panel the user has seen (#966); null until the first
     * rows of the open session, which count as seen.
     */
    private var seenDoneSubagentIds: Set<String>? = null

    /** The background work panel shows its Done tab, so finishes are seen as they land. */
    private var backgroundWorkDoneVisible = false

    /** When subagents seen working finished live, keyed by tool part id, for rows with no server time. */
    private var subagentFinishTimes: Map<String, Long> = emptyMap()

    /**
     * When the connection last became live; null while it is not. Rows published before it
     * were seen before a gap, so a finish found after it is not stamped locally.
     */
    private var subagentLiveSinceMs: Long? = null

    /** When the published subagent rows were computed. */
    private var subagentsSeenAtMs: Long? = null

    /**
     * Resolves the working footer, the pending-prompts strip and the background work rows, in
     * that order, and schedules their wake-ups.
     *
     * Inputs read from the timeline are lambdas so each is read where the resolution needs it.
     *
     * @param messages the presented (live-marked) timeline rows.
     * @param previousSubagents the rows the last publish showed.
     */
    fun resolve(
        sessionStatusLabel: String?,
        isCompacting: Boolean,
        sendProgressPercent: Int?,
        isBound: Boolean,
        messages: List<SessionChatMessageItem>,
        loadedMessages: () -> List<Message>,
        pendingOptimistic: () -> List<SessionChatMessageItem>,
        childSessions: () -> List<SessionChatChildSessionCandidate>,
        inboxState: SessionChatInboxRegionState,
        infoState: SessionInfoRegionState,
        previousSubagents: List<SessionChatSubagent>,
    ): SessionChatTurnStatusResolution {
        val awaitingUser =
            inboxState.permissionRequests.isNotEmpty() || inboxState.questionRequests.isNotEmpty()
        val lastIsThinking =
            WorkingTurnIndicator.isStreamingThinking(loadedMessages()) ||
                WorkingTurnIndicator.lastIsThinking(messages)
        val activeWorkingLabel =
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = sessionStatusLabel,
                isCompacting = isCompacting,
                isAwaitingUser = awaitingUser,
                sendProgressPercent = sendProgressPercent,
                isThinking = lastIsThinking,
            )
        val workingResolution =
            WorkingTurnIndicator.resolveFooter(
                activeLabel = activeWorkingLabel,
                lastIsThinking = lastIsThinking,
                hideImmediately = awaitingUser || !isBound,
                previous = workingChrome,
                hold = workingHold,
                nowMs = nowMs(),
            )
        workingChrome = workingResolution.chrome
        workingHold = workingResolution.hold
        scheduleWorkingFooterWake(workingResolution.wakeAfterMs)
        // Step rail / activity card: the live rail's or card's running or Thinking… line is the indicator (#779).
        val workingIndicatorLabel =
            workingResolution.chrome.label.takeUnless {
                activeWorkingLabel != null && SessionChatStepRail.hasLiveRail(messages)
            }
        val workingIndicatorCollapsed = workingResolution.chrome.collapsed
        val workingIndicatorFading = workingResolution.chrome.fading
        val workingLampKey =
            "${workingIndicatorLabel ?: "none"}:" +
                "${if (workingIndicatorCollapsed) 1 else 0}:" +
                "${if (workingIndicatorFading) 1 else 0}:" +
                "${if (workingHold != null) 1 else 0}"
        if (!loggedWorkingLamp || lastWorkingLamp != workingLampKey) {
            loggedWorkingLamp = true
            lastWorkingLamp = workingLampKey
            DiagnosticLog.info(
                DiagnosticLogArea.Chat,
                "workingLamp label=${workingIndicatorLabel ?: "none"} " +
                    "collapsed=${if (workingIndicatorCollapsed) 1 else 0} " +
                    "fading=${if (workingIndicatorFading) 1 else 0} " +
                    "hold=${if (workingHold != null) 1 else 0} " +
                    "status=${sessionStatusLabel ?: "none"} " +
                    "awaitingUser=${if (awaitingUser) 1 else 0} " +
                    "lastIsThinking=${if (lastIsThinking) 1 else 0}",
            )
        }
        val pendingResolution =
            pendingPromptsGate.resolve(
                pendingPromptsFrom(
                    loaded = loadedMessages(),
                    optimistic = pendingOptimistic(),
                ),
                nowMs = nowMs(),
            )
        schedulePendingPromptsWake(pendingResolution.wakeAfterMs)
        val subagents = resolveSubagents(loadedMessages, childSessions, inboxState, infoState, previousSubagents)
        val seenDone = SessionChatBackgroundWork.seenDoneIds(seenDoneSubagentIds, subagents, backgroundWorkDoneVisible)
        seenDoneSubagentIds = seenDone
        return SessionChatTurnStatusResolution(
            workingIndicatorLabel = workingIndicatorLabel,
            workingIndicatorCollapsed = workingIndicatorCollapsed,
            workingIndicatorFading = workingIndicatorFading,
            pendingPrompts = pendingResolution.prompts,
            subagents = subagents,
            backgroundWorkDoneUnseen = SessionChatBackgroundWork.hasUnseenDone(subagents, seenDone),
        )
    }

    /**
     * The background work panel shows (or stops showing) its Done tab (#966). Returns true when
     * the facade should republish: while the tab shows, every finished row counts as seen.
     */
    fun setDoneVisible(visible: Boolean): Boolean {
        if (backgroundWorkDoneVisible == visible) return false
        backgroundWorkDoneVisible = visible
        return visible
    }

    /** A new session or draft: the footer, the strip gate and the Done tab's seen rows start over. */
    fun reset() {
        seenDoneSubagentIds = null
        backgroundWorkDoneVisible = false
        resetFooter()
    }

    /** Stops the footer and strip timers and forgets their holds; seen Done rows stay. */
    fun resetFooter() {
        workingHoldJob?.cancel()
        workingHoldJob = null
        workingHold = null
        workingChrome = WorkingTurnIndicator.Chrome.Unmounted
        pendingPromptsWakeJob?.cancel()
        pendingPromptsWakeJob = null
        pendingPromptsGate.reset()
    }

    /**
     * Steer, queue, or cancel the waiting prompt [id] of [sessionId]. [logLabel] names the
     * action in the diagnostic log when the request fails.
     */
    fun updatePendingPrompt(
        action: SessionChatPendingPromptAction,
        id: String,
        logLabel: String,
        sessionId: String?,
        worktree: String?,
    ) {
        sessionId ?: return
        worktree ?: return
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    when (action) {
                        SessionChatPendingPromptAction.Steer -> sessionRepository.steerPendingPrompt(sessionId, id, worktree)
                        SessionChatPendingPromptAction.Queue -> sessionRepository.queuePendingPrompt(sessionId, id, worktree)
                        SessionChatPendingPromptAction.Cancel -> sessionRepository.cancelPendingPrompt(sessionId, id, worktree)
                    }
                }
            }.onSuccess { ok ->
                if (ok) {
                    when (action) {
                        SessionChatPendingPromptAction.Steer -> applyOptimistic(id, FollowUpDelivery.Steer, false)
                        SessionChatPendingPromptAction.Queue -> applyOptimistic(id, FollowUpDelivery.Queue, false)
                        SessionChatPendingPromptAction.Cancel -> applyOptimistic(id, null, true)
                    }
                } else {
                    reportError(UiText.resource(R.string.session_chat_pending_prompt_update_failed))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                DiagnosticLog.warning(
                    DiagnosticLogArea.Chat,
                    "pending prompt $logLabel failed id=$id",
                    error,
                )
                reportError(UiText.resource(R.string.session_chat_pending_prompt_update_failed))
            }
        }
    }

    /**
     * Subagent rows from the linked timeline, live session activity and the pending
     * requests of the session tree; remembers when rows seen working finish.
     */
    private fun resolveSubagents(
        loadedMessages: () -> List<Message>,
        childSessions: () -> List<SessionChatChildSessionCandidate>,
        inboxState: SessionChatInboxRegionState,
        infoState: SessionInfoRegionState,
        previousSubagents: List<SessionChatSubagent>,
    ): List<SessionChatSubagent> {
        val now = nowMs()
        val isLive = infoState.connectionStatus == SessionConnectionStatus.Live
        if (!isLive) {
            subagentLiveSinceMs = null
        } else if (subagentLiveSinceMs == null) {
            subagentLiveSinceMs = now
        }
        val liveSince = subagentLiveSinceMs
        val seenAt = subagentsSeenAtMs
        val seenLive = isLive && liveSince != null && seenAt != null && seenAt >= liveSince
        subagentsSeenAtMs = now
        val messages = SessionChatChildSessionLinker.link(loadedMessages(), childSessions())
        val sessions = inboxState.permissionSessions + inboxState.questionSessions
        val requestSessionIds =
            inboxState.permissionRequests.map { it.sessionId } + inboxState.questionRequests.map { it.sessionId }

        fun rows(finishTimes: Map<String, Long>) =
            SessionChatBackgroundWork.subagents(
                messages = messages,
                sessions = sessions,
                busySessionIds = infoState.busySessionIds,
                requestSessionIds = requestSessionIds,
                localFinishedAtMs = finishTimes,
            )
        val current = rows(subagentFinishTimes)
        val finishTimes =
            SessionChatBackgroundWork.finishTimes(
                previous = previousSubagents,
                current = current,
                known = subagentFinishTimes,
                nowMs = now,
                seenLive = seenLive,
            )
        if (finishTimes == subagentFinishTimes) return current
        subagentFinishTimes = finishTimes
        return rows(finishTimes)
    }

    private fun schedulePendingPromptsWake(wakeAfterMs: Long?) {
        pendingPromptsWakeJob?.cancel()
        pendingPromptsWakeJob = null
        if (wakeAfterMs == null) return
        pendingPromptsWakeJob =
            scope.launch {
                delay(wakeAfterMs)
                onWake()
            }
    }

    private fun scheduleWorkingFooterWake(wakeAfterMs: Long?) {
        workingHoldJob?.cancel()
        workingHoldJob = null
        if (wakeAfterMs == null) return
        workingHoldJob =
            scope.launch {
                delay(wakeAfterMs)
                onWake()
            }
    }
}
