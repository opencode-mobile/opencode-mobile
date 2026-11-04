package dev.opencode.mobile.domain.session

/**
 * Session search by title (#929): a case-insensitive substring match, the same rule the v1 and v2
 * servers apply to their `search` query parameter.
 */
object SessionTitleSearch {
    /** The query as searched: surrounding whitespace dropped; empty means no search. */
    fun normalizedQuery(raw: String): String = raw.trim()

    /** First case-insensitive occurrence of [query] in [title], or null (also for an empty query). */
    fun matchRange(
        title: String,
        query: String,
    ): IntRange? {
        val needle = normalizedQuery(query)
        if (needle.isEmpty()) return null
        val start = title.indexOf(needle, ignoreCase = true)
        return if (start < 0) null else start until start + needle.length
    }

    fun matches(
        title: String,
        query: String,
    ): Boolean = matchRange(title, query) != null

    /**
     * What a search returns from a session list: active root sessions whose title matches. The
     * remotes apply it to server replies too, since a server that ignores `search` lists everything.
     */
    fun filter(
        sessions: List<Session>,
        query: String,
    ): List<Session> = sessions.activeSessions().rootSessions().filter { matches(it.title, query) }
}
