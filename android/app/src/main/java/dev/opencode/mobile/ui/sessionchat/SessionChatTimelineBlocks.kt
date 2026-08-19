package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.command.CommandInvocationCollapser
import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
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
}

object SessionChatTimelineBlocks {
    private val contextToolNames = setOf("read", "glob", "grep")

    fun build(
        role: MessageRole,
        parts: List<MessagePart>,
        collapseConsecutiveToolCalls: Boolean = false,
    ): SessionChatTimelineBlocksResult {
        if (parts.isEmpty()) return SessionChatTimelineBlocksResult.Ok(emptyList())

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
                    val trimmed = part.text.trim()
                    if (trimmed.isNotEmpty() || part.isStreaming) {
                        val partId = part.id ?: return SessionChatTimelineBlocksResult.MissingPartIds
                        blocks +=
                            SessionChatTimelineBlock.Reasoning(
                                blockID = PresentationBlockId.direct(partId),
                                text = trimmed,
                                isStreaming = part.isStreaming,
                                durationMs = thoughtDurationMs(part.timeStartEpochMs, part.timeEndEpochMs),
                            )
                    }
                    index += 1
                }
                is MessagePart.Compaction -> {
                    index += 1
                }
            }
        }
        if (!flushText()) return SessionChatTimelineBlocksResult.MissingPartIds
        return SessionChatTimelineBlocksResult.Ok(blocks)
    }

    /** Convenience for call sites that fall back when identity is incomplete. */
    fun buildOrFallback(
        role: MessageRole,
        parts: List<MessagePart>,
        messageId: String,
        fallbackText: String,
        collapseConsecutiveToolCalls: Boolean = false,
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

        return when (val result = build(role, parts, collapseConsecutiveToolCalls)) {
            is SessionChatTimelineBlocksResult.Ok ->
                result.blocks.ifEmpty { fallbackBlocks() }
            SessionChatTimelineBlocksResult.MissingPartIds -> fallbackBlocks()
        }
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
        val hasTextPart = message.parts.any { it is MessagePart.Text }
        if (!isStreaming && !hasTextPart) return emptyList()
        return listOf(
            SessionChatTimelineBlock.Text(
                blockID = PresentationBlockId.messageFallback(message.id),
                text = message.text,
            ),
        )
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
    ): List<DisplayMessageBody> {
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

    private fun isMergeableToolOnlyAssistant(body: DisplayMessageBody): Boolean {
        if (body.message.role != MessageRole.Assistant || body.blocks.isEmpty()) return false
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

    private fun thoughtDurationMs(
        start: Long?,
        end: Long?,
    ): Long? {
        if (start == null || end == null) return null
        val duration = end - start
        return duration.takeIf { it >= 0 }
    }

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
            is MessagePart.Reasoning -> !part.isStreaming && part.text.isBlank()
        }

    private fun isContextTool(toolName: String): Boolean = toolName.lowercase() in contextToolNames

    /**
     * A visible tool can join a group. Child-session tools (`task` / v2
     * `subagent`) never group so their child-session tap stays available; when
     * collapse is on every other visible tool groups, otherwise only context
     * tools do.
     */
    private fun isGroupableTool(tool: MessagePart.Tool, collapse: Boolean): Boolean {
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
            title = groupTitle(tools.size),
            subtitle = groupSubtitle(tools),
            errorText = errorText,
            members = members,
        )
    }

    /** Count headline for the Tool Call Card (`7 tools`). */
    private fun groupTitle(count: Int): String = "$count tools"

    /**
     * Family one-liner in encounter order (`Edit · Write · 5 shells`).
     * A single member of a family is just the verb; multiples get a count.
     */
    private fun groupSubtitle(tools: List<MessagePart.Tool>): String {
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
            "read" -> contextChrome(tool, displayTitle = "Read", subtitle = pathSubtitle(tool.input))
            "glob" ->
                contextChrome(
                    tool,
                    displayTitle = "Glob",
                    subtitle = patternPathSubtitle(tool.input),
                )
            "grep" ->
                contextChrome(
                    tool,
                    displayTitle = "Grep",
                    subtitle = patternPathSubtitle(tool.input),
                )
            "bash", "shell" -> shellChrome(tool)
            "execute" -> executeChrome(tool)
            "lsp" -> lspChrome(tool)
            "plan_exit" -> planExitChrome(tool)
            "webfetch" -> networkChrome(tool, displayTitle = "Fetch", subtitleKey = "url")
            "websearch" -> networkChrome(tool, displayTitle = "Search", subtitleKey = "query")
            "skill" ->
                Chrome(
                    title = "Skill",
                    subtitle =
                        inputString(tool.input, "name", "skill")
                            ?: runningTitleOverride(tool),
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

    private fun canOpenChangesReview(status: ToolStatus): Boolean =
        status == ToolStatus.Completed || status == ToolStatus.Error

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

    private fun childSessionId(tool: MessagePart.Tool): String? =
        metadataString(tool.metadata, "sessionId", "sessionID")

    private fun contextChrome(
        tool: MessagePart.Tool,
        displayTitle: String,
        subtitle: String?,
    ): Chrome {
        if (subtitle == null) {
            return Chrome(
                title = ToolPartIdentity.calledTitle(tool.toolName),
                subtitle = runningTitleOverride(tool),
            )
        }
        return Chrome(
            title = displayTitle,
            subtitle = runningTitleOverride(tool) ?: subtitle,
        )
    }

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

    private fun pathSubtitle(input: JsonObject?): String? =
        inputString(input, "filePath", "path", "file")

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

    private fun isQuestionTool(tool: MessagePart.Tool): Boolean =
        ToolPartIdentity.isQuestion(tool.toolName, tool.input)

    private fun askedQuestionsTitle(count: Int): String =
        if (count == 1) "Asked 1 question" else "Asked $count questions"

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
