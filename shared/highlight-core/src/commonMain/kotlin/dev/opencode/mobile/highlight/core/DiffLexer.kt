package dev.opencode.mobile.highlight.core

import dev.opencode.mobile.highlight.core.LexerScan.isDigit
import dev.opencode.mobile.highlight.core.LexerScan.startsWith

/**
 * Unified diffs (git, `diff -u`, patches) and the looser diffs models write.
 *
 * Roles, each for the whole line: header lines (`diff --git`, `index`, `---`, `+++`, mode,
 * rename, and similar lines) are Warning; an added line, `+` included, is Inserted; a removed
 * line is Deleted; `\ No newline at end of file` is Comment; context lines stay plain. A hunk
 * header's `@@ -a,b +c,d @@` is Keyword and the function name git appends after it is Function.
 *
 * States: [OUTSIDE] a hunk; [LOOSE] in a hunk whose header had no usable line counts; or
 * [COUNTED] with the old and new lines still expected (15 bits each). Counting is what tells a
 * removed line `--- x` from a file header, so a counted hunk ends exactly where its header says.
 */
internal object DiffLexer : HighlightLexer() {
    private const val OUTSIDE = 0
    private const val LOOSE = 1
    private const val COUNTED = 1 shl 30
    private const val MAX_COUNT = 0x7FFF

