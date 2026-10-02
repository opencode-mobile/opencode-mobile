package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.command.CommandInvocationCollapser
import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ThoughtPartChrome
import dev.opencode.mobile.domain.message.ToolPartIdentity
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.markdown.MarkdownDocument
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Session Chat presentation units inside one message timeline item.
 * Built from structured parts with hide rules and lightweight tool chrome.
 * Every block carries a stable [blockID] derived from server part IDs.
 */
sealed class SessionChatTimelineBlock {
    abstract val blockID: String

    data class Text(
        override val blockID: String,
        val text: String,
        val renderedMarkdown: MarkdownDocument? = null,
    ) : SessionChatTimelineBlock()

    data class Attachment(
        override val blockID: String,
        val attachment: SessionChatAttachmentItem,
    ) : SessionChatTimelineBlock()

    data class ToolCard(
        override val blockID: String,
        val toolName: String,
        val status: ToolStatus,
        val title: String,
        val subtitle: String? = null,
        val errorText: String? = null,
        val additions: Int? = null,
        val deletions: Int? = null,
        /** Non-null when the card should open a Bottom Sheet with this body. */
        val detailText: String? = null,
        /** Non-null when tap should open this child session's Session Chat. */
        val childSessionId: String? = null,
        /** When true, tap opens owning-turn Changes (optional File Viewer path). */
        val opensChangesReview: Boolean = false,
        val changesInitialFilePath: String? = null,
    ) : SessionChatTimelineBlock()

    data class ToolGroup(
        override val blockID: String,
        val count: Int,
        val status: ToolStatus,
        val title: String,
        val subtitle: String? = null,
        val errorText: String? = null,
        val members: List<ToolCard>,
    ) : SessionChatTimelineBlock()

    data class ChildSessionRow(
        override val blockID: String,
        val agentName: String,
        val letter: String,
        val isBackground: Boolean,
        val secondLine: String? = null,
        val timeStartEpochMs: Long? = null,
        val childSessionId: String? = null,
    ) : SessionChatTimelineBlock()

    data class Reasoning(
        override val blockID: String,
        val text: String,
        val isStreaming: Boolean,
        val durationMs: Long? = null,
    ) : SessionChatTimelineBlock()

    /**
     * Chip timeline (OPE-260): one narration paragraph plus the tools the agent
     * used under it. [text] is null for a paragraph that only holds chips.
     * [textBlockID] is the merged-text id the equivalent [Text] block would carry
     * so per-block rendered-markdown lookup keeps working.
     * [expansionID] is the paragraph's first part id: unlike [blockID] it does
     * not change as later tools stream in, so expansion state can key on it.
     * [exploreSummary] folds two or more explore tools (read, glob, grep,
     * search, fetch) that did not fail into one chip; those tools are not in
     * [chips]. [chips] holds every remaining tool, one chip per tool.
     */
    data class StepParagraph(
        override val blockID: String,
        val text: String?,
        val renderedMarkdown: MarkdownDocument? = null,
        val chips: List<ToolChip>,
        val errorLines: List<String>,
        val textBlockID: String? = null,
        val expansionID: String = blockID,
        val exploreSummary: ExploreSummary? = null,
    ) : SessionChatTimelineBlock()

    /**
     * The explore tools of a [StepParagraph] as one chip: `Explored · 4 reads ·
     * 2 greps`, or `Exploring · …` while any member is still running. Tapping
     * it opens the tool group sheet with [title], [subtitle] and [members].
     */
    data class ExploreSummary(
        val blockID: String,
        val label: String,
        val title: String,
        val subtitle: String?,
        val status: ToolStatus,
        val members: List<ToolCard>,
    )

    /**
     * One tool inside a [StepParagraph]; [card] keeps today's tap targets.
     * [id] is the first part id behind the chip and stays put while later
     * identical runs fold into it. [arrivalIndex] is the chip's position in
     * arrival order before the family sort; `+N more` expansion is scoped to
     * chips that existed at tap time by comparing against it.
     * [repeatCount] > 1 when identical consecutive completed runs were folded
     * into this chip; [card] is then the latest run.
     */
    data class ToolChip(
        val id: String,
        val kind: ToolChipKind,
        val family: ToolChipFamily,
        val label: String,
        /** Agent chips: first line of the description; skill chips: the skill directory. */
        val brief: String? = null,
        /**
         * Skill chips only: the requested skill id, stable from the first
         * in-flight update on, while [label] switches to the server-reported
         * name once the run settles.
         */
        val skillId: String? = null,
        val additions: Int? = null,
        val deletions: Int? = null,
        val status: ToolStatus,
        val trailingText: String? = null,
        val card: ToolCard,
        val repeatCount: Int = 1,
        val arrivalIndex: Int = 0,
    ) {
        /** Chip trailing text with the `×N` repeat marker ahead of any existing text. */
        val displayTrailingText: String?
            get() {
                val repeat = if (repeatCount > 1) "\u00d7$repeatCount" else null
                return listOfNotNull(repeat, trailingText?.takeIf { it.isNotEmpty() })
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" \u00b7 ")
            }
    }

    /**
     * False for blocks the timeline paints as nothing (a live thought is footer
     * chrome). Rows must not render empty: see AssistantMessageContent fallback.
     */
    val paintsInTimeline: Boolean
        get() =
            when (this) {
                is Reasoning -> !isStreaming
                else -> true
            }

    /** Narration a block carries for message-level markdown: text or a paragraph with text. */
    val markdownText: String?
        get() =
            when (this) {
                is Text -> text
                is StepParagraph -> text
                else -> null
            }

    val decoratedMarkdown: MarkdownDocument?
        get() =
            when (this) {
                is Text -> renderedMarkdown
                is StepParagraph -> renderedMarkdown
                else -> null
            }

    /** Copy with markdown attached; only [Text] and [StepParagraph] carry one. */
    fun withRenderedMarkdown(document: MarkdownDocument?): SessionChatTimelineBlock =
        when (this) {
            is Text -> copy(renderedMarkdown = document)
            is StepParagraph -> copy(renderedMarkdown = document)
            else -> this
        }
}

enum class ToolChipKind { File, Command, Agent, Skill }

/**
 * One skill a message loaded, for the Skills sheet a skill chip opens. [id]
 * is the chip id of the first run; [directory] is the server-reported skill
 * directory once the run settled.
 */
data class SessionChatSkillUse(
    val id: String,
    val label: String,
    val directory: String?,
    val status: ToolStatus,
)

/**
 * Truncation family; chips inside a paragraph are grouped in declaration order
 * (explore summary, changes, commands, agents, skills) so a new chip only
 * appends at the end of its group.
 */
enum class ToolChipFamily { Explore, Change, Command, Agent, Skill }

object SessionChatTimelineBlocks {
    private val contextToolNames = setOf("read", "glob", "grep", "websearch")

