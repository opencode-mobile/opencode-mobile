package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.text.UiText

/**
 * What a child session is doing, read from its own messages while an activity
 * card shows it (#779). [steps] are its visible tool steps in order.
 */
data class SessionChatChildActivity(
    val steps: List<SessionChatStep>,
) {
    /** Running title of its most recently started running tool. */
    val currentAction: UiText?
        get() = SessionChatActivityCard.latestRunning(steps)?.runningTitle

    val toolCount: Int
        get() = steps.size
}

/** A live activity card: the summary line, running child sessions, and the footer band. */
data class SessionChatActivityLive(
    val summary: UiText,
    val agents: List<Agent>,
    val current: Current?,
    /** Footer text when no tool runs; null when [current] is set. */
    val idleText: UiText?,
    val startedAtMs: Long?,
) {
    /** A running child session; [id] is the step (part) id. */
    data class Agent(
        val id: String,
        val agentName: String,
        /** What the child is doing now (app text), else its description (content). */
        val action: UiText,
        val startedAtMs: Long?,
    )

    /** The latest running tool, with [extraRunning] more tools running beside it. */
    data class Current(
        val stepId: String,
        val family: SessionChatStepFamily?,
        val title: UiText,
        val extraRunning: Int,
        val startedAtMs: Long?,
    )
}

/**
 * A settled card: `N steps · Read, Edit, Agent`, then one row per child
 * session the segment used.
 */
data class SessionChatActivitySettled(
    val stepCount: Int,
    val names: List<UiText>,
    val worstStatus: SessionChatStepStatus,
    val agents: List<Agent> = emptyList(),
) {
    /** A child session; [id] is the step (part) id, [action] its result line, else its description. */
    data class Agent(
        val id: String,
        val agentName: String,
        val action: UiText,
    )

    /** The tool names as one list: `Read, Grep, Edit, Agent`. */
    val namesText: UiText
        get() = names.reduceOrNull { acc, next -> UiText.resource(R.string.timeline_list_comma, acc, next) } ?: UiText.verbatim("")
}

/**
 * The activity sheet's content for one card, resolved from the current
 * timeline by block id. [messageId] owns the card (a Changes tap needs it).
 */
data class SessionChatActivitySheetContent(
    val messageId: String,
    val blockId: String,
    val isLive: Boolean,
    val stepCount: Int,
    val startedAtMs: Long?,
    val agents: List<Agent>,
    /** Every step that is not a child session, in order. */
    val steps: List<SessionChatStep>,
) {
    data class Agent(
        val step: SessionChatStep,
        val agentName: String,
        val description: String?,
        /** `N tools so far` while running, the finished summary once done. */
        val caption: UiText?,
        /** Up to three latest steps of a running child, oldest first; empty when not observed. */
        val recent: List<SessionChatStep>,
    )
}

/**
 * Activity card timeline rules (#779) that sit outside the block walk: the
 * live card, the settled line and the sheet. Segment building, stable
 * identity and liveness are shared with [SessionChatStepRail].
 */
object SessionChatActivityCard {
    val THINKING: UiText = UiText.resource(R.string.timeline_activity_thinking)
    val WAITING_FOR_AGENTS: UiText = UiText.resource(R.string.timeline_activity_waiting_for_agents)

    /** Most recently started running tool that is not a child session; later in order wins a tie. */
    fun latestRunning(steps: List<SessionChatStep>): SessionChatStep? =
        steps
            .withIndex()
            .filter { (_, step) -> step.status.isInFlight && step.kind == SessionChatStepKind.Tool }
            .maxWithOrNull(compareBy<IndexedValue<SessionChatStep>> { it.value.startedAtMs ?: Long.MIN_VALUE }.thenBy { it.index })
            ?.value

    fun live(
        block: SessionChatTimelineBlock.ActivityCard,
        children: Map<String, SessionChatChildActivity> = emptyMap(),
        thinkingText: UiText = THINKING,
    ): SessionChatActivityLive {
        val steps = block.steps
        val runningChildren = steps.filter { it.kind == SessionChatStepKind.ChildSession && it.status.isInFlight }
        val runningTools = steps.filter { it.kind == SessionChatStepKind.Tool && it.status.isInFlight }
        val latest = latestRunning(steps)
        return SessionChatActivityLive(
            summary = settledSummary(steps),
            agents =
                runningChildren.map { step ->
                    SessionChatActivityLive.Agent(
                        id = step.id,
                        agentName = agentName(step),
                        action = childAction(step, children),
                        startedAtMs = step.startedAtMs,
                    )
                },
            current =
                latest?.let {
                    SessionChatActivityLive.Current(
                        stepId = it.id,
                        family = it.family,
                        title = it.runningTitle,
                        extraRunning = runningTools.size - 1,
                        startedAtMs = it.startedAtMs,
                    )
                },
            idleText =
                when {
                    latest != null -> null
                    runningChildren.isNotEmpty() -> WAITING_FOR_AGENTS
                    else -> thinkingText
                },
            startedAtMs = startedAtMs(steps),
        )
    }

