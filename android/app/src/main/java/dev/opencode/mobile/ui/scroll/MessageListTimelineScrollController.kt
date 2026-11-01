package dev.opencode.mobile.ui.scroll

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class MessageListTimelineScrollController(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val autoFollowState: MessageListAutoFollowState,
) {
    var isProgrammaticScrolling by mutableStateOf(false)
        private set

    private var scrollJob: Job? = null

    fun animateToEnd(
        messageCount: Int,
        tolerancePx: Int,
        bottomAnchorHeightPx: Int,
        autoFollowEnabled: Boolean = true,
    ) {
        launchScroll {
            listState.animateScrollToTimelineEndFromCurrentPosition(messageCount = messageCount)
            settleScroll(
                messageCount = messageCount,
                tolerancePx = tolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
                autoFollowEnabled = autoFollowEnabled,
            )
        }
    }

    fun snapToEnd(
        messageCount: Int,
        tolerancePx: Int,
        bottomAnchorHeightPx: Int,
        autoFollowEnabled: Boolean = true,
    ) {
        launchScroll {
            withFrameNanos { }
            withFrameNanos { }
            listState.scrollToTimelineEnd()
            settleScroll(
                messageCount = messageCount,
                tolerancePx = tolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
                autoFollowEnabled = autoFollowEnabled,
            )
        }
    }

    fun snapToEndAfterLayoutChange(
        messageCount: Int,
        tolerancePx: Int,
        bottomAnchorHeightPx: Int,
        autoFollowEnabled: Boolean = true,
    ) {
        launchScroll {
            listState.scrollToTimelineEndAfterLayoutChange(messageCount = messageCount)
            settleScroll(
                messageCount = messageCount,
                tolerancePx = tolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
                autoFollowEnabled = autoFollowEnabled,
            )
        }
    }

    /**
     * Scroll the lazy item at [index] into view, away from the end (a failure the
     * activity pill points at). Follow stops; the place then holds like a user scroll.
     */
    fun revealItem(
        index: Int,
        messageCount: Int,
        tolerancePx: Int,
        bottomAnchorHeightPx: Int,
    ) {
        launchScroll {
            autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd = false)
            listState.animateScrollToItem(index)
            settleScroll(
                messageCount = messageCount,
                tolerancePx = tolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
                autoFollowEnabled = true,
            )
        }
    }

    private fun launchScroll(block: suspend () -> Unit) {
        scrollJob?.cancel()
        isProgrammaticScrolling = true
        val job =
            scope.launch {
                try {
                    block()
                } finally {
                    if (scrollJob === coroutineContext[Job]) {
                        isProgrammaticScrolling = false
                    }
                }
            }
        scrollJob = job
    }

    private suspend fun settleScroll(
        messageCount: Int,
        tolerancePx: Int,
        bottomAnchorHeightPx: Int,
        autoFollowEnabled: Boolean,
    ) {
        withFrameNanos { }
        val pinned =
            listState.isPinnedToTimelineEnd(
                messageCount = messageCount,
                tolerancePx = tolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
            )
        if (autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled = autoFollowEnabled) && !pinned) {
            return
        }
        autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd = pinned)
    }
}

@Composable
internal fun rememberMessageListTimelineScrollController(
    listState: LazyListState,
    autoFollowState: MessageListAutoFollowState,
): MessageListTimelineScrollController {
    val scope = rememberCoroutineScope()
    return remember(listState, scope, autoFollowState) {
        MessageListTimelineScrollController(
            listState = listState,
            scope = scope,
            autoFollowState = autoFollowState,
        )
    }
}