    fun build(
        role: MessageRole,
        parts: List<MessagePart>,
        collapseConsecutiveToolCalls: Boolean = false,
        chipTimeline: Boolean = false,
    ): SessionChatTimelineBlocksResult {
        if (parts.isEmpty()) return SessionChatTimelineBlocksResult.Ok(emptyList())
        if (chipTimeline && role == MessageRole.Assistant) return buildStepParagraphs(parts)

        val allowToolCards = role == MessageRole.Assistant
        val blocks = mutableListOf<SessionChatTimelineBlock>()
        val pendingText = mutableListOf<Pair<String, String>>()

        fun flushText(): Boolean {
            if (pendingText.isEmpty()) return true
            val merged = pendingText.joinToString("\n") { it.second }
            val ids = pendingText.map { it.first }
            pendingText.clear()
            if (merged.isNotEmpty()) {
                blocks +=
                    SessionChatTimelineBlock.Text(
                        blockID = PresentationBlockId.mergedText(ids),
                        text = merged,
                    )
            }
            return true
        }

        var index = 0
        while (index < parts.size) {
            when (val part = parts[index]) {
                is MessagePart.Text -> {
                    if (part.isDisplayable) {
                        val trimmed = part.text.trim()
                        if (trimmed.isNotEmpty()) {
                            val partId = part.id ?: return SessionChatTimelineBlocksResult.MissingPartIds
                            pendingText += partId to trimmed
                        }
                    }
                    index += 1
                }
                is MessagePart.Tool -> {
                    if (!allowToolCards || !isVisibleTool(part)) {
                        index += 1
                        continue
                    }
                    if (!flushText()) return SessionChatTimelineBlocksResult.MissingPartIds
                    if (isGroupableTool(part, collapseConsecutiveToolCalls)) {
                        val groupTools = mutableListOf<MessagePart.Tool>()
                        var lookAhead = index
                        while (lookAhead < parts.size) {
                            val candidatePart = parts[lookAhead]
                            if (isTransparentPart(candidatePart)) {
                                lookAhead += 1
                                continue
                            }
                            val candidate = candidatePart as? MessagePart.Tool ?: break
                            if (!isGroupableTool(candidate, collapseConsecutiveToolCalls)) break
                            groupTools += candidate
                            lookAhead += 1
                        }
                        if (groupTools.size >= 2) {
                            val group = toToolGroup(groupTools) ?: return SessionChatTimelineBlocksResult.MissingPartIds
                            blocks += group
                            index = lookAhead
                        } else {
                            val block = toToolBlock(part) ?: return SessionChatTimelineBlocksResult.MissingPartIds
                            blocks += block
                            index += 1
                        }
                    } else {
                        val block = toToolBlock(part) ?: return SessionChatTimelineBlocksResult.MissingPartIds
                        blocks += block
                        index += 1
                    }
                }
                is MessagePart.File -> {
                    if (!flushText()) return SessionChatTimelineBlocksResult.MissingPartIds
                    blocks +=
                        SessionChatTimelineBlock.Attachment(
                            blockID = PresentationBlockId.direct(part.id),
                            attachment = AttachmentPresentationMapper.from(part),
                        )
                    index += 1
                }
                is MessagePart.Reasoning -> {
                    if (!flushText()) return SessionChatTimelineBlocksResult.MissingPartIds
                    // Live thought is footer chrome (Thinking); only settled thoughts become rows.
                    if (ThoughtPartChrome.isVisible(part) && !part.isStreaming) {
                        val partId = part.id ?: return SessionChatTimelineBlocksResult.MissingPartIds
                        val text = ThoughtPartChrome.displayText(part)
                        blocks +=
                            SessionChatTimelineBlock.Reasoning(
                                blockID = PresentationBlockId.direct(partId),
                                text = text,
                                isStreaming = part.isStreaming,
                                durationMs = thoughtDurationMs(part.timeStartEpochMs, part.timeEndEpochMs),
                            )
                    }
                    index += 1
                }
                is MessagePart.Compaction -> {
                    // v1 marks compaction on the user message that asked for it;
                    // only the v2 compaction item (an assistant row) gets a marker.
                    if (allowToolCards) {
                        if (!flushText()) return SessionChatTimelineBlocksResult.MissingPartIds
                        blocks += compactionCard(part) ?: return SessionChatTimelineBlocksResult.MissingPartIds
                    }
                    index += 1
                }
            }
        }
        if (!flushText()) return SessionChatTimelineBlocksResult.MissingPartIds
        return SessionChatTimelineBlocksResult.Ok(blocks)
    }

    /**
     * Chip timeline walk: displayable text accumulates into the pending
     * paragraph, visible tools become chips under it, and text arriving after a
     * chip closes the paragraph. Attachments and settled thoughts are emitted as
     * today and do not close a paragraph.
     */
    private fun buildStepParagraphs(parts: List<MessagePart>): SessionChatTimelineBlocksResult {
        val blocks = mutableListOf<SessionChatTimelineBlock>()
        val pendingText = mutableListOf<Pair<String, String>>()
        val pendingTools = mutableListOf<MessagePart.Tool>()

        fun flushParagraph(): Boolean {
            if (pendingText.isEmpty() && pendingTools.isEmpty()) return true
            val textIds = pendingText.map { it.first }
            val text = pendingText.joinToString("\n") { it.second }.takeIf { it.isNotEmpty() }
            val toolIds = pendingTools.map { it.id ?: return false }
            val summaryTools = exploreSummaryTools(pendingTools)
            val summaryIds = summaryTools.mapTo(mutableSetOf()) { it.id }
            val exploreSummary = if (summaryTools.isEmpty()) null else exploreSummary(summaryTools) ?: return false
            val chips =
                orderedChips(
                    foldRepeatedChips(
                        pendingTools
                            .filter { it.id !in summaryIds }
                            .map { toToolChip(it) ?: return false },
                    ),
                )
            val partIds = textIds + toolIds
            blocks +=
                SessionChatTimelineBlock.StepParagraph(
                    blockID = PresentationBlockId.stepParagraph(partIds),
                    text = text,
                    chips = chips,
                    errorLines = chips.mapNotNull(::chipErrorLine),
                    textBlockID = if (text != null) PresentationBlockId.mergedText(textIds) else null,
                    expansionID = PresentationBlockId.stepParagraph(listOf(partIds.first())),
                    exploreSummary = exploreSummary,
                )
            pendingText.clear()
            pendingTools.clear()
            return true
        }

        for (part in parts) {
            when (part) {
                is MessagePart.Text -> {
                    if (!part.isDisplayable) continue
                    val trimmed = part.text.trim()
                    if (trimmed.isEmpty()) continue
                    val partId = part.id ?: return SessionChatTimelineBlocksResult.MissingPartIds
                    if (pendingTools.isNotEmpty() && !flushParagraph()) return SessionChatTimelineBlocksResult.MissingPartIds
                    pendingText += partId to trimmed
                }
                is MessagePart.Tool -> {
                    if (!isVisibleTool(part)) continue
                    if (part.id == null) return SessionChatTimelineBlocksResult.MissingPartIds
                    pendingTools += part
                }
                is MessagePart.File ->
                    blocks +=
                        SessionChatTimelineBlock.Attachment(
                            blockID = PresentationBlockId.direct(part.id),
                            attachment = AttachmentPresentationMapper.from(part),
                        )
                is MessagePart.Reasoning -> {
                    if (ThoughtPartChrome.isVisible(part) && !part.isStreaming) {
                        val partId = part.id ?: return SessionChatTimelineBlocksResult.MissingPartIds
                        val text = ThoughtPartChrome.displayText(part)
                        val duration = thoughtDurationMs(part.timeStartEpochMs, part.timeEndEpochMs)
                        // Pending narration and tools have not flushed a paragraph yet, so
                        // this thought is still consecutive with the previous row.
                        val previous = blocks.lastOrNull() as? SessionChatTimelineBlock.Reasoning
                        if (previous != null && !previous.isStreaming) {
                            blocks[blocks.lastIndex] =
                                SessionChatTimelineBlock.Reasoning(
                                    blockID = PresentationBlockId.mergedThought(listOf(previous.blockID, partId)),
                                    text = joinThoughts(previous.text, text),
                                    isStreaming = false,
                                    durationMs = sumThoughtDurations(previous.durationMs, duration),
                                )
                        } else {
                            blocks +=
                                SessionChatTimelineBlock.Reasoning(
                                    blockID = PresentationBlockId.direct(partId),
                                    text = text,
                                    isStreaming = false,
                                    durationMs = duration,
                                )
                        }
                    }
                }
                is MessagePart.Compaction -> {
                    if (!flushParagraph()) return SessionChatTimelineBlocksResult.MissingPartIds
                    blocks += compactionCard(part) ?: return SessionChatTimelineBlocksResult.MissingPartIds
                }
            }
        }
        if (!flushParagraph()) return SessionChatTimelineBlocksResult.MissingPartIds
        return SessionChatTimelineBlocksResult.Ok(blocks)
    }