    fun settled(block: SessionChatTimelineBlock.ActivityCard): SessionChatActivitySettled =
        SessionChatActivitySettled(
            stepCount = block.steps.size,
            names = toolNames(block.steps),
            worstStatus = block.worstStatus,
            agents =
                block.steps
                    .filter { it.kind == SessionChatStepKind.ChildSession }
                    .map { step ->
                        val child = step.childSession
                        SessionChatActivitySettled.Agent(
                            id = step.id,
                            agentName = agentName(step),
                            action = UiText.verbatim(child?.result ?: child?.description ?: ""),
                        )
                    },
        )

    /** Finds the card [blockId] in [messages] and lays out its sheet; null once the card is gone. */
    fun sheet(
        messages: List<SessionChatMessageItem>,
        blockId: String,
        children: Map<String, SessionChatChildActivity> = emptyMap(),
    ): SessionChatActivitySheetContent? {
        for (message in messages) {
            val block =
                message.blocks.firstOrNull { it.blockID == blockId } as? SessionChatTimelineBlock.ActivityCard ?: continue
            return SessionChatActivitySheetContent(
                messageId = message.id,
                blockId = blockId,
                isLive = block.isLive,
                stepCount = block.steps.size,
                startedAtMs = startedAtMs(block.steps),
                agents =
                    block.steps
                        .filter { it.kind == SessionChatStepKind.ChildSession }
                        .map { step -> sheetAgent(step, children) },
                steps = block.steps.filter { it.kind != SessionChatStepKind.ChildSession },
            )
        }
        return null
    }

    /**
     * Child sessions whose messages the screen model observes: running
     * children of a live card and of the card whose sheet is open.
     */
    fun observedChildSessionIds(
        messages: List<SessionChatMessageItem>,
        presentedBlockId: String?,
    ): Set<String> =
        buildSet {
            for (message in messages) {
                for (block in message.blocks) {
                    if (block !is SessionChatTimelineBlock.ActivityCard) continue
                    if (!block.isLive && block.blockID != presentedBlockId) continue
                    block.steps.forEach { step ->
                        val id = step.childSession?.childSessionId
                        if (step.kind == SessionChatStepKind.ChildSession && step.status.isInFlight && id != null) add(id)
                    }
                }
            }
        }

    fun hasCard(
        messages: List<SessionChatMessageItem>,
        blockId: String,
    ): Boolean = messages.any { message -> message.blocks.any { it.blockID == blockId && it is SessionChatTimelineBlock.ActivityCard } }

    /** Whether card [blockId] in [messages] still holds step [stepId]. */
    fun hasStep(
        messages: List<SessionChatMessageItem>,
        blockId: String,
        stepId: String,
    ): Boolean =
        messages.any { message ->
            message.blocks.any { block ->
                block.blockID == blockId && block is SessionChatTimelineBlock.ActivityCard && block.steps.any { it.id == stepId }
            }
        }

    /** A child session's visible tool steps from its own messages. */
    fun childActivity(messages: List<Message>): SessionChatChildActivity =
        SessionChatChildActivity(SessionChatTimelineBlocks.toolSteps(messages))

    /** Settled one-liner and live summary: `9 steps · 3 reads · 1 search`. */
    fun settledSummary(steps: List<SessionChatStep>): UiText =
        TimelineText.joined(listOf(UiText.plural(R.plurals.timeline_activity_count_step, steps.size)) + familyCounts(steps))
            ?: UiText.plural(R.plurals.timeline_activity_count_step, steps.size)

    /** The family list without the leading count: `3 reads · 1 search`. */
    fun familySummary(steps: List<SessionChatStep>): UiText = TimelineText.joined(familyCounts(steps)) ?: UiText.verbatim("")

    fun worstStatus(steps: List<SessionChatStep>): SessionChatStepStatus = SessionChatStepRail.worstStatus(steps)

    /**
     * Caption under a sheet step: the builder's detail (match count, failure),
     * or for an answered question the chosen answer.
     */
    fun sheetDetail(step: SessionChatStep): UiText? {
        step.detail?.let { return it }
        val isQuestion = step.kind == SessionChatStepKind.Tool && step.family == null
        return step.card?.subtitle?.takeIf { isQuestion && step.status == SessionChatStepStatus.Completed }
    }

