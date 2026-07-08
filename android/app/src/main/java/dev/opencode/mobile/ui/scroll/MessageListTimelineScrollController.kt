package dev.opencode.mobile.ui.scroll

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class MessageListTimelineScrollController(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val autoFollowState: MessageListAutoFollowState,
) {
    private var scrollJob: Job? = null

    fun animateToEnd(
        messageCount: Int,
        tolerancePx: Int,
        bottomAnchorHeightPx: Int,
    ) {
        launchScroll {
            listState.animateScrollToTimelineEndAfterAppend(messageCount = messageCount)
            markSettled(messageCount, tolerancePx, bottomAnchorHeightPx)
        }
    }

    fun snapToEnd(
        messageCount: Int,
        tolerancePx: Int,
        bottomAnchorHeightPx: Int,
    ) {
        launchScroll {
            listState.scrollToTimelineEnd(messageCount = messageCount)
            markSettled(messageCount, tolerancePx, bottomAnchorHeightPx)
        }
    }

    fun snapToEndAfterLayoutChange(
        messageCount: Int,
        tolerancePx: Int,
        bottomAnchorHeightPx: Int,
    ) {
        launchScroll {
            listState.scrollToTimelineEndAfterLayoutChange(messageCount = messageCount)
            markSettled(messageCount, tolerancePx, bottomAnchorHeightPx)
        }
    }

    private fun launchScroll(block: suspend () -> Unit) {
        scrollJob?.cancel()
        scrollJob =
            scope.launch {
                block()
            }
    }

    private fun markSettled(
        messageCount: Int,
        tolerancePx: Int,
        bottomAnchorHeightPx: Int,
    ) {
        autoFollowState.markProgrammaticScrollSettled(
            isPinnedToEnd =
                listState.isPinnedToTimelineEnd(
                    messageCount = messageCount,
                    tolerancePx = tolerancePx,
                    bottomAnchorHeightPx = bottomAnchorHeightPx,
                ),
        )
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
