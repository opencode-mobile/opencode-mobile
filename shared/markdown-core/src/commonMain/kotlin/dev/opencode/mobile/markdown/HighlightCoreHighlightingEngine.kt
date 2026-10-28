package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.core.HighlightCallResult
import dev.opencode.mobile.highlight.core.HighlightCancelFlag
import dev.opencode.mobile.highlight.core.HighlightCancellation
import dev.opencode.mobile.highlight.core.HighlightCore
import dev.opencode.mobile.highlight.core.HighlightDeadline
import dev.opencode.mobile.highlight.core.HighlightRole
import dev.opencode.mobile.highlight.core.HighlightSession
import dev.opencode.mobile.highlight.core.HighlightStatus

/**
 * Session Chat code fences on the #872 highlight-core library. A fence in a language
 * highlight-core does not support (or with no language) comes back with no spans and
 * [TextMateParseOutcome.Unsupported]: plain text, never an error.
 *
 * Each [CodeBlockKey] keeps one [HighlightSession] and the spans of its last revision. A new
 * revision is applied as an edit of the range between the common prefix and the common suffix,
 * so a streamed fence re-lexes from its last changed line and a mid-text edit stops once line
 * states match again. Sessions
 * are kept least-recently-used within [maxSessions] and [maxCharacters], and dropped on [trim].
 *
 * Every call runs under [deadlineMillis]: past it the fence comes back with its highlighted
 * prefix and a plain rest, and the next request for the same text continues from there. A newer
 * revision of a key cancels an older one still running; a revision older than the one already
 * applied comes back [TextMateParseOutcome.Cancelled].
 *
 * Calls may come from any thread; the sessions behind them run one call at a time.
 */
