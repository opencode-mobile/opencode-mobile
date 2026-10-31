package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.session.Session

/**
 * The descendant subagent session a permission or question request comes from.
 * Requests from the bound session itself have no attribution.
 */
data class InboxRequestAttribution(
    val sessionId: String,
    /** The asking session's parent, so Open pushes it as a child session. */
    val parentId: String,
    val sessionTitle: String,
    /** The subagent's agent name (`general`, `explore`), null when the session title does not name it. */
    val agentName: String?,
) {
    val letter: String
        get() = SessionChatChildSessionElapsed.letter(agentName ?: sessionTitle)

    /** "general · subagent wants to run: npm test". */
    fun permissionSummary(
        permission: String,
        target: String?,
    ): String {
        val action = permissionAction(permission)
        val wants = if (target != null) "wants to $action: $target" else "wants to $action"
        return "${prefix}subagent $wants"
    }

    /** "explore · subagent asks". */
    val questionSummary: String
        get() = "${prefix}subagent asks"

    private val prefix: String
        get() = agentName?.let { "$it · " }.orEmpty()

    companion object {
        fun resolve(
            requestSessionId: String,
            rootSessionId: String,
            sessions: List<Session>,
        ): InboxRequestAttribution? {
            if (requestSessionId == rootSessionId) return null
            val session = sessions.firstOrNull { it.id == requestSessionId }
            val title = session?.title.orEmpty()
            return InboxRequestAttribution(
                sessionId = requestSessionId,
                parentId = session?.parentId ?: rootSessionId,
                sessionTitle = title.ifEmpty { "Subagent" },
                agentName = session?.agentName ?: agentName(fromTitle = title),
            )
        }

        /** v1 has no session agent field; its task tool titles a subagent session `<description> (@<agent> subagent)`. */
        fun agentName(fromTitle: String): String? {
            val trimmed = fromTitle.trim { it == ' ' || it == '\t' }
            val suffix = " subagent)"
            if (!trimmed.endsWith(suffix)) return null
            val open = trimmed.lastIndexOf("(@")
            if (open < 0) return null
            val start = open + 2
            val end = trimmed.length - suffix.length
            if (start >= end) return null
            val name = trimmed.substring(start, end)
            if (name.any { it.isWhitespace() || it == '(' || it == ')' }) return null
            return name
        }

        private fun permissionAction(permission: String): String =
            when (permission.lowercase()) {
                "bash", "shell" -> "run"
                "edit", "write", "patch", "multiedit" -> "edit"
                "read" -> "read"
                "webfetch" -> "fetch"
                else -> "use ${permission.replace('_', ' ')}"
            }
    }
}

/**
 * Paging between waiting requests of one kind. The selected request is tracked by id so a
 * snapshot that adds or drops other requests keeps the panel on the same one.
 */
object InboxRequestPaging {
    /** Index of the selected request, or 0 when nothing is selected or it is gone. */
    fun index(
        ids: List<String>,
        selectedId: String?,
    ): Int = selectedId?.let { ids.indexOf(it) }?.takeIf { it >= 0 } ?: 0

    /** Id [delta] pages from the selected one, clamped to the ends; null when there are no requests. */
    fun pagedId(
        ids: List<String>,
        selectedId: String?,
        delta: Int,
    ): String? {
        if (ids.isEmpty()) return null
        return ids[(index(ids, selectedId) + delta).coerceIn(0, ids.lastIndex)]
    }
}