    /**
     * The compaction marker: a card that opens the summary in the tool detail
     * sheet, so the summary never reads as reply text.
     */
    private fun compactionCard(part: MessagePart.Compaction): SessionChatTimelineBlock.ToolCard? {
        val partId = part.id ?: return null
        return SessionChatTimelineBlock.ToolCard(
            blockID = PresentationBlockId.direct(partId),
            toolName = SessionCompactChrome.MARKER_TOOL_NAME,
            status = if (part.completed) ToolStatus.Completed else ToolStatus.Running,
            title = if (part.completed) SessionCompactChrome.MARKER_TITLE else SessionCompactChrome.STATUS_LABEL,
            subtitle = if (part.auto) SessionCompactChrome.MARKER_AUTO_SUBTITLE else null,
            detailText = part.summary?.takeIf { it.isNotBlank() },
        )
    }

    /** A message that carries a compaction marker stays its own row. */
    private fun isCompactionBody(body: DisplayMessageBody): Boolean = body.message.parts.any { it is MessagePart.Compaction }

    /**
     * Explore tools that fold into the paragraph's summary chip: two or more
     * that did not fail. A single explore tool keeps its own chip (its path is
     * more useful than `1 read`); a failed one stays an attention chip with
     * its error line.
     */
    private fun exploreSummaryTools(tools: List<MessagePart.Tool>): List<MessagePart.Tool> {
        val candidates =
            tools.filter { chipFamily(it.toolName.lowercase()) == ToolChipFamily.Explore && it.status != ToolStatus.Error }
        return if (candidates.size >= 2) candidates else emptyList()
    }

    private fun exploreSummary(tools: List<MessagePart.Tool>): SessionChatTimelineBlock.ExploreSummary? {
        val memberIds = tools.map { it.id ?: return null }
        val members = tools.map { toToolCard(it) ?: return null }
        val running = tools.any { it.status == ToolStatus.Running || it.status == ToolStatus.Pending }
        val counts = exploreCounts(tools)
        return SessionChatTimelineBlock.ExploreSummary(
            blockID = PresentationBlockId.toolGroup(memberIds),
            label = (if (running) "Exploring" else "Explored") + " · " + counts,
            title = "Explored — $counts",
            subtitle = tools.firstNotNullOfOrNull(::contextSubtitle),
            status = if (running) ToolStatus.Running else ToolStatus.Completed,
            members = members,
        )
    }

    /** `4 reads · 2 greps` in first-encounter order. */
    private fun exploreCounts(tools: List<MessagePart.Tool>): String {
        val order = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()
        for (tool in tools) {
            val key = tool.toolName.lowercase()
            if (key !in counts) order += key
            counts[key] = (counts[key] ?: 0) + 1
        }
        return order.joinToString(" · ") { name -> exploreCountPhrase(name, counts.getValue(name)) }
    }

    private fun exploreCountPhrase(
        name: String,
        count: Int,
    ): String =
        when (name) {
            "read" -> if (count == 1) "1 read" else "$count reads"
            "grep" -> if (count == 1) "1 grep" else "$count greps"
            "glob" -> if (count == 1) "1 glob" else "$count globs"
            "websearch" -> if (count == 1) "1 search" else "$count searches"
            "webfetch" -> if (count == 1) "1 fetch" else "$count fetches"
            else -> "$count $name"
        }

    /**
     * Identical consecutive completed runs fold into one chip (`×N`) that
     * keeps the first run's id and the latest run's card. Chips are then
     * numbered in arrival order so expansion can be scoped to them.
     */
    private fun foldRepeatedChips(chips: List<SessionChatTimelineBlock.ToolChip>): List<SessionChatTimelineBlock.ToolChip> {
        val folded = mutableListOf<SessionChatTimelineBlock.ToolChip>()
        for (chip in chips) {
            val previous = folded.lastOrNull()
            if (previous != null && StepParagraphChips.canFold(previous, chip)) {
                folded[folded.lastIndex] = StepParagraphChips.fold(previous, chip)
            } else {
                folded += chip
            }
        }
        return folded.mapIndexed { index, chip -> chip.copy(arrivalIndex = index) }
    }

    /**
     * Skill name: the settled server output names it in metadata; before that
     * the request carries the skill id (older servers sent `name`).
     */
    private fun skillName(tool: MessagePart.Tool): String? = metadataString(tool.metadata, "name") ?: requestedSkillId(tool)

    /** The skill the request asked for; present from the first update on. */
    private fun requestedSkillId(tool: MessagePart.Tool): String? = inputString(tool.input, "id", "name", "skill")

    private fun skillDirectory(tool: MessagePart.Tool): String? = metadataString(tool.metadata, "directory")

    /**
     * Skills a message loaded, in first-use order across its step paragraphs.
     * Repeated loads of one skill collapse into one entry, keyed by the
     * requested skill id (the label once no id is known), that keeps the
     * first chip's id and the latest run's label, status and directory.
     */
    fun skillUses(blocks: List<SessionChatTimelineBlock>): List<SessionChatSkillUse> {
        val uses = linkedMapOf<String, SessionChatSkillUse>()
        blocks
            .filterIsInstance<SessionChatTimelineBlock.StepParagraph>()
            .flatMap { it.chips }
            .filter { it.kind == ToolChipKind.Skill }
            .forEach { chip ->
                val key = chip.skillId ?: chip.label
                val previous = uses[key]
                uses[key] =
                    SessionChatSkillUse(
                        id = previous?.id ?: chip.id,
                        label = chip.label,
                        directory = chip.brief ?: previous?.directory,
                        status = chip.status,
                    )
            }
        return uses.values.toList()
    }

