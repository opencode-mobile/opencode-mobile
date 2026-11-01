package dev.opencode.mobile.domain.shell

import dev.opencode.mobile.data.remote.dto.ShellOutputDto

/**
 * Reads a v2 shell's output tail first, then follows it by byte cursor.
 *
 * The first read asks for an empty page past the end to learn the size, then starts
 * [TAIL_BYTES] before the end, dropping the partial first line when that skips older output.
 * Later reads continue from the last cursor, page by page while the server has more. The text
 * keeps at most the last [AgentShellOutput.MAX_CHARS] characters.
 *
 * [read] answers one page from a cursor, or null once the shell is removed (404).
 */
internal class V2ShellOutputReader(
    private val read: suspend (cursor: Long) -> ShellOutputDto?,
) {
    private var cursor: Long? = null
    private var text = StringBuilder()
    private var truncated = false
    private var dropsPartialLine = false

    var output: AgentShellOutput = AgentShellOutput()
        private set

    /** The shell is gone; later reads stop and [output] keeps the last text. */
    var removed = false
        private set

    /** Reads what is new. Returns true when [output] changed. */
    suspend fun poll(): Boolean {
        if (removed) return false
        var from = cursor ?: startCursor() ?: return false
        var changed = false
        var pages = 0
        while (pages++ < MAX_PAGES_PER_POLL) {
            val answer = read(from)
            if (answer == null) {
                removed = true
                break
            }
            var pageChanged = false
            if (answer.truncated && !truncated) {
                truncated = true
                pageChanged = true
            }
            if (append(answer.output)) pageChanged = true
            if (pageChanged) {
                // Kept page by page, so a later page that fails does not lose this one.
                output = AgentShellOutput(text.toString(), isTruncated = truncated)
                changed = true
            }
            if (answer.cursor <= from) break
            from = answer.cursor
            cursor = from
            if (from >= answer.size) break
        }
        return changed
    }

    /** Where the first read starts: [TAIL_BYTES] before the end. Null when the shell is gone. */
    private suspend fun startCursor(): Long? {
        val probe = read(PROBE_CURSOR)
        if (probe == null) {
            removed = true
            return null
        }
        val start = (probe.size - TAIL_BYTES).coerceAtLeast(0)
        if (start > 0) {
            truncated = true
            dropsPartialLine = true
        }
        cursor = start
        return start
    }

    private fun append(chunk: String): Boolean {
        if (chunk.isEmpty()) return false
        var added = chunk
        if (dropsPartialLine) {
            val newline = added.indexOf('\n')
            if (newline < 0) return false
            added = added.substring(newline + 1)
            dropsPartialLine = false
            if (added.isEmpty()) return false
        }
        text.append(added)
        if (text.length > AgentShellOutput.MAX_CHARS) {
            text = StringBuilder(text.substring(text.length - AgentShellOutput.MAX_CHARS))
            truncated = true
        }
        return true
    }

    companion object {
        /** Past any real output size: the server answers an empty page with the current size. */
        const val PROBE_CURSOR = 999_999_999_999L

        /** How far before the end the first read starts. */
        const val TAIL_BYTES = 32_768L

        /** Guards one poll against a server that never catches up. */
        private const val MAX_PAGES_PER_POLL = 16
    }
}
