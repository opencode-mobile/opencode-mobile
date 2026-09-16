package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingTurnIndicatorTest {
    @Test
    fun labelForActiveTurn_nullWhenIdle() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(statusLabel = null),
        )
    }

    @Test
    fun labelForActiveTurn_showsWorkingWhenBusy() {
        assertEquals(
            "Working",
            WorkingTurnIndicator.labelForActiveTurn(statusLabel = "Working"),
        )
    }

    @Test
    fun labelForActiveTurn_showsSendingPercentWhileBodyIsOnTheWire() {
        assertEquals(
            "Sending 25%",
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                sendProgressPercent = 25,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsSendingEllipsisAtZero() {
        assertEquals(
            "Sending…",
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                sendProgressPercent = 0,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_compactingWinsOverSending() {
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isCompacting = true,
                sendProgressPercent = 40,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_hidesSendingWhenAwaitingUser() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isAwaitingUser = true,
                sendProgressPercent = 40,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsRetrying() {
        assertEquals(
            "Retrying",
            WorkingTurnIndicator.labelForActiveTurn(statusLabel = "Retrying"),
        )
    }

    @Test
    fun labelForActiveTurn_hidesWhenAwaitingUser() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isAwaitingUser = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_hidesCompactingWhenAwaitingUser() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isCompacting = true,
                isAwaitingUser = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsCompactingEvenWhenStatusIsWorking() {
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isCompacting = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsCompactingWhenIdleStatus() {
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = null,
                isCompacting = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_ignoresThinkingWhenIdleStatus() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = null,
                isThinking = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsThinkingInPlace() {
        assertEquals(
            WorkingTurnIndicator.THINKING_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isThinking = true,
            ),
        )
        assertFalse(
            WorkingTurnIndicator.shouldCollapseReservedRow(
                lastIsThinking = true,
                label = WorkingTurnIndicator.THINKING_LABEL,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_keepsCompactingWhenLastIsThinking() {
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isCompacting = true,
                isThinking = true,
            ),
        )
        assertFalse(
            WorkingTurnIndicator.shouldCollapseReservedRow(
                lastIsThinking = true,
                label = SessionCompactChrome.STATUS_LABEL,
            ),
        )
    }

    @Test
    fun shouldCollapseReservedRow_falseWhenNotThinking() {
        assertFalse(
            WorkingTurnIndicator.shouldCollapseReservedRow(
                lastIsThinking = false,
                label = "Working",
            ),
        )
    }

    @Test
    fun shouldCollapseReservedRow_falseWhenLabelMissing() {
        assertFalse(
            WorkingTurnIndicator.shouldCollapseReservedRow(
                lastIsThinking = true,
                label = null,
            ),
        )
    }

    @Test
    fun showsIdleMark_onlyForIdleLabel() {
        assertTrue(WorkingTurnIndicator.showsIdleMark(WorkingTurnIndicator.IDLE_LABEL))
        assertFalse(WorkingTurnIndicator.showsIdleMark("Working"))
        assertFalse(WorkingTurnIndicator.showsIdleMark(WorkingTurnIndicator.THINKING_LABEL))
        assertFalse(WorkingTurnIndicator.showsIdleMark(SessionCompactChrome.STATUS_LABEL))
    }

    @Test
    fun lastIsThinking_trueWhenLastBlockIsStreamingReasoning() {
        assertTrue(
            WorkingTurnIndicator.lastIsThinking(
                listOf(message(SessionChatTimelineBlock.Reasoning("r1", "hmm", isStreaming = true))),
            ),
        )
    }

    @Test
    fun lastIsThinking_falseWhenLastIsSettledThought() {
        assertFalse(
            WorkingTurnIndicator.lastIsThinking(
                listOf(message(SessionChatTimelineBlock.Reasoning("r1", "hmm", isStreaming = false))),
            ),
        )
    }

    @Test
    fun lastIsThinking_trueWhenStreamingReasoningThenText() {
        assertTrue(
            WorkingTurnIndicator.lastIsThinking(
                listOf(
                    message(
                        SessionChatTimelineBlock.Reasoning("r1", "hmm", isStreaming = true),
                        SessionChatTimelineBlock.Text("t1", "Hello"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun lastIsThinking_falseWhenEmpty() {
        assertFalse(WorkingTurnIndicator.lastIsThinking(emptyList()))
    }

    @Test
    fun isStreamingThinking_trueForEmptyStreamingReasoning() {
        assertTrue(
            WorkingTurnIndicator.isStreamingThinking(
                listOf(
                    Message(
                        id = "a1",
                        role = MessageRole.Assistant,
                        text = "",
                        parts =
                            listOf(
                                MessagePart.Reasoning(
                                    text = "",
                                    isStreaming = true,
                                    id = "r1",
                                ),
                            ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun isStreamingThinking_falseWhenSettled() {
        assertFalse(
            WorkingTurnIndicator.isStreamingThinking(
                listOf(
                    Message(
                        id = "a1",
                        role = MessageRole.Assistant,
                        text = "",
                        parts =
                            listOf(
                                MessagePart.Reasoning(
                                    text = "done",
                                    isStreaming = false,
                                    id = "r1",
                                ),
                            ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun resolveFooter_freshIdleStaysUnmounted() {
        val resolved =
            resolve(
                activeLabel = null,
                previous = WorkingTurnIndicator.Chrome.Unmounted,
                hold = null,
            )
        assertEquals(WorkingTurnIndicator.Chrome.Unmounted, resolved.chrome)
        assertNull(resolved.hold)
        assertNull(resolved.wakeAfterMs)
    }

    @Test
    fun resolveFooter_activeWorkingExpandedCancelsHold() {
        val resolved =
            resolve(
                activeLabel = "Working",
                previous = fadingWorking(),
                hold = visibleHold(),
            )
        assertEquals(
            WorkingTurnIndicator.Chrome(label = "Working", collapsed = false, fading = false),
            resolved.chrome,
        )
        assertNull(resolved.hold)
        assertNull(resolved.wakeAfterMs)
    }

    @Test
    fun resolveFooter_activeThinkingStaysExpanded() {
        val resolved =
            resolve(
                activeLabel = WorkingTurnIndicator.THINKING_LABEL,
                lastIsThinking = true,
            )
        assertEquals(
            WorkingTurnIndicator.Chrome(
                label = WorkingTurnIndicator.THINKING_LABEL,
                collapsed = false,
                fading = false,
            ),
            resolved.chrome,
        )
        assertNull(resolved.hold)
        assertNull(resolved.wakeAfterMs)
    }

    @Test
    fun resolveFooter_visibleWorkingIdleShowsIdle() {
        val resolved =
            resolve(
                activeLabel = null,
                previous = WorkingTurnIndicator.Chrome(label = "Working", collapsed = false, fading = false),
                nowMs = 10_000L,
            )
        assertEquals(
            WorkingTurnIndicator.Chrome(label = WorkingTurnIndicator.IDLE_LABEL, collapsed = false, fading = false),
            resolved.chrome,
        )
        assertEquals(
            WorkingTurnIndicator.Hold(startedAtMs = 10_000L),
            resolved.hold,
        )
        assertEquals(WorkingTurnIndicator.IDLE_HOLD_MS, resolved.wakeAfterMs)
    }

    @Test
    fun resolveFooter_visibleHoldBeforeDeadlineStillIdle() {
        val resolved =
            resolve(
                activeLabel = null,
                previous = fadingWorking(),
                hold = visibleHold(startedAtMs = 10_000L),
                nowMs = 14_999L,
            )
        assertEquals(
            WorkingTurnIndicator.Chrome(label = WorkingTurnIndicator.IDLE_LABEL, collapsed = false, fading = false),
            resolved.chrome,
        )
        assertEquals(visibleHold(startedAtMs = 10_000L), resolved.hold)
        assertEquals(1L, resolved.wakeAfterMs)
    }

    @Test
    fun resolveFooter_visibleHoldAtDeadlineCollapsesIdle() {
        val resolved =
            resolve(
                activeLabel = null,
                previous = fadingWorking(),
                hold = visibleHold(startedAtMs = 10_000L),
                nowMs = 15_000L,
            )
        assertEquals(
            WorkingTurnIndicator.Chrome(label = WorkingTurnIndicator.IDLE_LABEL, collapsed = true, fading = false),
            resolved.chrome,
        )
        assertEquals(visibleHold(startedAtMs = 10_000L), resolved.hold)
        assertNull(resolved.wakeAfterMs)
    }

    @Test
    fun resolveFooter_collapsedThinkingIdleShowsIdle() {
        val resolved =
            resolve(
                activeLabel = null,
                previous = WorkingTurnIndicator.Chrome(label = "Working", collapsed = true, fading = false),
                nowMs = 10_000L,
            )
        assertEquals(
            WorkingTurnIndicator.Chrome(label = WorkingTurnIndicator.IDLE_LABEL, collapsed = false, fading = false),
            resolved.chrome,
        )
        assertEquals(
            WorkingTurnIndicator.Hold(startedAtMs = 10_000L),
            resolved.hold,
        )
        assertEquals(WorkingTurnIndicator.IDLE_HOLD_MS, resolved.wakeAfterMs)
    }

    @Test
    fun resolveFooter_newActiveWorkingCancelsVisibleHold() {
        val resolved =
            resolve(
                activeLabel = "Working",
                previous = fadingWorking(),
                hold = visibleHold(),
            )
        assertEquals(
            WorkingTurnIndicator.Chrome(label = "Working", collapsed = false, fading = false),
            resolved.chrome,
        )
        assertNull(resolved.hold)
        assertNull(resolved.wakeAfterMs)
    }

    @Test
    fun resolveFooter_hideImmediatelyUnmountsAnyHold() {
        val resolved =
            resolve(
                activeLabel = null,
                hideImmediately = true,
                previous = fadingWorking(),
                hold = visibleHold(),
            )
        assertEquals(WorkingTurnIndicator.Chrome.Unmounted, resolved.chrome)
        assertNull(resolved.hold)
        assertNull(resolved.wakeAfterMs)
    }

    private fun resolve(
        activeLabel: String?,
        lastIsThinking: Boolean = false,
        hideImmediately: Boolean = false,
        previous: WorkingTurnIndicator.Chrome = WorkingTurnIndicator.Chrome.Unmounted,
        hold: WorkingTurnIndicator.Hold? = null,
        nowMs: Long = 0L,
    ): WorkingTurnIndicator.Resolution =
        WorkingTurnIndicator.resolveFooter(
            activeLabel = activeLabel,
            lastIsThinking = lastIsThinking,
            hideImmediately = hideImmediately,
            previous = previous,
            hold = hold,
            nowMs = nowMs,
        )

    private fun fadingWorking(): WorkingTurnIndicator.Chrome =
        WorkingTurnIndicator.Chrome(label = "Working", collapsed = false, fading = true)

    private fun visibleHold(startedAtMs: Long = 0L): WorkingTurnIndicator.Hold = WorkingTurnIndicator.Hold(startedAtMs = startedAtMs)

    private fun message(vararg blocks: SessionChatTimelineBlock): SessionChatMessageItem =
        SessionChatMessageItem(
            id = "m1",
            role = MessageRole.Assistant,
            text = "",
            metadataLabel = "",
            blocks = blocks.toList(),
        )
}
