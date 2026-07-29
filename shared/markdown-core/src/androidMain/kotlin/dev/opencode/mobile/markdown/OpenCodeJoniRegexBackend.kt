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

class OpenCodeJoniRegexRuntime(
    private val cacheCoordinator: TextMateWeightedCacheCoordinator = TextMateWeightedCacheCoordinator(),
) : OpenCodeTextMateRegexBackend,
    TextMateCacheTrimmable {
    override val name: String = "joni-oniguruma"

    override val isOnigurumaCompatible: Boolean = true

    private sealed class RegexCacheEntry {
        data class Compiled(
            val regex: Regex,
            val cacheKey: String,
            val estimatedBytes: Long,
        ) : RegexCacheEntry()

        data class Decomposed(
            val candidates: List<String>,
        ) : RegexCacheEntry()

        data object Failed : RegexCacheEntry()
    }

    private val regexCache = ConcurrentHashMap<String, RegexCacheEntry>()
    private val activeSearchPins = ConcurrentHashMap<String, Int>()

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
            val cacheKey = regexCacheKey(pattern)
            val estimated = TextMateWeightedCacheCoordinator.estimateRegexBytes(pattern)
            cacheCoordinator.promote(
                key = cacheKey,
                category = TextMateWeightedCacheCoordinator.Category.CompiledRegex,
                estimatedBytes = estimated,
            )
            return RegexCacheEntry.Compiled(regex, cacheKey, estimated)
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
        buffer: PreparedTextMateLineBuffer,
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
                return find(suffix, buffer, startIndex, endIndex, contiguousAnchor, metricsSink)
                    ?.takeIf { match -> match.start == startIndex }
            }
        }

        when (val entry = regexCache.getOrPut(effectivePattern) { resolvePattern(effectivePattern, metricsSink) }) {
            is RegexCacheEntry.Compiled ->
                return OpenCodeTextMateRegexContiguousAnchorSupport.enforceContiguousAnchor(
                    pattern = effectivePattern,
                    match = search(entry, buffer, startIndex, endIndex, metricsSink),
                    contiguousAnchor = contiguousAnchor,
                )
            is RegexCacheEntry.Decomposed -> {
                return entry.candidates
                    .mapNotNull { candidate -> find(candidate, buffer, startIndex, endIndex, contiguousAnchor, metricsSink) }
                    .minByOrNull { match -> match.start }
            }
            RegexCacheEntry.Failed -> return null
        }
    }

    private fun search(
        entry: RegexCacheEntry.Compiled,
        buffer: PreparedTextMateLineBuffer,
        startIndex: Int,
        endIndex: Int,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): TextMateRegexMatch? {
        pin(entry.cacheKey)
        try {
            val indexMap = buffer.indexMap
            val inputBytes = buffer.bytes
            val inputLength = buffer.length

            val startByte = indexMap.byteIndexFor(startIndex.coerceIn(0, inputLength))
            val endByte = indexMap.byteIndexFor(endIndex.coerceIn(0, inputLength))
            val matcher = entry.regex.matcher(inputBytes)
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
        } finally {
            unpin(entry.cacheKey)
        }
    }

    override fun trimCaches(level: MemoryPressure) {
        when (level) {
            MemoryPressure.Normal -> Unit
            MemoryPressure.Moderate,
            MemoryPressure.Critical,
            -> {
                val victims =
                    regexCache.entries
                        .filter { (pattern, entry) ->
                            entry is RegexCacheEntry.Compiled &&
                                (activeSearchPins[entry.cacheKey] ?: 0) == 0
                        }.map { it.key }
                victims.forEach { pattern ->
                    val removed = regexCache.remove(pattern)
                    if (removed is RegexCacheEntry.Compiled) {
                        cacheCoordinator.removeIfUnleased(removed.cacheKey)
                    }
                }
                cacheCoordinator.trim(level)
            }
        }
    }

    private fun pin(cacheKey: String) {
        activeSearchPins.merge(cacheKey, 1, Int::plus)
        cacheCoordinator.lease(cacheKey)
    }

    private fun unpin(cacheKey: String) {
        activeSearchPins.compute(cacheKey) { _, count ->
            val next = (count ?: 1) - 1
            if (next <= 0) null else next
        }
        cacheCoordinator.release(cacheKey)
    }

    private fun regexCacheKey(pattern: String): String = "joni:$pattern"
}
