package dev.opencode.mobile.domain.command

/**
 * UTF-16 range covering a leading `[/command]` token in collapsed slash-command
 * chat text, or null when the text is not in that display form.
 */
fun commandTokenHighlightRange(text: String): IntRange? {
    if (!text.startsWith("[/")) return null
    val end = text.indexOf(']')
    if (end <= 1) return null
    return 0..end
}
