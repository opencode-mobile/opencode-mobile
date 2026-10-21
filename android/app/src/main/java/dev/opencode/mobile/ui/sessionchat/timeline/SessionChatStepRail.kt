package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessageRole

/**
 * Step rail timeline rules (#779) that sit outside the block walk: parallel
 * branches, the rail's worst status, render-time folding, and which rail is
 * live while a turn is working.
 */
object SessionChatStepRail {
    /** More settled steps than this fold behind a leading `N earlier steps` row. */
    const val FOLD_THRESHOLD = 4

    /** Settled steps that stay visible after the fold row. */
    const val FOLD_KEEP_LAST = 3

    /** A running step directly after another running step is a parallel branch. */
    fun withBranches(steps: List<SessionChatStep>): List<SessionChatStep> =
        steps.mapIndexed { index, step ->
            val previous = steps.getOrNull(index - 1)
            val isBranch = step.status.isInFlight && previous?.status?.isInFlight == true
            if (step.isBranch == isBranch) step else step.copy(isBranch = isBranch)
        }

    fun worstStatus(steps: List<SessionChatStep>): SessionChatStepStatus =
        if (steps.any { it.status == SessionChatStepStatus.Failed && !it.isInterrupted }) {
            SessionChatStepStatus.Failed
        } else {
            SessionChatStepStatus.Completed
        }

    /** One row of a rendered rail: a step, or the leading fold row. */
    sealed class Entry {
        data class Step(
            val step: SessionChatStep,
        ) : Entry()

        data class Fold(
            val count: Int,
            val status: SessionChatStepStatus,
        ) : Entry()
    }

    /**
     * With more than [FOLD_THRESHOLD] settled tool or thought steps, every
     * settled one but the last [FOLD_KEEP_LAST] folds into one leading row.
     * Running steps and child sessions never fold. [unfolded] shows all.
     */
    fun entries(
        steps: List<SessionChatStep>,
        unfolded: Boolean,
    ): List<Entry> {
        val foldable = steps.filter(::isFoldable)
        if (unfolded || foldable.size <= FOLD_THRESHOLD) return steps.map { Entry.Step(it) }
        val folded = foldable.dropLast(FOLD_KEEP_LAST)
        val foldedIds = folded.mapTo(HashSet()) { it.id }
        return buildList {
            add(Entry.Fold(count = folded.size, status = worstStatus(folded)))
            steps.filter { it.id !in foldedIds }.forEach { add(Entry.Step(it)) }
        }
    }

    private fun isFoldable(step: SessionChatStep): Boolean = step.kind != SessionChatStepKind.ChildSession && !step.status.isInFlight

    /**
     * Marks the live rail (or activity card, #779): the trailing block of the
     * last assistant row while the turn is working. Text after it ends its
     * liveness, so the Working footer comes back for a reply that is only
     * streaming text. Returns [messages] itself when nothing changes.
     */
    fun markLive(
        messages: List<SessionChatMessageItem>,
        isTurnActive: Boolean,
    ): List<SessionChatMessageItem> {
        val liveIndex = if (isTurnActive) liveMessageIndex(messages) else -1
        var changed = false
        val marked =
            messages.mapIndexed { index, message ->
                val updated = withLiveRail(message, live = index == liveIndex)
                if (updated !== message) changed = true
                updated
            }
        return if (changed) marked else messages
    }

    fun hasLiveRail(messages: List<SessionChatMessageItem>): Boolean =
        messages.any { message ->
            message.blocks.any {
                (it is SessionChatTimelineBlock.StepRail && it.isLive) ||
                    (it is SessionChatTimelineBlock.ActivityCard && it.isLive)
            }
        }

    private fun liveMessageIndex(messages: List<SessionChatMessageItem>): Int {
        val index = messages.indexOfLast { it.role == MessageRole.Assistant }
        if (index < 0) return -1
        // A newer sent prompt starts another turn; queued follow-ups do not.
        val newerPrompt = messages.drop(index + 1).any { it.role == MessageRole.User && !it.isPending }
        if (newerPrompt) return -1
        val trailing = messages[index].blocks.lastOrNull { it.paintsInTimeline }
        val isStepSegment = trailing is SessionChatTimelineBlock.StepRail || trailing is SessionChatTimelineBlock.ActivityCard
        return if (isStepSegment) index else -1
    }

    private fun withLiveRail(
        message: SessionChatMessageItem,
        live: Boolean,
    ): SessionChatMessageItem {
        val liveRailId =
            if (live) message.blocks.lastOrNull { it.paintsInTimeline }?.blockID else null
        var changed = false
        val blocks =
            message.blocks.map { block ->
                val isLive = block.blockID == liveRailId
                when (block) {
                    is SessionChatTimelineBlock.StepRail -> {
                        val thinking = isLive && block.steps.none { it.status.isInFlight }
                        if (block.isLive == isLive && block.thinking == thinking) {
                            block
                        } else {
                            changed = true
                            block.copy(isLive = isLive, thinking = thinking)
                        }
                    }
                    is SessionChatTimelineBlock.ActivityCard -> {
                        val thinking = isLive && block.steps.none { it.status.isInFlight }
                        if (block.isLive == isLive && block.thinking == thinking) {
                            block
                        } else {
                            changed = true
                            block.copy(isLive = isLive, thinking = thinking)
                        }
                    }
                    else -> block
                }
            }
        return if (changed) message.copy(blocks = blocks) else message
    }
}