    /** Fixed group order by [ToolChipFamily], arrival order inside a group. */
    private fun orderedChips(chips: List<SessionChatTimelineBlock.ToolChip>): List<SessionChatTimelineBlock.ToolChip> =
        chips.sortedBy { it.family.ordinal }

    private fun chipErrorLine(chip: SessionChatTimelineBlock.ToolChip): String? {
        if (chip.status != ToolStatus.Error) return null
        return "${chip.label}: ${chip.card.errorText ?: "Failed"}"
    }

    private fun toToolChip(tool: MessagePart.Tool): SessionChatTimelineBlock.ToolChip? {
        val partId = tool.id ?: return null
        val card = toToolCard(tool) ?: return null
        val name = tool.toolName.lowercase()
        val family = chipFamily(name)
        val kind =
            when (family) {
                ToolChipFamily.Skill -> ToolChipKind.Skill
                ToolChipFamily.Agent -> ToolChipKind.Agent
                ToolChipFamily.Explore, ToolChipFamily.Change -> ToolChipKind.File
                ToolChipFamily.Command -> ToolChipKind.Command
            }
        val isChange = family == ToolChipFamily.Change
        return SessionChatTimelineBlock.ToolChip(
            id = PresentationBlockId.direct(partId),
            kind = kind,
            family = family,
            label = chipLabel(name, tool, card),
            brief =
                when (family) {
                    ToolChipFamily.Agent -> chipBrief(tool)
                    ToolChipFamily.Skill -> skillDirectory(tool)
                    else -> null
                },
            skillId = if (family == ToolChipFamily.Skill) requestedSkillId(tool) else null,
            additions = card.additions.takeIf { isChange },
            deletions = card.deletions.takeIf { isChange },
            status = tool.status,
            trailingText = null,
            card = card,
        )
    }

    private fun chipFamily(name: String): ToolChipFamily =
        when {
            isChildSessionTool(name) -> ToolChipFamily.Agent
            name == "skill" -> ToolChipFamily.Skill
            name in contextToolNames || name == "webfetch" -> ToolChipFamily.Explore
            name == "edit" || name == "write" || name == "apply_patch" -> ToolChipFamily.Change
            else -> ToolChipFamily.Command
        }

    private fun chipLabel(
        name: String,
        tool: MessagePart.Tool,
        card: SessionChatTimelineBlock.ToolCard,
    ): String =
        when (name) {
            "read" -> readPathSubtitle(tool.input)?.let(::lastPathComponent)
            "edit", "write", "apply_patch" -> card.subtitle?.let(::lastPathComponent)
            "glob", "grep" -> inputString(tool.input, "pattern") ?: inputString(tool.input, "path", "directory")
            "websearch" -> inputString(tool.input, "query")
            "webfetch" -> inputString(tool.input, "url")?.let(::urlHost)
            "bash", "shell" -> inputString(tool.input, "command")?.let(StepParagraphChips::commandLabel)
            "execute" -> card.subtitle?.let(StepParagraphChips::commandLabel)
            "task", "subagent" -> taskAgentName(tool)
            "skill" -> "/" + (skillName(tool) ?: name)
            "lsp", "plan_exit" -> card.title
            else -> if (isQuestionTool(tool)) card.title else null
        } ?: tool.toolName