    override fun lexLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        if (state and COUNTED != 0) return countedLine(text, start, end, state, out)
        if (state == LOOSE) return looseLine(text, start, end, out)
        return outsideLine(text, start, end, out)
    }

    private fun countedLine(
        text: CharArray,
        start: Int,
        end: Int,
        state: Int,
        out: SpanSink,
    ): Int {
        var old = (state ushr 15) and MAX_COUNT
        var new = state and MAX_COUNT
        when (if (start < end) text[start] else ' ') {
            ' ' -> {
                old -= 1
                new -= 1
            }
            '-' -> {
                out.span(start, end, RoleCode.DELETED)
                old -= 1
            }
            '+' -> {
                out.span(start, end, RoleCode.INSERTED)
                new -= 1
            }
            '\\' -> {
                out.span(start, end, RoleCode.COMMENT)
                return state
            }
            else -> return outsideLine(text, start, end, out)
        }
        if (old < 0) old = 0
        if (new < 0) new = 0
        return if (old == 0 && new == 0) OUTSIDE else counted(old, new)
    }

    private fun looseLine(
        text: CharArray,
        start: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        if (start == end) return LOOSE
        when (text[start]) {
            ' ' -> return LOOSE
            '+' -> {
                if (isFileHeader(text, start, end, '+')) return outsideLine(text, start, end, out)
                out.span(start, end, RoleCode.INSERTED)
                return LOOSE
            }
            '-' -> {
                if (isFileHeader(text, start, end, '-')) return outsideLine(text, start, end, out)
                out.span(start, end, RoleCode.DELETED)
                return LOOSE
            }
            '\\' -> {
                out.span(start, end, RoleCode.COMMENT)
                return LOOSE
            }
        }
        if (startsWith(text, start, end, "@@") || isHeaderWord(text, start, end)) return outsideLine(text, start, end, out)
        return LOOSE
    }

    private fun outsideLine(
        text: CharArray,
        start: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        if (start == end) return OUTSIDE
        val role =
            when (text[start]) {
                '@' -> if (startsWith(text, start, end, "@@")) return hunkHeader(text, start, end, out) else 0
                '+' -> if (isFileHeader(text, start, end, '+')) RoleCode.WARNING else RoleCode.INSERTED
                '-' -> if (isFileHeader(text, start, end, '-')) RoleCode.WARNING else RoleCode.DELETED
                '\\' -> RoleCode.COMMENT
                '>' -> if (spaceOrEndAfter(text, start, end)) RoleCode.INSERTED else 0
                '<' -> if (spaceOrEndAfter(text, start, end)) RoleCode.DELETED else 0
                '!' -> if (spaceOrEndAfter(text, start, end)) RoleCode.WARNING else 0
                '*' -> if (startsWith(text, start, end, "*** ")) RoleCode.WARNING else 0
                '=' -> if (startsWith(text, start, end, "====")) RoleCode.WARNING else 0
                else -> if (isHeaderWord(text, start, end)) RoleCode.WARNING else 0
            }
        if (role != 0) out.span(start, end, role)
        return OUTSIDE
    }

    /** `@@ -a[,b] +c[,d] @@ heading`; returns the state for the hunk's lines. */
    private fun hunkHeader(
        text: CharArray,
        start: Int,
        end: Int,
        out: SpanSink,
    ): Int {
        // A combined diff (`@@@ -a,b -c,d +e,f @@@`) has more columns than two counts can follow.
        if (start + 2 < end && text[start + 2] == '@') {
            headerSpans(text, start, end, closingAt(text, start + 3, end, "@@@"), out)
            return LOOSE
        }
        var index = LexerScan.skipBlanks(text, start + 2, end)
        var old = -1
        var new = -1
        if (index < end && text[index] == '-') {
            index = skipNumber(text, index + 1, end)
            old = 1
            if (index < end && text[index] == ',') {
                old = countAt(text, index + 1, end)
                index = skipNumber(text, index + 1, end)
            }
            index = LexerScan.skipBlanks(text, index, end)
            if (index < end && text[index] == '+') {
                index = skipNumber(text, index + 1, end)
                new = 1
                if (index < end && text[index] == ',') {
                    new = countAt(text, index + 1, end)
                    index = skipNumber(text, index + 1, end)
                }
                index = LexerScan.skipBlanks(text, index, end)
            }
        }
        val counted = old >= 0 && new >= 0 && startsWith(text, index, end, "@@")
        headerSpans(text, start, end, if (counted) index + 2 else closingAt(text, start + 2, end, "@@"), out)
        return when {
            !counted || old > MAX_COUNT || new > MAX_COUNT -> LOOSE
            old == 0 && new == 0 -> OUTSIDE
            else -> counted(old, new)
        }
    }

    /** Keyword through the closing `@@` (the whole line when there is none), then Function for the heading. */
    private fun headerSpans(
        text: CharArray,
        start: Int,
        end: Int,
        closeEnd: Int,
        out: SpanSink,
    ) {
        val keywordEnd = if (closeEnd < 0) end else closeEnd
        out.span(start, keywordEnd, RoleCode.KEYWORD)
        val heading = LexerScan.skipBlanks(text, keywordEnd, end)
        if (heading < end) out.span(heading, end, RoleCode.FUNCTION)
    }

    /** The offset after the first [marker] at or after [from], or -1. */
    private fun closingAt(
        text: CharArray,
        from: Int,
        end: Int,
        marker: String,
    ): Int {
        var index = from
        while (index < end) {
            if (startsWith(text, index, end, marker)) return index + marker.length
            index += 1
        }
        return -1
    }

    private fun skipNumber(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        while (index < end && isDigit(text[index])) index += 1
        return index
    }

    /** The decimal count at [from], capped just above [MAX_COUNT]; -1 when there are no digits. */
    private fun countAt(
        text: CharArray,
        from: Int,
        end: Int,
    ): Int {
        var index = from
        var value = 0
        while (index < end && isDigit(text[index])) {
            if (value <= MAX_COUNT) value = value * 10 + (text[index] - '0')
            index += 1
        }
        return if (index == from) -1 else minOf(value, MAX_COUNT + 1)
    }

    private fun counted(
        old: Int,
        new: Int,
    ): Int = COUNTED or (old shl 15) or new

    /** `---` / `+++` alone or followed by a space or tab: a file header outside a counted hunk. */
    private fun isFileHeader(
        text: CharArray,
        start: Int,
        end: Int,
        marker: Char,
    ): Boolean {
        if (end - start < 3 || text[start + 1] != marker || text[start + 2] != marker) return false
        return start + 3 == end || text[start + 3] == ' ' || text[start + 3] == '\t'
    }

    private fun spaceOrEndAfter(
        text: CharArray,
        start: Int,
        end: Int,
    ): Boolean = start + 1 == end || text[start + 1] == ' '

    private fun isHeaderWord(
        text: CharArray,
        start: Int,
        end: Int,
    ): Boolean =
        when (text[start]) {
            'd' -> startsWith(text, start, end, "diff ") || startsWith(text, start, end, "deleted file mode") || startsWith(text, start, end, "dissimilarity index")
            'i' -> startsWith(text, start, end, "index ")
            'I' -> startsWith(text, start, end, "Index: ")
            'n' -> startsWith(text, start, end, "new file mode") || startsWith(text, start, end, "new mode")
            'o' -> startsWith(text, start, end, "old mode")
            's' -> startsWith(text, start, end, "similarity index")
            'r' -> startsWith(text, start, end, "rename from") || startsWith(text, start, end, "rename to")
            'c' -> startsWith(text, start, end, "copy from") || startsWith(text, start, end, "copy to")
            'B' -> startsWith(text, start, end, "Binary files")
            'G' -> startsWith(text, start, end, "GIT binary patch")
            'O' -> startsWith(text, start, end, "Only in ")
            else -> false
        }
}