class HighlightCoreHighlightingEngine(
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
    private val deadlineMillis: Double = DEFAULT_DEADLINE_MILLIS,
) : TextMateHighlightingEngine {
    private class Entry(
        val session: HighlightSession,
        var revision: Long,
        var source: String,
        /** Packed `start, length, role` for lines [0, coloredEndLine) of [source]. */
        var spans: IntArray,
        var coloredEndLine: Int,
    ) {
        val isComplete: Boolean
            get() = coloredEndLine >= session.lineCount
    }

    private class InFlight(
        val revision: Long,
        val flag: HighlightCancelFlag,
    )

    private class Either(
        val first: HighlightCancellation,
        val second: HighlightCancellation,
    ) : HighlightCancellation {
        override val isCancelled: Boolean
            get() = first.isCancelled || second.isCancelled
    }

    private val sessionLock = createTextMateSchedulerLock()
    private val flagLock = createTextMateSchedulerLock()

    // Insertion order is recency: a used entry is removed and put back at the end.
    private val sessions = LinkedHashMap<String, Entry>()
    private var retainedCharacters = 0
    private val inFlight = mutableMapOf<String, InFlight>()

    /** Sessions held now, for tests and diagnostics. */
    val sessionCount: Int
        get() = sessionLock.withLock { sessions.size }

    /** True when highlight-core handles [language]; anything else comes back unsupported. */
    fun supports(language: String?): Boolean = language != null && HighlightCore.supports(language)

    override suspend fun highlight(request: HighlightRequest): HighlightResult = highlightNow(request)

    override suspend fun trim(level: MemoryPressure) {
        trimNow(level)
    }

    /** Drops sessions for [level]: Moderate keeps the most recent half, Critical keeps none. */
    fun trimNow(level: MemoryPressure) {
        sessionLock.withLock {
            when (level) {
                MemoryPressure.Normal -> Unit
                MemoryPressure.Moderate -> evict(maxSessions / 2, maxCharacters / 2)
                MemoryPressure.Critical -> {
                    sessions.clear()
                    retainedCharacters = 0
                }
            }
        }
    }

    /**
     * The synchronous core of [highlight], on the caller's thread. [cancel] stops it early (with a
     * coroutine, pass one that reads the job), as does a newer revision of the same key. An
     * unsupported or missing language comes back [TextMateParseOutcome.Unsupported].
     */
    fun highlightNow(
        request: HighlightRequest,
        cancel: HighlightCancellation = HighlightCancellation.Never,
    ): HighlightResult = highlightReporting(request, cancel).result

    /** [highlightNow] plus how the call reached its spans, for tests. */
    internal fun highlightReporting(
        request: HighlightRequest,
        cancel: HighlightCancellation = HighlightCancellation.Never,
    ): Reported {
        val language = request.language?.let(HighlightCore::canonicalLanguage)
        if (language == null) return Reported(emptyResult(request, TextMateParseOutcome.Unsupported))
        val flag = HighlightCancelFlag()
        flagLock.withLock {
            val running = inFlight[request.key.value]
            if (running != null && running.revision < request.revision.value) running.flag.cancel()
            if (running == null || running.revision <= request.revision.value) {
                inFlight[request.key.value] = InFlight(request.revision.value, flag)
            }
        }
        return try {
            val combined = if (cancel === HighlightCancellation.Never) flag else Either(flag, cancel)
            sessionLock.withLock { highlightLocked(request, language, combined) }
        } finally {
            flagLock.withLock {
                if (inFlight[request.key.value]?.flag === flag) inFlight.remove(request.key.value)
            }
        }
    }

    private fun highlightLocked(
        request: HighlightRequest,
        language: String,
        cancel: HighlightCancellation,
    ): Reported {
        val key = request.key.value
        val revision = request.revision.value
        val existing = sessions.remove(key)
        existing?.let { retainedCharacters -= it.source.length }
        if (existing != null && revision < existing.revision) {
            keep(key, existing)
            return Reported(emptyResult(request, TextMateParseOutcome.Cancelled))
        }
        val deadline = HighlightDeadline.after(deadlineMillis)
        val source = request.source
        val entry =
            existing?.takeIf { it.session.language == language }
                ?: Entry(HighlightSession(language), revision, "", IntArray(0), 0)
        val oldSource = entry.source
        val oldSpans = entry.spans
        val session = entry.session
        val mode: String
        val call: HighlightCallResult?
        val prefix = commonPrefix(oldSource, source)
        when {
            entry !== existing || (oldSource != source && !entry.isComplete) -> {
                mode = MODE_OPEN
                call = session.open(source, Int.MAX_VALUE, cancel, deadline)
            }
            oldSource == source && entry.isComplete -> {
                mode = MODE_REUSE
                call = null
            }
            oldSource == source -> {
                mode = MODE_CONTINUE
                call = session.highlight(entry.coloredEndLine, session.lineCount, cancel, deadline)
            }
            else -> {
                mode = MODE_EDIT
                val suffix = commonSuffix(oldSource, source, prefix)
                call =
                    session.replace(
                        prefix,
                        oldSource.length - suffix,
                        source.substring(prefix, source.length - suffix),
                        cancel,
                        deadline,
                    )
            }
        }
        if (call != null && (call.status == HighlightStatus.Cancelled || call.status == HighlightStatus.Failed)) {
            // Spans past the edit are unknown now; the next request opens the fence again.
            val outcome =
                if (call.status == HighlightStatus.Cancelled) TextMateParseOutcome.Cancelled else TextMateParseOutcome.Failed
            val failed = emptyResult(request, outcome)
            val failure = call.failure ?: return Reported(failed)
            return Reported(failed.copy(diagnostics = listOf(MarkdownDiagnostic(MarkdownDiagnosticKind.HighlighterFallback, failure))))
        }
        var reusedLines = 0
        var suffixLines = 0
        if (call != null) {
            val keepBefore = if (mode == MODE_OPEN) 0 else rangeStart(session, call.startLine)
            reusedLines = if (mode == MODE_OPEN) 0 else call.startLine
            val complete = call.status == HighlightStatus.Complete
            val coloredEnd = if (complete) call.endLine else call.highlightedEndLine
            val merged = SpanBuffer(oldSpans.size + call.spans.intCount)
            merged.addClipped(oldSpans, oldSpans.size, 0, keepBefore, 0)
            val fresh = call.spans.toIntArray()
            merged.addClipped(fresh, fresh.size, keepBefore, rangeStart(session, coloredEnd), 0)
            if (complete && call.endLine < session.lineCount) {
                // Converged edit: the lines after the result keep their spans, shifted by the edit.
                val delta = source.length - oldSource.length
                val oldResume = rangeStart(session, call.endLine) - delta
                merged.addClipped(oldSpans, oldSpans.size, oldResume, Int.MAX_VALUE, delta)
                suffixLines = session.lineCount - call.endLine
                entry.coloredEndLine = session.lineCount
            } else {
                entry.coloredEndLine = coloredEnd
            }
            entry.spans = merged.toIntArray()
        }
        entry.revision = revision
        entry.source = source
        keep(key, entry)
        val spans = toCodeSpans(entry.spans)
        val partial = call != null && call.status == HighlightStatus.Partial
        val outcome = if (spans.isEmpty()) TextMateParseOutcome.NoSpans else TextMateParseOutcome.Highlighted
        return Reported(
            result =
                HighlightResult(
                    key = request.key,
                    revision = request.revision,
                    source = source,
                    canonicalLanguage = language,
                    policyEpoch = request.policyEpoch,
                    spans = spans,
                    outcome = outcome,
                    diagnostics = emptyList(),
                ),
            mode = mode,
            reusedLines = if (call == null) session.lineCount else reusedLines,
            retokenizedLines = call?.let { it.highlightedEndLine - it.startLine } ?: 0,
            suffixReusedLines = suffixLines,
            partial = partial,
        )
    }

    private fun keep(
        key: String,
        entry: Entry,
    ) {
        sessions[key] = entry
        retainedCharacters += entry.source.length
        evict(maxSessions, maxCharacters, protect = key)
    }

    /** Drops least-recently-used sessions until both bounds hold, never [protect]. */
    private fun evict(
        sessionLimit: Int,
        characterLimit: Int,
        protect: String? = null,
    ) {
        val iterator = sessions.entries.iterator()
        while ((sessions.size > sessionLimit || retainedCharacters > characterLimit) && iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (key == protect) continue
            iterator.remove()
            retainedCharacters -= entry.source.length
        }
    }

    /** Packed spans built by appending clipped, shifted ranges in order. */
    private class SpanBuffer(
        capacity: Int,
    ) {
        private var ints = IntArray(capacity)
        private var size = 0

        /** Adds spans of [source] that start in [from, until), cut at [until], moved by [shift]. */
        fun addClipped(
            source: IntArray,
            sourceSize: Int,
            from: Int,
            until: Int,
            shift: Int,
        ) {
            var index = 0
            while (index < sourceSize) {
                val start = source[index]
                val end = start + source[index + 1]
                if (start >= from && start < until) {
                    if (size + 3 > ints.size) ints = ints.copyOf(maxOf(size + 3, ints.size * 2))
                    ints[size] = start + shift
                    ints[size + 1] = minOf(end, until) - start
                    ints[size + 2] = source[index + 2]
                    size += 3
                }
                index += 3
            }
        }

        fun toIntArray(): IntArray = ints.copyOf(size)
    }

    /**
     * One call's result and how it got there: [mode] is Open, Edit, Continue, or Reuse (null when
     * the call returned before reaching a session), and [partial] means the deadline passed.
     */
    internal class Reported(
        val result: HighlightResult,
        val mode: String? = null,
        val reusedLines: Int = 0,
        val retokenizedLines: Int = 0,
        val suffixReusedLines: Int = 0,
        val partial: Boolean = false,
    )

    companion object {
        const val DEFAULT_MAX_SESSIONS = 64
        const val DEFAULT_MAX_CHARACTERS = 1_000_000
        const val DEFAULT_DEADLINE_MILLIS = 50.0

        private const val MODE_OPEN = "Open"
        private const val MODE_EDIT = "Edit"
        private const val MODE_CONTINUE = "Continue"
        private const val MODE_REUSE = "Reuse"

        private val roles: Array<CodeTokenRole> =
            HighlightRole.entries.map { role -> CodeTokenRole.valueOf(role.name) }.toTypedArray()

        /** Packed `start, length, role` triples as [CodeSpan]s, leaving out Plain. */
        internal fun toCodeSpans(packed: IntArray): List<CodeSpan> =
            buildList(packed.size / 3) {
                var index = 0
                while (index < packed.size) {
                    val role = roles.getOrNull(packed[index + 2]) ?: CodeTokenRole.Plain
                    if (role != CodeTokenRole.Plain) add(CodeSpan(packed[index], packed[index] + packed[index + 1], role))
                    index += 3
                }
            }

        private fun rangeStart(
            session: HighlightSession,
            line: Int,
        ): Int = if (line >= session.lineCount) session.length else session.lineStart(line)

        private fun commonPrefix(
            first: String,
            second: String,
        ): Int {
            val limit = minOf(first.length, second.length)
            var index = 0
            while (index < limit && first[index] == second[index]) index++
            return index
        }

        /** Matching characters at the ends of both strings, not reaching into the first [prefix]. */
        private fun commonSuffix(
            first: String,
            second: String,
            prefix: Int,
        ): Int {
            val limit = minOf(first.length, second.length) - prefix
            var count = 0
            while (count < limit && first[first.length - 1 - count] == second[second.length - 1 - count]) count++
            return count
        }
    }
}

/** A result for [request] with no spans and no language, for calls that end before highlighting. */
fun emptyResult(
    request: HighlightRequest,
    outcome: TextMateParseOutcome,
): HighlightResult =
    HighlightResult(
        key = request.key,
        revision = request.revision,
        source = request.source,
        canonicalLanguage = null,
        policyEpoch = request.policyEpoch,
        spans = emptyList(),
        outcome = outcome,
        diagnostics = emptyList(),
    )