    private fun chipBrief(tool: MessagePart.Tool): String? =
        (inputString(tool.input, "description", "prompt") ?: childSessionTitle(tool))
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun lastPathComponent(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    private fun urlHost(url: String): String =
        runCatching { java.net.URI(url).host }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: url

    /** Convenience for call sites that fall back when identity is incomplete. */
    fun buildOrFallback(
        role: MessageRole,
        parts: List<MessagePart>,
        messageId: String,
        fallbackText: String,
        collapseConsecutiveToolCalls: Boolean = false,
        chipTimeline: Boolean = false,
    ): List<SessionChatTimelineBlock> {
        fun fallbackBlocks(): List<SessionChatTimelineBlock> =
            if (fallbackText.isNotBlank()) {
                listOf(
                    SessionChatTimelineBlock.Text(
                        blockID = PresentationBlockId.messageFallback(messageId),
                        text = fallbackText,
                    ),
                )
            } else {
                emptyList()
            }

        return when (val result = build(role, parts, collapseConsecutiveToolCalls, chipTimeline)) {
            is SessionChatTimelineBlocksResult.Ok ->
                result.blocks.ifEmpty { fallbackBlocks() }
            SessionChatTimelineBlocksResult.MissingPartIds -> fallbackBlocks()
        }
    }

    /**
     * Only the latest narration paragraph is live. Earlier paragraphs in a
     * merged row stay settled while a later step is still streaming.
     */
    fun streamingStepParagraphBlockId(
        blocks: List<SessionChatTimelineBlock>,
        messageIsStreaming: Boolean,
    ): String? {
        if (!messageIsStreaming) return null
        return blocks.lastOrNull { it is SessionChatTimelineBlock.StepParagraph && it.text != null }?.blockID
    }

    /**
     * TUI starts a fragment on `session.text.started` before any delta. Keep a
     * stable row so an empty streaming assistant does not vanish from the timeline.
     * Hidden-tool-only messages stay omitted.
     */
    fun ensureLiveRow(
        message: Message,
        isStreaming: Boolean,
        blocks: List<SessionChatTimelineBlock>,
    ): List<SessionChatTimelineBlock> {
        if (blocks.isNotEmpty()) return blocks
        if (!isStreaming) return emptyList()
        return listOf(
            SessionChatTimelineBlock.Text(
                blockID = PresentationBlockId.messageFallback(message.id),
                text = message.text,
            ),
        )
    }

    /**
     * Rows never render empty: drop blocks the timeline paints as nothing
     * (live thought is footer chrome) and fall back to the live placeholder.
     */
    fun paintedBlocks(message: SessionChatMessageItem): List<SessionChatTimelineBlock> {
        val painted = message.blocks.filter { it.paintsInTimeline }
        if (painted.isEmpty()) {
            return listOf(
                SessionChatTimelineBlock.Text(
                    blockID = PresentationBlockId.messageFallback(message.id),
                    text = message.text,
                ),
            )
        }
        return painted
    }

    /**
     * When collapse is on, consecutive assistant messages whose visible blocks
     * are only groupable tools become one tool group. OpenCode emits each
     * sequential tool step as its own assistant message; grouping only inside
     * one message would leave a stack of Shell cards. Visible text, attachments,
     * and `task` tools still break a run.
     */
    fun mergeConsecutiveToolOnlyAssistantBodies(
        bodies: List<DisplayMessageBody>,
        collapseConsecutiveToolCalls: Boolean,
        chipTimeline: Boolean = false,
    ): List<DisplayMessageBody> {
        if (chipTimeline) return mergeConsecutiveAssistantBodiesForChips(bodies, collapseConsecutiveToolCalls)
        if (!collapseConsecutiveToolCalls || bodies.size < 2) return bodies
        val merged = mutableListOf<DisplayMessageBody>()
        var index = 0
        while (index < bodies.size) {
            if (!isMergeableToolOnlyAssistant(bodies[index])) {
                merged += bodies[index]
                index += 1
                continue
            }
            var end = index + 1
            while (end < bodies.size && isMergeableToolOnlyAssistant(bodies[end])) {
                end += 1
            }
            if (end == index + 1) {
                merged += bodies[index]
                index += 1
                continue
            }
            val run = bodies.subList(index, end)
            val parts = run.flatMap { it.message.parts }
            val first = bodies[index]
            val blocks =
                buildOrFallback(
                    role = MessageRole.Assistant,
                    parts = parts,
                    messageId = first.message.id,
                    fallbackText = "",
                    collapseConsecutiveToolCalls = true,
                )
            merged += DisplayMessageBody(message = first.message, blocks = blocks)
            index = end
        }
        return merged
    }

    /**
     * Chip timeline: v1 emits each model step as its own assistant message, so
     * every run of consecutive assistant bodies (text included) becomes one body
     * and a paragraph joins the tools that follow it in the next message. A run
     * whose parts lack stable ids is left unmerged rather than dropped.
     */
    private fun mergeConsecutiveAssistantBodiesForChips(
        bodies: List<DisplayMessageBody>,
        collapseConsecutiveToolCalls: Boolean,
    ): List<DisplayMessageBody> {
        if (bodies.size < 2) return bodies
        val merged = mutableListOf<DisplayMessageBody>()
        var index = 0
        while (index < bodies.size) {
            if (bodies[index].message.role != MessageRole.Assistant || isCompactionBody(bodies[index])) {
                merged += bodies[index]
                index += 1
                continue
            }
            var end = index + 1
            while (end < bodies.size &&
                bodies[end].message.role == MessageRole.Assistant &&
                !isCompactionBody(bodies[end])
            ) {
                end += 1
            }
            if (end == index + 1) {
                merged += bodies[index]
                index += 1
                continue
            }
            val run = bodies.subList(index, end)
            val first = run.first()
            val parts = run.flatMap { it.message.parts }
            val result =
                build(
                    role = MessageRole.Assistant,
                    parts = parts,
                    collapseConsecutiveToolCalls = collapseConsecutiveToolCalls,
                    chipTimeline = true,
                )
            when (result) {
                // Streaming is derived from parts, so the merged parts carry
                // "any member streaming".
                is SessionChatTimelineBlocksResult.Ok ->
                    merged +=
                        DisplayMessageBody(
                            message = first.message.copy(parts = parts),
                            blocks = reattachMarkdown(result.blocks, run),
                        )
                SessionChatTimelineBlocksResult.MissingPartIds -> merged += run
            }
            index = end
        }
        return merged
    }

    /** Rebuilt paragraphs reuse the markdown their source bodies already carried. */
    private fun reattachMarkdown(
        blocks: List<SessionChatTimelineBlock>,
        run: List<DisplayMessageBody>,
    ): List<SessionChatTimelineBlock> {
        val markdownByTextId =
            buildMap {
                run.forEach { body ->
                    body.blocks.forEach { block ->
                        val id =
                            when (block) {
                                is SessionChatTimelineBlock.Text -> block.blockID
                                is SessionChatTimelineBlock.StepParagraph -> block.textBlockID
                                else -> null
                            }
                        val document = block.decoratedMarkdown
                        if (id != null && document != null) put(id, document)
                    }
                }
            }
        if (markdownByTextId.isEmpty()) return blocks
        return blocks.map { block ->
            if (block is SessionChatTimelineBlock.StepParagraph && block.renderedMarkdown == null) {
                markdownByTextId[block.textBlockID]?.let { block.copy(renderedMarkdown = it) } ?: block
            } else {
                block
            }
        }
    }

    private fun isMergeableToolOnlyAssistant(body: DisplayMessageBody): Boolean {
        if (body.message.role != MessageRole.Assistant || body.blocks.isEmpty()) return false
        if (isCompactionBody(body)) return false
        return body.blocks.all { isGroupableToolBlock(it) }
    }

    private fun isGroupableToolBlock(block: SessionChatTimelineBlock): Boolean =
        when (block) {
            is SessionChatTimelineBlock.ToolGroup -> true
            is SessionChatTimelineBlock.ToolCard -> !isChildSessionTool(block.toolName)
            is SessionChatTimelineBlock.Text,
            is SessionChatTimelineBlock.Attachment,
            is SessionChatTimelineBlock.ChildSessionRow,
            is SessionChatTimelineBlock.Reasoning,
            is SessionChatTimelineBlock.StepParagraph,
            -> false
        }

    /**
     * Rewrites user text blocks that are expanded slash-command / skill
     * templates into `[/name] args` for Session Chat display.
     */
    fun collapseCommandInvocations(
        role: MessageRole,
        blocks: List<SessionChatTimelineBlock>,
        commands: List<ServerCommand>,
    ): List<SessionChatTimelineBlock> {
        if (role != MessageRole.User || commands.isEmpty() || blocks.isEmpty()) return blocks
        return blocks.map { block ->
            if (block !is SessionChatTimelineBlock.Text) return@map block
            val collapsed = CommandInvocationCollapser.collapse(block.text, commands) ?: return@map block
            block.copy(text = collapsed.text, renderedMarkdown = null)
        }
    }

    fun thoughtDurationLabel(durationMs: Long?): String? {
        val duration = durationMs ?: return null
        if (duration < 0) return null
        return when {
            duration < 1_000 -> "${duration}ms"
            duration < 60_000 -> "${"%.1f".format(duration / 1000.0)}s"
            duration < 3_600_000 -> {
                val minutes = duration / 60_000
                val seconds = (duration % 60_000) / 1_000
                "${minutes}m ${seconds}s"
            }
            else -> {
                val hours = duration / 3_600_000
                val minutes = (duration % 3_600_000) / 60_000
                "${hours}h ${minutes}m"
            }
        }
    }

    private fun joinThoughts(
        first: String,
        second: String,
    ): String =
        when {
            first.isEmpty() -> second
            second.isEmpty() -> first
            else -> first + "\n\n" + second
        }

    private fun sumThoughtDurations(
        first: Long?,
        second: Long?,
    ): Long? =
        when {
            first == null -> second
            second == null -> first
            else -> first + second
        }

    private fun thoughtDurationMs(
        start: Long?,
        end: Long?,
    ): Long? {
        if (start == null || end == null) return null
        val duration = end - start
        return duration.takeIf { it >= 0 }
    }

    private fun isLiveReasoning(part: MessagePart.Reasoning): Boolean = ThoughtPartChrome.isVisible(part)

    private fun isVisibleTool(tool: MessagePart.Tool): Boolean = ToolPartIdentity.isVisibleOnTimeline(tool)

    /**
     * Parts that never become timeline blocks. Grouping looks through them so
     * hidden todos, empty text, and compaction markers do not split a visible
     * tool run (web filters to renderable parts before grouping).
     */
    private fun isTransparentPart(part: MessagePart): Boolean =
        when (part) {
            is MessagePart.Compaction -> true
            is MessagePart.Text -> !part.isDisplayable || part.text.isBlank()
            is MessagePart.Tool -> !isVisibleTool(part)
            is MessagePart.File -> false
            is MessagePart.Reasoning -> !isLiveReasoning(part)
        }

    private fun isContextTool(toolName: String): Boolean = toolName.lowercase() in contextToolNames

    /**
     * A visible tool can join a group. Child-session tools (`task` / v2
     * `subagent`) never group so their child-session tap stays available; when
     * collapse is on every other visible tool groups, otherwise only context
     * tools do.
     */
    private fun isGroupableTool(
        tool: MessagePart.Tool,
        collapse: Boolean,
    ): Boolean {
        if (isChildSessionTool(tool.toolName)) return false
        if (collapse) return true
        return isContextTool(tool.toolName)
    }

    private fun toToolGroup(tools: List<MessagePart.Tool>): SessionChatTimelineBlock.ToolGroup? {
        val memberIds = tools.map { it.id ?: return null }
        val members =
            tools.map { tool ->
                toToolCard(tool) as? SessionChatTimelineBlock.ToolCard ?: return null
            }
        val status = worstStatus(tools.map { it.status })
        val errorText =
            tools
                .firstOrNull { it.status == ToolStatus.Error }
                ?.error
                ?.takeIf { it.isNotEmpty() }
        return SessionChatTimelineBlock.ToolGroup(
            blockID = PresentationBlockId.toolGroup(memberIds),
            count = tools.size,
            status = status,
            title = groupTitle(tools),
            subtitle = groupSubtitle(tools),
            errorText = errorText,
            members = members,
        )
    }

    /** Context-only groups match TUI (`Explored — 1 read`). Mixed groups stay `N tools`. */
    private fun groupTitle(tools: List<MessagePart.Tool>): String =
        if (tools.isNotEmpty() && tools.all { isContextTool(it.toolName) }) {
            exploredTitle(tools)
        } else {
            "${tools.size} tools"
        }

    /**
     * Family one-liner in encounter order (`Edit · Write · 5 shells`).
     * A single member of a family is just the verb; multiples get a count.
     */
    private fun groupSubtitle(tools: List<MessagePart.Tool>): String? {
        if (tools.isNotEmpty() && tools.all { isContextTool(it.toolName) }) {
            return tools.firstNotNullOfOrNull(::contextSubtitle)
        }
        val order = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()
        for (tool in tools) {
            val key = tool.toolName.lowercase()
            if (key !in counts) order += key
            counts[key] = (counts[key] ?: 0) + 1
        }
        return order.joinToString(" · ") { name ->
            familyPhrase(name, counts.getValue(name))
        }
    }

    private fun exploredTitle(tools: List<MessagePart.Tool>): String {
        val order = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()
        for (tool in tools) {
            val key = tool.toolName.lowercase()
            if (key !in counts) order += key
            counts[key] = (counts[key] ?: 0) + 1
        }
        val summary =
            order.joinToString(" · ") { name ->
                exploredFamilyPhrase(name, counts.getValue(name))
            }
        return "Explored — $summary"
    }

    private fun exploredFamilyPhrase(
        name: String,
        count: Int,
    ): String =
        when (name) {
            "read" -> if (count == 1) "1 read" else "$count reads"
            "grep" -> if (count == 1) "1 grep" else "$count greps"
            "glob" -> if (count == 1) "1 glob" else "$count globs"
            "websearch" -> if (count == 1) "1 search" else "$count searches"
            else -> familyPhrase(name, count)
        }

    private fun contextSubtitle(tool: MessagePart.Tool): String? =
        when (tool.toolName.lowercase()) {
            "read" -> readPathSubtitle(tool.input)
            "glob", "grep" -> patternPathSubtitle(tool.input)
            "websearch" -> inputString(tool.input, "query")
            else -> null
        }

    private fun familyPhrase(
        name: String,
        count: Int,
    ): String =
        when (name) {
            "read" -> if (count == 1) "Read" else "$count reads"
            "grep" -> if (count == 1) "Grep" else "$count greps"
            "glob" -> if (count == 1) "Glob" else "$count globs"
            "bash", "shell" -> if (count == 1) "Shell" else "$count shells"
            "execute" -> if (count == 1) "Execute" else "$count executes"
            "lsp" -> if (count == 1) "LSP" else "$count LSPs"
            "plan_exit" -> if (count == 1) "Plan" else "$count plans"
            "edit" -> if (count == 1) "Edit" else "$count edits"
            "write" -> if (count == 1) "Write" else "$count writes"
            "webfetch" -> if (count == 1) "Fetch" else "$count fetches"
            "websearch" -> if (count == 1) "Search" else "$count searches"
            "question" -> askedQuestionsTitle(count)
            "skill" -> if (count == 1) "Skill" else "$count skills"
            "apply_patch" -> if (count == 1) "Patch" else "$count patches"
            "linear_get_issue" -> if (count == 1) "Get issue" else "$count Get issues"
            "linear_save_issue" -> if (count == 1) "Save issue" else "$count Save issues"
            else -> if (count == 1) name else "$count $name"
        }

    private fun worstStatus(statuses: List<ToolStatus>): ToolStatus {
        if (statuses.any { it == ToolStatus.Error }) return ToolStatus.Error
        if (statuses.any { it == ToolStatus.Running || it == ToolStatus.Pending }) {
            return ToolStatus.Running
        }
        return ToolStatus.Completed
    }

    private fun toToolBlock(tool: MessagePart.Tool): SessionChatTimelineBlock? {
        if (isChildSessionTool(tool.toolName) && tool.status != ToolStatus.Error) {
            return toChildSessionRow(tool)
        }
        return toToolCard(tool)
    }

    private fun toChildSessionRow(tool: MessagePart.Tool): SessionChatTimelineBlock.ChildSessionRow? {
        val partId = tool.id ?: return null
        val agentName = taskAgentName(tool)
        val secondLine =
            inputString(tool.input, "description", "prompt")
                ?: childSessionTitle(tool)
        return SessionChatTimelineBlock.ChildSessionRow(
            blockID = PresentationBlockId.direct(partId),
            agentName = agentName,
            letter = SessionChatChildSessionElapsed.letter(from = agentName),
            isBackground = isTruthyMetadata(tool.metadata, "background"),
            secondLine = secondLine,
            timeStartEpochMs =
                if (tool.status == ToolStatus.Pending || tool.status == ToolStatus.Running) {
                    tool.timeStartEpochMs
                } else {
                    null
                },
            childSessionId = childSessionId(tool),
        )
    }

    private fun toToolCard(tool: MessagePart.Tool): SessionChatTimelineBlock.ToolCard? {
        val partId = tool.id ?: return null
        val name = tool.toolName.lowercase()
        val chrome = specializedChrome(name, tool) ?: genericChrome(tool)
        val errorText =
            when (tool.status) {
                ToolStatus.Error -> tool.error?.takeIf { it.isNotEmpty() }
                else -> null
            }
        return SessionChatTimelineBlock.ToolCard(
            blockID = PresentationBlockId.direct(partId),
            toolName = tool.toolName,
            status = tool.status,
            title = chrome.title,
            subtitle = chrome.subtitle,
            errorText = errorText,
            additions = chrome.additions,
            deletions = chrome.deletions,
            detailText = chrome.detailText,
            childSessionId = chrome.childSessionId,
            opensChangesReview = chrome.opensChangesReview,
            changesInitialFilePath = chrome.changesInitialFilePath,
        )
    }

    private data class Chrome(
        val title: String,
        val subtitle: String?,
        val additions: Int? = null,
        val deletions: Int? = null,
        val detailText: String? = null,
        val childSessionId: String? = null,
        val opensChangesReview: Boolean = false,
        val changesInitialFilePath: String? = null,
    )

    private fun genericChrome(tool: MessagePart.Tool): Chrome {
        val subtitle =
            inputPreview(tool.input)
                ?: tool.title?.takeIf { it.isNotEmpty() && !it.equals(tool.toolName, ignoreCase = true) }
                ?: runningTitleOverride(tool)
        return Chrome(
            title = ToolPartIdentity.calledTitle(tool.toolName),
            subtitle = subtitle,
            detailText = settledDetail(tool),
        )
    }

    private fun specializedChrome(
        name: String,
        tool: MessagePart.Tool,
    ): Chrome? {
        if (isQuestionTool(tool)) return questionChrome(tool)
        return when (name) {
            "read" -> contextChrome(tool, subtitle = readPathSubtitle(tool.input))
            "glob" -> contextChrome(tool, subtitle = patternPathSubtitle(tool.input))
            "grep" -> contextChrome(tool, subtitle = patternPathSubtitle(tool.input))
            "bash", "shell" -> shellChrome(tool)
            "execute" -> executeChrome(tool)
            "lsp" -> lspChrome(tool)
            "plan_exit" -> planExitChrome(tool)
            "webfetch" -> networkChrome(tool, displayTitle = "Fetch", subtitleKey = "url")
            "websearch" -> contextChrome(tool, subtitle = inputString(tool.input, "query"))
            "skill" ->
                Chrome(
                    title = "Skill",
                    subtitle = skillName(tool) ?: runningTitleOverride(tool),
                )
            "task", "subagent" -> taskChrome(tool)
            "edit" -> fileChangeChrome(tool, displayTitle = "Edit")
            "write" -> fileChangeChrome(tool, displayTitle = "Write")
            "apply_patch" -> fileChangeChrome(tool, displayTitle = "Patch")
            else -> null
        }
    }

    private fun fileChangeChrome(
        tool: MessagePart.Tool,
        displayTitle: String,
    ): Chrome {
        val filesArray = tool.metadata?.get("files") as? JsonArray
        val patchFiles = metadataFiles(tool.metadata)
        if (filesArray != null && filesArray.size > 1) {
            val additions = positiveCount(patchFiles.sumOf { it.additions ?: 0 })
            val deletions = positiveCount(patchFiles.sumOf { it.deletions ?: 0 })
            return Chrome(
                title = "Patch",
                subtitle = "${filesArray.size} files",
                additions = additions,
                deletions = deletions,
                opensChangesReview = canOpenChangesReview(tool.status),
                changesInitialFilePath = null,
            )
        }

        val singlePatchFile = patchFiles.singleOrNull()
        val path =
            metadataFilediffString(tool.metadata, "file")
                ?: singlePatchFile?.path
                ?: pathSubtitle(tool.input)
        val additions =
            positiveCount(
                metadataFilediffInt(tool.metadata, "additions")
                    ?: singlePatchFile?.additions,
            )
        val deletions =
            positiveCount(
                metadataFilediffInt(tool.metadata, "deletions")
                    ?: singlePatchFile?.deletions,
            )
        val opens = path != null && canOpenChangesReview(tool.status)
        return Chrome(
            title = displayTitle,
            subtitle = path,
            additions = additions.takeIf { path != null },
            deletions = deletions.takeIf { path != null },
            opensChangesReview = opens,
            changesInitialFilePath = path.takeIf { opens },
        )
    }

    private fun canOpenChangesReview(status: ToolStatus): Boolean = status == ToolStatus.Completed || status == ToolStatus.Error

    private data class PatchFileEntry(
        val path: String,
        val additions: Int?,
        val deletions: Int?,
    )

    private fun metadataFiles(metadata: JsonObject?): List<PatchFileEntry> {
        val array = metadata?.get("files") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val path =
                stringFromObject(obj, "filePath", "relativePath", "file")
                    ?: return@mapNotNull null
            PatchFileEntry(
                path = path,
                additions = intFromObject(obj, "additions"),
                deletions = intFromObject(obj, "deletions"),
            )
        }
    }

