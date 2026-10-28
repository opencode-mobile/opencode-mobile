package dev.opencode.mobile.highlight.core

/** Allocation-free character helpers shared by lexers. */
internal object LexerScan {
    /** True when `text[at, end)` starts with [prefix]. */
    fun startsWith(
        text: CharArray,
        at: Int,
        end: Int,
        prefix: String,
    ): Boolean {
        if (end - at < prefix.length) return false
        for (index in prefix.indices) {
            if (text[at + index] != prefix[index]) return false
        }
        return true
    }

    /** True when `text[from, to)` is exactly [word]. */
    fun equals(
        text: CharArray,
        from: Int,
        to: Int,
        word: String,
    ): Boolean = to - from == word.length && startsWith(text, from, to, word)

    /** The offset just past the first `*` `/` at or after [from], or -1 when the line has none. */
    fun blockCommentEnd(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index + 1 < end) {
            if (text[index] == '*' && text[index + 1] == '/') return index + 2
            index += 1
        }
        return -1
    }

    /** The first offset at or after [from] that is not a space or tab. */
    fun skipBlanks(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end && (text[index] == ' ' || text[index] == '\t')) index += 1
        return index
    }

    fun isDigit(char: Char): Boolean = char in '0'..'9'

    fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

    fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

    fun isWordStart(char: Char): Boolean = isAsciiLetter(char) || char == '_' || char == '$'

    fun isWordPart(char: Char): Boolean = isWordStart(char) || isDigit(char)
}
