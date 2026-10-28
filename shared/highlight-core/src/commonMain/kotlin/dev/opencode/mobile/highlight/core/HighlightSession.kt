package dev.opencode.mobile.highlight.core

/**
 * One document being highlighted: a chat fence that grows as text streams in, or a File Viewer
 * file colored a screen at a time. It holds the text, the start of every line, and the end state
 * of every line lexed so far, never spans; callers keep the spans they need.
 *
 * Lines end at `\n`; a `\r` before it belongs to the terminator, so CRLF text lexes like LF
 * text. Offsets are UTF-16 code units, as in Kotlin and `NSString`.
 *
 * Every call takes a [HighlightCancellation] and a [HighlightDeadline] and returns a
 * [HighlightCallResult]: [HighlightStatus.Complete], [HighlightStatus.Partial] (deadline passed:
 * highlighted prefix, plain rest), [HighlightStatus.Cancelled], or [HighlightStatus.Failed] (the
 * lexer threw: plain for that request). Coloring line N needs the state at its start, which comes
 * from a state-only pass that emits no spans; lines it has not reached are reported plain, never
 * guessed.
 *
 * A session is single-threaded: make every call from one thread at a time. Only the cancellation
 * flag may be touched from another thread. An unsupported language gives plain results.
 */
class HighlightSession internal constructor(
    private val lexer: HighlightLexer?,
    /** The canonical language id when supported, for example "json" for "jsonc"; otherwise the input, normalized. */
    val language: String,
    val limits: HighlightLimits,
) {
    constructor(language: String, limits: HighlightLimits = HighlightLimits.standard) : this(
        HighlightLanguages.lexer(language),
        HighlightLanguages.canonical(language) ?: HighlightLanguages.normalize(language),
        limits,
    )

    val isSupported: Boolean
        get() = lexer != null

    private var chars = CharArray(0)

    /** Text length in UTF-16 code units. */
    var length: Int = 0
        private set

    private var lineStarts = IntArray(INITIAL_LINES)

    /** Lines in the text; an empty text, or one ending in `\n`, ends with an empty line. */
    var lineCount: Int = 1
        private set

    private var endStates = IntArray(INITIAL_LINES)
    private var stateCount = 0

    // After an edit, the old end states of the lines from the edit's last line on, kept so
    // re-lexing can stop at the first line whose end state matches its old one.
    private var staleStart = 0
    private var staleEnd = 0

    /** Lines [0, knownStateLines) have known end states; coloring a line below needs a state pass first. */
    val knownStateLines: Int
        get() = stateCount

    fun text(): String = chars.concatToString(0, length)

    fun lineStart(line: Int): Int = lineStarts[checkLine(line)]

    /** Where [line]'s text ends, before its `\n` or `\r\n`. */
    fun lineEnd(line: Int): Int = contentEnd(checkLine(line))

    /** The line holding [offset]; an offset at a line start belongs to that line. */
    fun lineAt(offset: Int): Int {
        require(offset in 0..length) { "Offset $offset outside 0..$length" }
        var low = 0
        var high = lineCount - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    /**
     * Replaces the whole text and highlights lines [0, endLine): pass the first screen's line
     * count for a file, or [Int.MAX_VALUE] for a whole chat fence. Indexing lines is a single
     * pass (about a millisecond per megabyte) and is not interrupted.
     */
    fun open(
        text: String,
        endLine: Int,
        cancel: HighlightCancellation,
        deadline: HighlightDeadline,
    ): HighlightCallResult {
        chars = text.toCharArray()
        length = text.length
        lineCount = 1
        lineStarts[0] = 0
        for (index in 0 until length) {
            if (chars[index] == '\n') addLineStart(index + 1)
        }
        stateCount = 0
        staleStart = 0
        staleEnd = 0
        return lex(0, endLine.coerceIn(0, lineCount), cancel, deadline, collect = true, stopAtConvergence = false)
    }

    /**
     * Appends streamed text. Re-lexes from the line the old text ended in, and the result covers
     * that line through the end.
     */
    fun append(
        text: String,
        cancel: HighlightCancellation,
        deadline: HighlightDeadline,
    ): HighlightCallResult = replace(length, length, text, cancel, deadline)

    /**
     * Replaces `[start, end)` with [text]. Re-lexes from the first changed line and stops at the
     * first line, at or after the edit's last line, whose end state matches its old one; the result
     * covers exactly the lines whose spans may have changed. Lines after it keep their spans,
     * shifted by `text.length - (end - start)`.
     */
    fun replace(
        start: Int,
        end: Int,
        text: String,
        cancel: HighlightCancellation,
        deadline: HighlightDeadline,
    ): HighlightCallResult {
        require(start in 0..end && end <= length) { "Edit $start..<$end outside 0..$length" }
        val firstLine = lineAt(start)
        val oldLastLine = lineAt(end)
        val removed = end - start
        val added = text.length
        val newLength = length - removed + added
        if (newLength > chars.size) chars = chars.copyOf(maxOf(newLength, chars.size * 2))
        chars.copyInto(chars, start + added, end, length)
        for (index in 0 until added) chars[start + index] = text[index]
        length = newLength

        var newlines = 0
        for (index in 0 until added) if (text[index] == '\n') newlines += 1
        val newLastLine = firstLine + newlines
        val lineShift = newLastLine - oldLastLine
        val newLineCount = lineCount + lineShift
        ensureLineCapacity(newLineCount)
        lineStarts.copyInto(lineStarts, oldLastLine + 1 + lineShift, oldLastLine + 1, lineCount)
        val offsetShift = added - removed
        for (line in newLastLine + 1 until newLineCount) lineStarts[line] += offsetShift
        var line = firstLine
        for (index in 0 until added) if (text[index] == '\n') lineStarts[++line] = start + index + 1

        val oldStateCount = stateCount
        if (oldStateCount > oldLastLine) {
            endStates.copyInto(endStates, newLastLine, oldLastLine, oldStateCount)
            staleStart = newLastLine
            staleEnd = oldStateCount + lineShift
        } else {
            staleStart = 0
            staleEnd = 0
        }
        stateCount = minOf(oldStateCount, firstLine)
        lineCount = newLineCount
        if (lexer == null) return plain(firstLine, newLastLine + 1)
        return lex(firstLine, lineCount, cancel, deadline, collect = true, stopAtConvergence = true)
    }

    /** Highlights lines [startLine, endLine), running a state-only pass first when they are below [knownStateLines]. */
    fun highlight(
        startLine: Int,
        endLine: Int,
        cancel: HighlightCancellation,
        deadline: HighlightDeadline,
    ): HighlightCallResult {
        val end = endLine.coerceIn(0, lineCount)
        return lex(startLine.coerceIn(0, end), end, cancel, deadline, collect = true, stopAtConvergence = false)
    }

    /**
     * The state-only pass on its own: computes end states up to [endLine] without emitting spans,
     * so a later [highlight] far down starts at once. The result covers [knownStateLines] before the
     * call through [endLine] and never has spans.
     */
    fun computeStates(
        endLine: Int,
        cancel: HighlightCancellation,
        deadline: HighlightDeadline,
    ): HighlightCallResult {
        val end = endLine.coerceIn(0, lineCount)
        return lex(minOf(stateCount, end), end, cancel, deadline, collect = false, stopAtConvergence = false)
    }

    private fun lex(
        startLine: Int,
        endLine: Int,
        cancel: HighlightCancellation,
        deadline: HighlightDeadline,
        collect: Boolean,
        stopAtConvergence: Boolean,
    ): HighlightCallResult {
        val lexer = lexer ?: return plain(startLine, endLine)
        val sink = SpanSink(cancel, deadline)
        var line = minOf(stateCount, startLine)
        var state = if (line == 0) 0 else endStates[line - 1]
        var resultEnd = endLine
        var sinceCheck = 0
        try {
            while (line < endLine) {
                if (sinceCheck >= CHECK_CHARS) {
                    sinceCheck = 0
                    if (sink.poll()) return stopped(sink, startLine, endLine, line, lineStarts[line])
                } else if (cancel.isCancelled) {
                    sink.poll()
                    return stopped(sink, startLine, endLine, line, lineStarts[line])
                }
                val lineStart = lineStarts[line]
                val lineEnd = minOf(contentEnd(line), lineStart + limits.maxLineLength)
                sink.collecting = collect && line >= startLine
                sink.beginLine(lineStart, lineEnd)
                state = lexer.lexLine(chars, lineStart, lineEnd, state, sink)
                if (sink.stopped) {
                    val reached = if (sink.collecting) sink.reached else lineStart
                    return stopped(sink, startLine, endLine, line, reached)
                }
                sinceCheck += lineEnd - lineStart + CHECK_LINE_COST
                sink.deadlineArmed = true
                if (line == stateCount) {
                    if (line >= staleStart && line < staleEnd && endStates[line] == state) {
                        // Converged: every old state after this line holds again.
                        stateCount = staleEnd
                        staleEnd = 0
                        staleStart = 0
                        if (stopAtConvergence && line >= startLine) {
                            resultEnd = line + 1
                            break
                        }
                        if (line + 1 < startLine) {
                            line = minOf(stateCount, startLine)
                            state = endStates[line - 1]
                            continue
                        }
                    } else {
                        endStates[line] = state
                        stateCount = line + 1
                    }
                }
                line += 1
            }
        } catch (failure: Throwable) {
            stateCount = minOf(stateCount, line)
            staleStart = 0
            staleEnd = 0
            return HighlightCallResult(
                status = HighlightStatus.Failed,
                startLine = startLine,
                endLine = endLine,
                highlightedEndLine = startLine,
                highlightedEnd = lineStarts[startLine],
                spans = HighlightSpans.empty,
                failure = failure::class.simpleName + (failure.message?.let { ": $it" } ?: ""),
            )
        }
        return HighlightCallResult(HighlightStatus.Complete, startLine, resultEnd, resultEnd, rangeEnd(resultEnd), sink.spans(), null)
    }

    private fun stopped(
        sink: SpanSink,
        startLine: Int,
        endLine: Int,
        line: Int,
        reached: Int,
    ): HighlightCallResult {
        if (sink.stoppedByCancel) {
            return HighlightCallResult(HighlightStatus.Cancelled, startLine, endLine, startLine, lineStarts[startLine], HighlightSpans.empty, null)
        }
        val highlightedLine = maxOf(line, startLine)
        val highlightedEnd = if (line < startLine) lineStarts[startLine] else reached
        return HighlightCallResult(HighlightStatus.Partial, startLine, endLine, highlightedLine, highlightedEnd, sink.spans(), null)
    }

    private fun plain(
        startLine: Int,
        endLine: Int,
    ) = HighlightCallResult(HighlightStatus.Complete, startLine, endLine, endLine, rangeEnd(endLine), HighlightSpans.empty, null)

    private fun rangeEnd(endLine: Int): Int = if (endLine >= lineCount) length else lineStarts[endLine]

    private fun contentEnd(line: Int): Int {
        if (line + 1 >= lineCount) return length
        val newline = lineStarts[line + 1] - 1
        return if (newline > lineStarts[line] && chars[newline - 1] == '\r') newline - 1 else newline
    }

    private fun addLineStart(offset: Int) {
        ensureLineCapacity(lineCount + 1)
        lineStarts[lineCount] = offset
        lineCount += 1
    }

    private fun ensureLineCapacity(lines: Int) {
        if (lines <= lineStarts.size) return
        val capacity = maxOf(lines, lineStarts.size * 2)
        lineStarts = lineStarts.copyOf(capacity)
        endStates = endStates.copyOf(capacity)
    }

    private fun checkLine(line: Int): Int {
        if (line < 0 || line >= lineCount) throw IndexOutOfBoundsException("Line $line of $lineCount")
        return line
    }

    private companion object {
        const val INITIAL_LINES = 64

        // The clock is read after about this many characters (each line counts a little extra).
        const val CHECK_CHARS = 8_192
        const val CHECK_LINE_COST = 128
    }
}
