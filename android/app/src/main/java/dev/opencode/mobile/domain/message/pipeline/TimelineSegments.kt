package dev.opencode.mobile.domain.message

/**
 * One run of a message on the default timeline (no chip, step rail or activity card layout):
 * merged text, a tool or tool group, attached files, a settled thought, a compaction marker or a
 * notice. The UI builder turns each into blocks; the display pipeline counts text runs.
 */
sealed interface TimelineSegment {
    /** Adjacent displayable text parts, merged; [blockId] is the block's presentation id. */
    data class Text(
        val partIds: List<String>,
        val text: String,
    ) : TimelineSegment {
        val blockId: String get() = PresentationBlockId.mergedText(partIds)
    }

    data class Tool(
        val tool: MessagePart.Tool,
    ) : TimelineSegment

    /** Two or more groupable tools (see [TimelineSegments.isGroupableTool]). */
    data class ToolGroup(
        val tools: List<MessagePart.Tool>,
    ) : TimelineSegment

    /** Consecutive file parts sent or attached together. */
    data class Files(
        val files: List<MessagePart.File>,
    ) : TimelineSegment

    /** A settled, visible thought. */
    data class Reasoning(
        val part: MessagePart.Reasoning,
        val partId: String,
    ) : TimelineSegment

    /** The v2 compaction marker on an assistant row. */
    data class Compaction(
        val part: MessagePart.Compaction,
    ) : TimelineSegment

    data class Notice(
        val part: MessagePart.Notice,
    ) : TimelineSegment
}

/**
 * Text-run boundaries, visibility and grouping rules of the default timeline. Text runs close at
 * visible tools, files, thoughts, compaction markers (assistant rows) and notices; invisible tools
 * (todowrite, live questions) do not close a run.
 */
object TimelineSegments {
    /** Tools that fold into an explore summary or group. */
    val contextToolNames: Set<String> = setOf("read", "glob", "grep", "websearch")

    fun isContextTool(toolName: String): Boolean = toolName.lowercase() in contextToolNames

    /** A subagent task (`task`, v2 `subagent`): opens a child session. */
    fun isChildSessionTool(toolName: String): Boolean {
        val name = toolName.lowercase()
        return name == "task" || name == "subagent"
    }

    /**
     * A visible tool can join a group. Child-session tools (`task` / v2
     * `subagent`) never group so their child-session tap stays available; when
     * collapse is on every other visible tool groups, otherwise only context
     * tools do.
     */
    fun isGroupableTool(
        tool: MessagePart.Tool,
        collapse: Boolean,
    ): Boolean {
        if (isChildSessionTool(tool.toolName)) return false
        if (collapse) return true
        return isContextTool(tool.toolName)
    }

    /**
     * Parts that never become timeline blocks. Grouping looks through them so
     * hidden parts between two context tools do not split the group.
     */
    fun isTransparentPart(part: MessagePart): Boolean =
        when (part) {
            is MessagePart.Compaction -> true
            is MessagePart.Notice -> false
            is MessagePart.Text -> !part.isDisplayable || part.text.isBlank()
            is MessagePart.Tool -> !ToolPartIdentity.isVisibleOnTimeline(part)
            is MessagePart.File -> false
            is MessagePart.Reasoning -> !ThoughtPartChrome.isVisible(part)
        }

    /**
     * The default timeline's segments for a message, or null when a part that would show lacks
     * its stable server id (callers then fall back to the message text).
     */
    fun segments(
        role: MessageRole,
        parts: List<MessagePart>,
        collapseConsecutiveToolCalls: Boolean = false,
    ): List<TimelineSegment>? {
        val allowToolCards = role == MessageRole.Assistant
        val segments = mutableListOf<TimelineSegment>()
        val pendingText = mutableListOf<Pair<String, String>>()

        fun flushText() {
            if (pendingText.isEmpty()) return
            val merged = pendingText.joinToString("\n") { it.second }
            val ids = pendingText.map { it.first }
            pendingText.clear()
            if (merged.isNotEmpty()) segments += TimelineSegment.Text(partIds = ids, text = merged)
        }

        var index = 0
        while (index < parts.size) {
            when (val part = parts[index]) {
                is MessagePart.Text -> {
                    if (part.isDisplayable) {
                        val trimmed = part.text.trim()
                        if (trimmed.isNotEmpty()) {
                            val partId = part.id ?: return null
                            pendingText += partId to trimmed
                        }
                    }
                    index += 1
                }
                is MessagePart.Tool -> {
                    if (!allowToolCards || !ToolPartIdentity.isVisibleOnTimeline(part)) {
                        index += 1
                        continue
                    }
                    flushText()
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
                            if (groupTools.any { it.id == null }) return null
                            segments += TimelineSegment.ToolGroup(groupTools)
                            index = lookAhead
                        } else {
                            if (part.id == null) return null
                            segments += TimelineSegment.Tool(part)
                            index += 1
                        }
                    } else {
                        if (part.id == null) return null
                        segments += TimelineSegment.Tool(part)
                        index += 1
                    }
                }
                is MessagePart.File -> {
                    flushText()
                    var lookAhead = index
                    val run = mutableListOf<MessagePart.File>()
                    while (lookAhead < parts.size) {
                        val file = parts[lookAhead] as? MessagePart.File ?: break
                        run += file
                        lookAhead += 1
                    }
                    segments += TimelineSegment.Files(run)
                    index = lookAhead
                }
                is MessagePart.Reasoning -> {
                    flushText()
                    // Live thought is footer chrome (Thinking); only settled thoughts become rows.
                    if (ThoughtPartChrome.isVisible(part) && !part.isStreaming) {
                        val partId = part.id ?: return null
                        segments += TimelineSegment.Reasoning(part, partId)
                    }
                    index += 1
                }
                is MessagePart.Compaction -> {
                    // v1 marks compaction on the user message that asked for it;
                    // only the v2 compaction item (an assistant row) gets a marker.
                    if (allowToolCards) {
                        flushText()
                        if (part.id == null) return null
                        segments += TimelineSegment.Compaction(part)
                    }
                    index += 1
                }
                is MessagePart.Notice -> {
                    flushText()
                    segments += TimelineSegment.Notice(part)
                    index += 1
                }
            }
        }
        flushText()
        return segments
    }

    /**
     * The message shows as exactly one text block on the default timeline: one text run among its
     * segments, or, when it has no segments or lacks part ids, non-blank fallback text.
     */
    fun hasSoleTextBlock(
        role: MessageRole,
        parts: List<MessagePart>,
        fallbackText: String,
    ): Boolean {
        val segments = segments(role, parts)
        if (segments.isNullOrEmpty()) return fallbackText.isNotBlank()
        return segments.count { it is TimelineSegment.Text } == 1
    }
}
