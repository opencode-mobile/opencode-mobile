package dev.opencode.mobile.ui.sessionchat

/**
 * When the pending prompts strip mounts. A v2 prompt to an idle session passes
 * through the server inbox for a few milliseconds (`inbox.enqueued`, then
 * `inbox.delivered`), so a server-waiting prompt only mounts the strip once it
 * has waited past a short grace. Otherwise the strip shows for a frame and
 * jolts the timeline inset. The app's own busy sends (settling rows) mount at
 * once. A mounted strip follows the list until it empties.
 */
class PendingPromptsStripGate {
    data class Resolution(
        val prompts: List<PendingPrompt>,
        val wakeAfterMs: Long? = null,
    )

    var waitingSinceMs: Long? = null
        private set
    var isMounted = false
        private set

    fun resolve(
        prompts: List<PendingPrompt>,
        nowMs: Long,
    ): Resolution {
        if (prompts.isEmpty()) {
            reset()
            return Resolution(prompts = emptyList())
        }
        if (isMounted || prompts.any { it.isSettling }) {
            isMounted = true
            return Resolution(prompts = prompts)
        }
        val since = waitingSinceMs ?: nowMs
        waitingSinceMs = since
        val waited = nowMs - since
        if (waited < GRACE_MS) {
            return Resolution(prompts = emptyList(), wakeAfterMs = GRACE_MS - waited)
        }
        isMounted = true
        return Resolution(prompts = prompts)
    }

    fun reset() {
        waitingSinceMs = null
        isMounted = false
    }

    companion object {
        const val GRACE_MS = 300L
    }
}
