package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Session Chat presentation units inside one message timeline item.
 * Built from structured parts with hide rules and lightweight tool chrome.
 */
sealed class SessionChatTimelineBlock {
    data class Text(
        val text: String,
    ) : SessionChatTimelineBlock()

    data class ToolCard(
        val toolName: String,
        val status: ToolStatus,
        val title: String,
        val subtitle: String? = null,
        val errorText: String? = null,
        /** Non-null when the card should open a Bottom Sheet with this body. */
        val detailText: String? = null,
    ) : SessionChatTimelineBlock()

    data class ToolGroup(
        val count: Int,
        val status: ToolStatus,
        val title: String,
        val subtitle: String? = null,
        val errorText: String? = null,
    ) : SessionChatTimelineBlock()
}

object SessionChatTimelineBlocks {
    private val contextToolNames = setOf("read", "glob", "grep")

    fun build(
        role: MessageRole,
        parts: List<MessagePart>,
    ): List<SessionChatTimelineBlock> {
        if (parts.isEmpty()) return emptyList()

        val allowToolCards = role == MessageRole.Assistant
        val blocks = mutableListOf<SessionChatTimelineBlock>()
        val pendingText = mutableListOf<String>()

        fun flushText() {
            if (pendingText.isEmpty()) return
            val merged = pendingText.joinToString("\n")
            pendingText.clear()
            if (merged.isNotEmpty()) {
                blocks += SessionChatTimelineBlock.Text(merged)
            }
        }

        var index = 0
        while (index < parts.size) {
            when (val part = parts[index]) {
                is MessagePart.Text -> {
                    val trimmed = part.text.trim()
                    if (trimmed.isNotEmpty()) {
                        pendingText += trimmed
                    }
                    index += 1
                }
                is MessagePart.Tool -> {
                    if (!allowToolCards || !isVisibleTool(part)) {
                        index += 1
                        continue
                    }
                    flushText()
                    if (isContextTool(part.toolName)) {
                        val groupTools = mutableListOf<MessagePart.Tool>()
                        var lookAhead = index
                        while (lookAhead < parts.size) {
                            val candidate = parts[lookAhead] as? MessagePart.Tool ?: break
                            if (!isVisibleTool(candidate) || !isContextTool(candidate.toolName)) break
                            groupTools += candidate
                            lookAhead += 1
                        }
                        if (groupTools.size >= 2) {
                            blocks += toToolGroup(groupTools)
                            index = lookAhead
                        } else {
                            blocks += toToolCard(part)
                            index += 1
                        }
                    } else {
                        blocks += toToolCard(part)
                        index += 1
                    }
                }
            }
        }
        flushText()
        return blocks
    }

    private fun isVisibleTool(tool: MessagePart.Tool): Boolean {
        val name = tool.toolName.lowercase()
        if (name == "todowrite") return false
        if (name == "question" &&
            (tool.status == ToolStatus.Pending || tool.status == ToolStatus.Running)
        ) {
            return false
        }
        return true
    }

    private fun isContextTool(toolName: String): Boolean = toolName.lowercase() in contextToolNames

    private fun toToolGroup(tools: List<MessagePart.Tool>): SessionChatTimelineBlock.ToolGroup {
        val status = worstStatus(tools.map { it.status })
        val errorText =
            tools
                .firstOrNull { it.status == ToolStatus.Error }
                ?.error
                ?.takeIf { it.isNotEmpty() }
        return SessionChatTimelineBlock.ToolGroup(
            count = tools.size,
            status = status,
            title = contextGroupTitle(tools),
            subtitle = null,
            errorText = errorText,
        )
    }

    private fun contextGroupTitle(tools: List<MessagePart.Tool>): String {
        val counts =
            linkedMapOf(
                "read" to 0,
                "grep" to 0,
                "glob" to 0,
            )
        for (tool in tools) {
            val key = tool.toolName.lowercase()
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.entries
            .filter { it.value > 0 }
            .joinToString(" · ") { (name, count) ->
                val label =
                    when (name) {
                        "read" -> if (count == 1) "read" else "reads"
                        "grep" -> if (count == 1) "grep" else "greps"
                        "glob" -> if (count == 1) "glob" else "globs"
                        else -> name
                    }
                "$count $label"
            }
    }

    private fun worstStatus(statuses: List<ToolStatus>): ToolStatus {
        if (statuses.any { it == ToolStatus.Error }) return ToolStatus.Error
        if (statuses.any { it == ToolStatus.Running || it == ToolStatus.Pending }) {
            return ToolStatus.Running
        }
        return ToolStatus.Completed
    }

    private fun toToolCard(tool: MessagePart.Tool): SessionChatTimelineBlock.ToolCard {
        val name = tool.toolName.lowercase()
        val chrome = specializedChrome(name, tool) ?: genericChrome(tool)
        val errorText =
            when (tool.status) {
                ToolStatus.Error -> tool.error?.takeIf { it.isNotEmpty() }
                else -> null
            }
        return SessionChatTimelineBlock.ToolCard(
            toolName = tool.toolName,
            status = tool.status,
            title = chrome.title,
            subtitle = chrome.subtitle,
            errorText = errorText,
            detailText = chrome.detailText,
        )
    }

    private data class Chrome(
        val title: String,
        val subtitle: String?,
        val detailText: String? = null,
    )

    private fun genericChrome(tool: MessagePart.Tool): Chrome {
        val subtitle =
            when (tool.status) {
                ToolStatus.Running -> tool.title?.takeIf { it.isNotEmpty() }
                else -> null
            }
        return Chrome(title = "Called ${tool.toolName}", subtitle = subtitle)
    }

    private fun specializedChrome(
        name: String,
        tool: MessagePart.Tool,
    ): Chrome? =
        when (name) {
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
            "bash" -> shellChrome(tool)
            "webfetch" -> networkChrome(tool, displayTitle = "Fetch", subtitleKey = "url")
            "websearch" -> networkChrome(tool, displayTitle = "Search", subtitleKey = "query")
            "question" ->
                Chrome(
                    title = "Question",
                    subtitle =
                        inputString(tool.input, "prompt", "text", "question")
                            ?: runningTitleOverride(tool),
                )
            "skill" ->
                Chrome(
                    title = "Skill",
                    subtitle =
                        inputString(tool.input, "name", "skill")
                            ?: runningTitleOverride(tool),
                )
            else -> null
        }

    private fun contextChrome(
        tool: MessagePart.Tool,
        displayTitle: String,
        subtitle: String?,
    ): Chrome {
        if (subtitle == null) {
            return Chrome(
                title = "Called ${tool.toolName}",
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
        val detail =
            tool.output?.takeIf { it.isNotEmpty() }
                ?: tool.error?.takeIf { it.isNotEmpty() }
        return Chrome(
            title = "Shell",
            subtitle = command,
            detailText = detail,
        )
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
}
