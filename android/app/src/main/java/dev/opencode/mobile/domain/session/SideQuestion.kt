package dev.opencode.mobile.domain.session

/**
 * `/btw` side questions, like the TUI and desktop app: ask the session's model about the
 * conversation so far and read the answer without adding anything to the session.
 */
object SideQuestion {
    const val COMMAND = "/btw"

    /**
     * What the TUI and desktop put in front of the question. The session's tools are exposed to
     * the model but no tool loop runs, so the model is told not to call any.
     */
    val INSTRUCTIONS =
        listOf(
            "The user is asking a quick side question about the conversation so far.",
            "Answer directly and concisely in markdown from what you already know.",
            "Do not call any tools and do not take any actions.",
        ).joinToString(" ")

    /** The `generate` prompt: the instruction, a blank line, then the trimmed question. */
    fun prompt(question: String): String = INSTRUCTIONS + "\n\n" + question.trim()

    /**
     * The question after a leading `/btw`, trimmed; empty for a bare `/btw`. Null when the draft is
     * not a `/btw` command (`/btwx`, or `/btw` later in the text).
     */
    fun questionInDraft(text: String): String? {
        val leading = text.trimStart()
        if (!leading.startsWith(COMMAND)) return null
        val rest = leading.substring(COMMAND.length)
        val first = rest.firstOrNull() ?: return ""
        if (!first.isWhitespace()) return null
        return rest.trim()
    }

    /**
     * The draft after a leading `/btw` and the whitespace after it, kept as typed (trailing spaces
     * stay). Null when the draft does not start with the command and whitespace.
     */
    fun draftAfterCommand(text: String): String? {
        if (!entersMode(text)) return null
        return text.trimStart().substring(COMMAND.length).trimStart()
    }

    /** Typing `/btw` and then whitespace switches the composer into side-question mode. */
    fun entersMode(text: String): Boolean {
        val leading = text.trimStart()
        return leading.startsWith(COMMAND) && leading.getOrNull(COMMAND.length)?.isWhitespace() == true
    }
}
