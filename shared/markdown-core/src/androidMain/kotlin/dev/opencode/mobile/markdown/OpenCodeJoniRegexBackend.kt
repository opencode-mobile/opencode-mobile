package dev.opencode.mobile.markdown

import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import org.jcodings.specific.UTF8Encoding
import org.joni.Option
import org.joni.Regex
import org.joni.Syntax
import org.joni.exception.JOniException
import kotlin.time.TimeSource

class OpenCodeJoniRegexRuntime : OpenCodeTextMateRegexBackend {
    override val name: String = "joni-oniguruma"

    override val isOnigurumaCompatible: Boolean = true

    private sealed class RegexCacheEntry {
        data class Compiled(
            val regex: Regex,
        ) : RegexCacheEntry()

        data class Decomposed(
            val candidates: List<String>,
        ) : RegexCacheEntry()

        data object Failed : RegexCacheEntry()
    }

    private val regexCache = ConcurrentHashMap<String, RegexCacheEntry>()

    // Joni prints Oniguruma parse warnings to System.err for many bundled TextMate
    // patterns. Compilation is cached, but cold-start still emits thousands of lines
    // to logcat and pays stderr I/O per unique pattern.
    private val compileLock = Any()
    private val silentErr =
        PrintStream(
            object : OutputStream() {
                override fun write(value: Int) = Unit
            },
        )

    private fun resolvePattern(
        pattern: String,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): RegexCacheEntry {
        compilePattern(pattern, metricsSink)?.let { regex ->
            return RegexCacheEntry.Compiled(regex)
        }

        val candidates =
            OpenCodeTextMateRegexAlternationSupport
                .decompositionCandidates(pattern)
                ?.filter { candidate -> compilePattern(candidate, metricsSink) != null }
                .orEmpty()
        if (candidates.isNotEmpty()) {
            return RegexCacheEntry.Decomposed(candidates)
        }

        return RegexCacheEntry.Failed
    }

    private fun compilePattern(
        pattern: String,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): Regex? {
        val patternBytes = pattern.encodeToByteArray()
        val startedAt = if (metricsSink.isEnabled) TimeSource.Monotonic.markNow() else null
        synchronized(compileLock) {
            val originalErr = System.err
            val compiled =
                try {
                    System.setErr(silentErr)
                    try {
                        Regex(
                            patternBytes,
                            0,
                            patternBytes.size,
                            Option.MULTILINE or Option.CAPTURE_GROUP,
                            UTF8Encoding.INSTANCE,
                            Syntax.RUBY,
                        )
                    } catch (_: JOniException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                } finally {
                    System.setErr(originalErr)
                }
            startedAt?.let {
                metricsSink.record(
                    TextMateMetricEvent(
                        stage = TextMateMetricStage.RegexCompile,
                        outcome = if (compiled == null) TextMateMetricOutcome.Failure else TextMateMetricOutcome.Success,
                        durationNanoseconds = it.elapsedNow().inWholeNanoseconds,
                        backend = name,
                    ),
                )
            }
            return compiled
        }
    }

    override fun find(
        pattern: String,
        input: String,
        startIndex: Int,
        endIndex: Int,
        contiguousAnchor: Int?,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): TextMateRegexMatch? {
        val normalizedPattern = OpenCodeTextMateRegexLookbehindSupport.normalize(pattern)
        val effectivePattern =
            OpenCodeTextMateRegexContiguousAnchorSupport.effectivePattern(
                pattern = normalizedPattern,
                startIndex = startIndex,
                contiguousAnchor = contiguousAnchor,
            )

        if (OpenCodeTextMateRegexContiguousAnchorSupport.rejectIfAnchorMismatch(effectivePattern, startIndex, contiguousAnchor)) {
            return null
        }

        when {
            effectivePattern == "\\G" ->
                return OpenCodeTextMateRegexContiguousAnchorSupport.contiguousAnchorStartMatch(
                    startIndex = startIndex,
                    endIndex = endIndex,
                    contiguousAnchor = contiguousAnchor,
                )
            effectivePattern.startsWith("\\G") -> {
                val suffix = effectivePattern.removePrefix("\\G")
                if (suffix.isEmpty()) {
                    return OpenCodeTextMateRegexContiguousAnchorSupport.contiguousAnchorStartMatch(
                        startIndex = startIndex,
                        endIndex = endIndex,
                        contiguousAnchor = contiguousAnchor,
                    )
                }
                return find(suffix, input, startIndex, endIndex, contiguousAnchor, metricsSink)
                    ?.takeIf { match -> match.start == startIndex }
            }
        }

        when (val entry = regexCache.getOrPut(effectivePattern) { resolvePattern(effectivePattern, metricsSink) }) {
            is RegexCacheEntry.Compiled ->
                return OpenCodeTextMateRegexContiguousAnchorSupport.enforceContiguousAnchor(
                    pattern = effectivePattern,
                    match = search(entry.regex, input, startIndex, endIndex, metricsSink),
                    contiguousAnchor = contiguousAnchor,
                )
            is RegexCacheEntry.Decomposed -> {
                return entry.candidates
                    .mapNotNull { candidate -> find(candidate, input, startIndex, endIndex, contiguousAnchor, metricsSink) }
                    .minByOrNull { match -> match.start }
            }
            RegexCacheEntry.Failed -> return null
        }
    }

    private fun search(
        regex: Regex,
        input: String,
        startIndex: Int,
        endIndex: Int,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): TextMateRegexMatch? {
        val indexMap = Utf8IndexMap(input)
        val inputBytes = indexMap.bytes

        val startByte = indexMap.byteIndexFor(startIndex.coerceIn(0, input.length))
        val endByte = indexMap.byteIndexFor(endIndex.coerceIn(0, input.length))
        val matcher = regex.matcher(inputBytes)
        val result =
            if (metricsSink.isEnabled) {
                val startedAt = TimeSource.Monotonic.markNow()
                var outcome = TextMateMetricOutcome.Success
                try {
                    matcher.search(startByte, endByte, Option.NONE)
                } catch (failure: Throwable) {
                    outcome = TextMateMetricOutcome.Failure
                    throw failure
                } finally {
                    metricsSink.record(
                        TextMateMetricEvent(
                            stage = TextMateMetricStage.NativeRegexSearch,
                            outcome = outcome,
                            durationNanoseconds = startedAt.elapsedNow().inWholeNanoseconds,
                            backend = name,
                        ),
                    )
                }
            } else {
                matcher.search(startByte, endByte, Option.NONE)
            }
        if (result < 0) return null

        val region = matcher.eagerRegion
        val captures =
            (0 until region.numRegs)
                .mapNotNull { index ->
                    val begin = region.getBeg(index)
                    val end = region.getEnd(index)
                    if (begin < 0 || end < begin) {
                        null
                    } else {
                        TextMateRegexCapture(
                            index = index,
                            start = indexMap.charIndexFor(begin),
                            end = indexMap.charIndexFor(end),
                        )
                    }
                }

        return TextMateRegexMatch(
            start = indexMap.charIndexFor(result),
            end = indexMap.charIndexFor(matcher.end),
            captures = captures,
        )
    }
}
