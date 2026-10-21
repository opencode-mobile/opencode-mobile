package dev.opencode.mobile.ui.sessionchat

/**
 * Activity card timeline rules (#779) that sit outside the block walk: which
 * steps are running or done, and the footer and settled summary strings.
 * Segment building, stable identity and liveness are shared with
 * [SessionChatStepRail].
 */
object SessionChatActivityCard {
    /** Pending or running steps, in order. */
    fun running(steps: List<SessionChatStep>): List<SessionChatStep> = steps.filter { it.status.isInFlight }

    /** Settled steps (completed, failed, interrupted), in order. */
    fun done(steps: List<SessionChatStep>): List<SessionChatStep> = steps.filterNot { it.status.isInFlight }

    /** Live footer: `4 done · 3 reads · 1 search`, or `nothing finished yet`. */
    fun summary(done: List<SessionChatStep>): String {
        if (done.isEmpty()) return "nothing finished yet"
        return listOf("${done.size} done").plus(familyCounts(done)).joinToString(SEPARATOR)
    }

    /** Settled one-liner: `9 steps · 3 reads · 1 search`. */
    fun settledSummary(steps: List<SessionChatStep>): String =
        listOf(plural(steps.size, "step")).plus(familyCounts(steps)).joinToString(SEPARATOR)

    /** The family list without the leading count: `3 reads · 1 search`. */
    fun familySummary(steps: List<SessionChatStep>): String = familyCounts(steps).joinToString(SEPARATOR)

    fun worstStatus(steps: List<SessionChatStep>): SessionChatStepStatus = SessionChatStepRail.worstStatus(steps)

    /** Families in first-seen order, each as `N word(s)`. */
    private fun familyCounts(steps: List<SessionChatStep>): List<String> {
        val counts = LinkedHashMap<SessionChatStepFamily, Int>()
        steps.forEach { step -> step.family?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts.map { (family, count) -> plural(count, family.word) }
    }

    private val SessionChatStepFamily.word: String
        get() =
            when (this) {
                SessionChatStepFamily.Read -> "read"
                SessionChatStepFamily.Search -> "search"
                SessionChatStepFamily.Fetch -> "fetch"
                SessionChatStepFamily.Edit -> "edit"
                SessionChatStepFamily.Command -> "command"
                SessionChatStepFamily.Skill -> "skill"
                SessionChatStepFamily.Thought -> "thought"
                SessionChatStepFamily.Agent -> "agent"
            }

    private fun plural(
        count: Int,
        word: String,
    ): String =
        when {
            count == 1 -> "1 $word"
            word.endsWith("ch") -> "$count ${word}es"
            else -> "$count ${word}s"
        }

    private const val SEPARATOR = " · "
}