    /** Deduplicated tool names in first-seen order: `Read, Grep, Edit, Agent`. */
    fun toolNames(steps: List<SessionChatStep>): List<UiText> = steps.map(::toolName).distinct()

    private fun toolName(step: SessionChatStep): UiText =
        when (step.kind) {
            SessionChatStepKind.Thought -> UiText.resource(R.string.tool_title_thought)
            SessionChatStepKind.ChildSession -> UiText.resource(R.string.tool_title_agent)
            SessionChatStepKind.Tool -> {
                val raw = step.card?.toolName.orEmpty()
                val title =
                    when (raw.lowercase()) {
                        "read" -> R.string.tool_title_read
                        "glob" -> R.string.tool_title_glob
                        "grep" -> R.string.tool_title_grep
                        "websearch", "codesearch" -> R.string.tool_title_search
                        "webfetch" -> R.string.tool_title_fetch
                        "edit", "apply_patch", "patch" -> R.string.tool_title_edit
                        "write" -> R.string.tool_title_write
                        "bash", "shell" -> R.string.tool_title_shell
                        "task", "subagent" -> R.string.tool_title_agent
                        "skill" -> R.string.tool_title_skill
                        "question" -> R.string.tool_title_question
                        // A question has no family; anything else outside the list keeps its own name.
                        else -> if (step.family == null) R.string.tool_title_question else null
                    }
                title?.let { UiText.resource(it) } ?: UiText.verbatim(raw.replaceFirstChar { it.uppercase() })
            }
        }

    private fun startedAtMs(steps: List<SessionChatStep>): Long? = steps.mapNotNull { it.startedAtMs }.minOrNull()

    /** A child session step's title is its agent name (server content). */
    private fun agentName(step: SessionChatStep): String =
        (step.childSession?.agent ?: (step.title as? UiText.Verbatim)?.value.orEmpty()).replaceFirstChar { it.uppercase() }

    private fun childAction(
        step: SessionChatStep,
        children: Map<String, SessionChatChildActivity>,
    ): UiText {
        val child = step.childSession
        val observed = child?.childSessionId?.let(children::get)?.currentAction
        return observed ?: UiText.verbatim(child?.description ?: "")
    }

    private fun sheetAgent(
        step: SessionChatStep,
        children: Map<String, SessionChatChildActivity>,
    ): SessionChatActivitySheetContent.Agent {
        val child = step.childSession
        val activity = child?.childSessionId?.let(children::get)
        val running = step.status.isInFlight
        val toolCount = activity?.toolCount?.takeIf { it > 0 } ?: child?.toolsSoFar
        val caption =
            when {
                running -> toolCount?.let { UiText.plural(R.plurals.session_chat_sheet_activity_tools_so_far, it) }
                step.status == SessionChatStepStatus.Failed && step.isInterrupted ->
                    UiText.resource(R.string.session_chat_sheet_activity_stopped)
                step.status == SessionChatStepStatus.Failed -> step.detail
                else -> TimelineText.verbatim(child?.result)
            }
        return SessionChatActivitySheetContent.Agent(
            step = step,
            agentName = agentName(step),
            description = child?.description,
            caption = caption,
            recent = if (running) activity?.steps?.takeLast(RECENT_CHILD_STEPS).orEmpty() else emptyList(),
        )
    }

    /** Families in first-seen order, each as `N word(s)`. */
    private fun familyCounts(steps: List<SessionChatStep>): List<UiText> {
        val counts = LinkedHashMap<SessionChatStepFamily, Int>()
        steps.forEach { step -> step.family?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts.map { (family, count) -> UiText.plural(family.countPlural, count) }
    }

    private val SessionChatStepFamily.countPlural: Int
        get() =
            when (this) {
                SessionChatStepFamily.Read -> R.plurals.tool_count_read
                SessionChatStepFamily.Search -> R.plurals.tool_count_search
                SessionChatStepFamily.Fetch -> R.plurals.tool_count_fetch
                SessionChatStepFamily.Edit -> R.plurals.tool_count_edit
                SessionChatStepFamily.Command -> R.plurals.timeline_activity_count_command
                SessionChatStepFamily.Skill -> R.plurals.tool_count_skill
                SessionChatStepFamily.Thought -> R.plurals.timeline_activity_count_thought
                SessionChatStepFamily.Agent -> R.plurals.timeline_activity_count_agent
            }

    private const val RECENT_CHILD_STEPS = 3
}
