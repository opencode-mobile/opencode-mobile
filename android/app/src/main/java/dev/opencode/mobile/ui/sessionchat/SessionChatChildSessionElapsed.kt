package dev.opencode.mobile.ui.sessionchat

object SessionChatChildSessionElapsed {
    fun format(
        startEpochMs: Long?,
        nowEpochMs: Long,
    ): String? {
        if (startEpochMs == null) return null
        val elapsedMs = (nowEpochMs - startEpochMs).coerceAtLeast(0)
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    fun letter(from: String): String {
        val first = from.firstOrNull() ?: return "?"
        return first.uppercaseChar().toString()
    }
}
