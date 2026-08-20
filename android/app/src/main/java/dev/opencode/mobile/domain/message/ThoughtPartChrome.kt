package dev.opencode.mobile.domain.message

/**
 * Compact thought-row diagnostics. Labels match Session Chat chrome
 * (`thinking` / `thought`) plus `hidden` when TUI would drop the part
 * (blank or `[REDACTED]`-only reasoning).
 */
object ThoughtPartChrome {
    fun displayText(part: MessagePart.Reasoning): String = part.text.replace("[REDACTED]", "").trim()

    fun isVisible(part: MessagePart.Reasoning): Boolean = displayText(part).isNotEmpty()

    fun paintLabel(part: MessagePart.Reasoning): String =
        when {
            !isVisible(part) -> "hidden"
            part.isStreaming -> "thinking"
            else -> "thought"
        }

    fun summarize(
        messages: List<Message>,
        paintedCount: Int? = null,
    ): String {
        val afterLastUser = assistantsAfterLastUser(messages)
        var thinking = 0
        var thought = 0
        var hidden = 0
        var omitted = 0
        var lastPaint = "none"
        var lastChars = 0
        var lastStreaming = false
        var lastHasEnd = false
        for (message in afterLastUser) {
            var visible = false
            for (part in message.parts) {
                when (part) {
                    is MessagePart.Reasoning -> {
                        val paint = paintLabel(part)
                        lastPaint = paint
                        lastChars = part.text.length
                        lastStreaming = part.isStreaming
                        lastHasEnd = part.timeEndEpochMs != null
                        when (paint) {
                            "thinking" -> thinking += 1
                            "thought" -> thought += 1
                            else -> hidden += 1
                        }
                        if (paint != "hidden") visible = true
                    }
                    is MessagePart.Text ->
                        if (part.isDisplayable && (part.isStreaming || part.text.isNotBlank())) {
                            visible = true
                        }
                    is MessagePart.Tool ->
                        if (ToolPartIdentity.isVisibleOnTimeline(part)) {
                            visible = true
                        }
                    is MessagePart.File -> visible = true
                    is MessagePart.Compaction -> Unit
                }
            }
            if (!visible && !isCompaction(message)) {
                omitted += 1
            }
        }
        val painted = paintedCount?.toString() ?: "-"
        return "thoughtChrome store=${messages.size} painted=$painted " +
            "omitted=$omitted thinking=$thinking thought=$thought hidden=$hidden " +
            "last=$lastPaint chars=$lastChars streaming=${if (lastStreaming) 1 else 0} " +
            "end=${if (lastHasEnd) 1 else 0}"
    }

    private fun assistantsAfterLastUser(messages: List<Message>): List<Message> {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.User }
        val after = if (lastUserIndex < 0) messages else messages.drop(lastUserIndex + 1)
        return after.filter { it.role == MessageRole.Assistant }
    }

    private fun isCompaction(message: Message): Boolean =
        message.mode.equals("compaction", ignoreCase = true) ||
            message.parts.any { it is MessagePart.Compaction }
}
