package dev.opencode.mobile.domain.message

object MessageSnapshotAuthority {
    fun isAheadOrEqual(
        remote: List<Message>,
        local: List<Message>,
    ): Boolean {
        val remoteScore = score(remote)
        val localScore = score(local)
        if (remoteScore.textCharacters < localScore.textCharacters) return false
        if (remoteScore.textCharacters > localScore.textCharacters) return true
        if (remoteScore.partCount > localScore.partCount) return true
        if (remoteScore.messageCount > localScore.messageCount) return true
        if (remoteScore.streamingCount < localScore.streamingCount) return true
        if (remoteScore.completedTools > localScore.completedTools) return true
        return true
    }

    private data class Score(
        val textCharacters: Int,
        val partCount: Int,
        val messageCount: Int,
        val streamingCount: Int,
        val completedTools: Int,
    )

    private fun score(messages: List<Message>): Score {
        var textCharacters = 0
        var partCount = 0
        var streamingCount = 0
        var completedTools = 0
        for (message in messages) {
            partCount += message.parts.size
            if (message.parts.isEmpty()) {
                textCharacters += message.text.length
                continue
            }
            for (part in message.parts) {
                when (part) {
                    is MessagePart.Text -> {
                        textCharacters += part.text.length
                        if (part.isStreaming) streamingCount += 1
                    }
                    is MessagePart.Tool -> {
                        if (part.status == ToolStatus.Completed) completedTools += 1
                    }
                    else -> Unit
                }
            }
        }
        return Score(
            textCharacters = textCharacters,
            partCount = partCount,
            messageCount = messages.size,
            streamingCount = streamingCount,
            completedTools = completedTools,
        )
    }
}