    private fun metadataFilediffString(
        metadata: JsonObject?,
        key: String,
    ): String? {
        val filediff = metadata?.get("filediff") as? JsonObject ?: return null
        return stringFromObject(filediff, key)
    }

    private fun metadataFilediffInt(
        metadata: JsonObject?,
        key: String,
    ): Int? {
        val filediff = metadata?.get("filediff") as? JsonObject ?: return null
        return intFromObject(filediff, key)
    }

    private fun stringFromObject(
        obj: JsonObject,
        vararg keys: String,
    ): String? {
        for (key in keys) {
            val value = (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            if (value != null) return value
        }
        return null
    }

    private fun intFromObject(
        obj: JsonObject,
        key: String,
    ): Int? = (obj[key] as? JsonPrimitive)?.intOrNull

    private fun positiveCount(value: Int?): Int? = value?.takeIf { it > 0 }

    private fun taskChrome(tool: MessagePart.Tool): Chrome {
        val title = taskAgentName(tool)
        val description =
            inputString(tool.input, "description", "prompt")
                ?: runningTitleOverride(tool)
        val subtitle =
            when {
                description != null && isTruthyMetadata(tool.metadata, "background") ->
                    "$description (background)"
                else -> description
            }
        return Chrome(
            title = title,
            subtitle = subtitle,
            childSessionId = childSessionId(tool),
        )
    }

    private fun taskAgentName(tool: MessagePart.Tool): String =
        inputString(tool.input, "agent", "subagent_type")
            ?: metadataString(tool.metadata, "agent")
            ?: if (tool.toolName.equals("subagent", ignoreCase = true)) "General" else "Task"

    private fun childSessionTitle(tool: MessagePart.Tool): String? {
        val title = tool.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (title.equals(tool.toolName, ignoreCase = true)) return null
        if (isCompositeChildTitle(title)) return null
        return title
    }

    private fun isCompositeChildTitle(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains(" subagent") ||
            title.contains(" — ") ||
            title.contains(" – ")
    }

    private fun isChildSessionTool(toolName: String): Boolean {
        val name = toolName.lowercase()
        return name == "task" || name == "subagent"
    }

    private fun childSessionId(tool: MessagePart.Tool): String? = metadataString(tool.metadata, "sessionId", "sessionID")

    private fun contextChrome(
        tool: MessagePart.Tool,
        subtitle: String?,
    ): Chrome =
        Chrome(
            title = exploredTitle(listOf(tool)),
            subtitle = runningTitleOverride(tool) ?: subtitle,
        )

    private fun shellChrome(tool: MessagePart.Tool): Chrome {
        val command =
            inputString(tool.input, "command")
                ?: runningTitleOverride(tool)
        return Chrome(
            title = "Shell",
            subtitle = command,
            detailText = settledDetail(tool),
        )
    }

    private fun executeChrome(tool: MessagePart.Tool): Chrome {
        val calls = executeCallPreviews(tool.metadata)
        val subtitle =
            when {
                calls.isEmpty() ->
                    runningTitleOverride(tool)
                        ?: tool.title?.takeIf { it.isNotEmpty() }
                calls.size == 1 -> calls.first()
                else -> "${calls.size} tools"
            }
        val detail =
            buildString {
                if (calls.isNotEmpty()) append(calls.joinToString("\n"))
                val extra = settledDetail(tool)
                if (extra != null) {
                    if (isNotEmpty()) append("\n\n")
                    append(extra)
                }
            }.takeIf { it.isNotEmpty() }
        return Chrome(title = "Execute", subtitle = subtitle, detailText = detail)
    }

    private fun executeCallPreviews(metadata: JsonObject?): List<String> {
        val array = metadata?.get("toolCalls") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["tool"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val preview = inputPreview(obj["input"] as? JsonObject)
            if (preview != null) "$name $preview" else name
        }
    }

    private fun lspChrome(tool: MessagePart.Tool): Chrome {
        val operation = inputString(tool.input, "operation")
        val path = pathSubtitle(tool.input)
        val line = inputString(tool.input, "line")
        val query = inputString(tool.input, "query")
        val location =
            when {
                path != null && line != null -> "$path:$line"
                path != null -> path
                query != null -> query
                else -> null
            }
        val subtitle =
            when {
                operation != null && location != null -> "$operation · $location"
                operation != null -> operation
                location != null -> location
                else ->
                    runningTitleOverride(tool)
                        ?: tool.title?.takeIf { it.isNotEmpty() }
            }
        return Chrome(title = "LSP", subtitle = subtitle, detailText = settledDetail(tool))
    }

    private fun planExitChrome(tool: MessagePart.Tool): Chrome =
        Chrome(
            title = "Plan",
            subtitle =
                tool.title?.takeIf { it.isNotEmpty() }
                    ?: "Ready to implement?",
            detailText = settledDetail(tool),
        )

    private fun settledDetail(tool: MessagePart.Tool): String? =
        when (tool.status) {
            ToolStatus.Completed, ToolStatus.Error ->
                tool.output?.takeIf { it.isNotEmpty() }
                    ?: tool.error?.takeIf { it.isNotEmpty() }
            ToolStatus.Pending, ToolStatus.Running -> null
        }

    private fun inputPreview(input: JsonObject?): String? {
        if (input == null) return null
        inputString(
            input,
            "command",
            "filePath",
            "path",
            "file",
            "url",
            "query",
            "pattern",
            "name",
            "description",
            "prompt",
            "text",
            "id",
        )?.let { return it }
        val parts =
            input.mapNotNull { (key, value) ->
                val primitive = value as? JsonPrimitive ?: return@mapNotNull null
                val text = primitive.content.takeIf { it.isNotEmpty() && it.length <= 80 } ?: return@mapNotNull null
                "$key=$text"
            }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "[$it]" }
    }

