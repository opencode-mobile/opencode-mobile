package dev.opencode.mobile.domain.command

/**
 * Shell mode in the composer, like `!` in the TUI and web app: a draft whose first character is
 * `!` runs the rest as a shell command in the session instead of sending a prompt.
 *
 * The `!` must be the very first character. A draft with leading whitespace (` !ls`) or a `!`
 * later in the text stays a prompt.
 */
object ComposerShellCommand {
    const val PREFIX = '!'

    fun isShellMode(text: String): Boolean = text.firstOrNull() == PREFIX

    /**
     * The command to run, trimmed, with inner newlines kept so a multi-line draft runs as one
     * script. Null when [text] is not in shell mode or has nothing after the `!`.
     */
    fun command(text: String): String? {
        if (!isShellMode(text)) return null
        return text.substring(1).trim().takeIf { it.isNotEmpty() }
    }

    /** [text] with the shell prefix removed, used to leave shell mode and keep the rest of the draft. */
    fun exitShellMode(text: String): String = if (isShellMode(text)) text.substring(1) else text
}