    private fun networkChrome(
        tool: MessagePart.Tool,
        displayTitle: String,
        subtitleKey: String,
    ): Chrome {
        val subtitle =
            inputString(tool.input, subtitleKey)
                ?: runningTitleOverride(tool)
        val detail =
            when (tool.status) {
                ToolStatus.Completed, ToolStatus.Error ->
                    tool.output?.takeIf { it.isNotEmpty() }
                        ?: tool.error?.takeIf { it.isNotEmpty() }
                ToolStatus.Pending, ToolStatus.Running -> null
            }
        return Chrome(
            title = displayTitle,
            subtitle = subtitle,
            detailText = detail,
        )
    }

    private fun runningTitleOverride(tool: MessagePart.Tool): String? =
        when (tool.status) {
            ToolStatus.Running -> tool.title?.takeIf { it.isNotEmpty() }
            else -> null
        }

    private fun pathSubtitle(input: JsonObject?): String? = inputString(input, "filePath", "path", "file")

    /** Read chrome is the file, not explore/cwd `path` (often the project root). */
    private fun readPathSubtitle(input: JsonObject?): String? = inputString(input, "filePath", "file")

    private fun patternPathSubtitle(input: JsonObject?): String? {
        val pattern = inputString(input, "pattern")
        val path = inputString(input, "path", "directory")
        return when {
            pattern != null && path != null -> "$pattern · $path"
            pattern != null -> pattern
            path != null -> path
            else -> null
        }
    }

    private fun isQuestionTool(tool: MessagePart.Tool): Boolean = ToolPartIdentity.isQuestion(tool.toolName, tool.input)

    private fun askedQuestionsTitle(count: Int): String = if (count == 1) "Asked 1 question" else "Asked $count questions"

    private fun questionChrome(tool: MessagePart.Tool): Chrome {
        val questions = questionTexts(tool.input)
        val answers = answerTexts(tool.metadata)
        val firstQuestion =
            questions.firstOrNull()
                ?: inputString(tool.input, "prompt", "text", "question")
                ?: runningTitleOverride(tool)
        val firstAnswer = answers.firstOrNull()
        val dismissed = if (tool.status == ToolStatus.Error) tool.error?.takeIf { it.isNotEmpty() } else null
        val count = questions.size.coerceAtLeast(if (firstQuestion != null) 1 else 0)
        return Chrome(
            title = askedQuestionsTitle(count),
            subtitle = firstAnswer,
            detailText =
                questionDetail(
                    questions = questions.ifEmpty { listOfNotNull(firstQuestion) },
                    answers = answers,
                    error = dismissed,
                ),
        )
    }

    private fun questionTexts(input: JsonObject?): List<String> {
        val array = input?.get("questions") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            (obj["question"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        }
    }

    private fun answerTexts(metadata: JsonObject?): List<String> {
        val array = metadata?.get("answers") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonArray ->
                    element
                        .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { text -> text.isNotEmpty() } }
                        .joinToString(", ")
                        .takeIf { it.isNotEmpty() }
                is JsonPrimitive -> element.content.takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }

    private fun questionDetail(
        questions: List<String>,
        answers: List<String>,
        error: String? = null,
    ): String? {
        val count = maxOf(questions.size, answers.size)
        val pairs =
            (0 until count).map { index ->
                val question = questions.getOrNull(index).orEmpty()
                val answer = answers.getOrNull(index).orEmpty()
                when {
                    question.isNotEmpty() && answer.isNotEmpty() -> "$question\n$answer"
                    question.isNotEmpty() -> question
                    else -> answer
                }
            }
        return (pairs.filter { it.isNotEmpty() } + listOfNotNull(error))
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
    }

    private fun inputString(
        input: JsonObject?,
        vararg keys: String,
    ): String? {
        if (input == null) return null
        for (key in keys) {
            val primitive = input[key] as? JsonPrimitive ?: continue
            val value = primitive.content.takeIf { it.isNotEmpty() } ?: continue
            return value
        }
        return null
    }

    private fun metadataString(
        metadata: JsonObject?,
        vararg keys: String,
    ): String? = inputString(metadata, *keys)

    private fun isTruthyMetadata(
        metadata: JsonObject?,
        key: String,
    ): Boolean {
        val primitive = metadata?.get(key) as? JsonPrimitive ?: return false
        primitive.booleanOrNull?.let { return it }
        val content = primitive.content.lowercase()
        return content == "true" || content == "1"
    }
}
