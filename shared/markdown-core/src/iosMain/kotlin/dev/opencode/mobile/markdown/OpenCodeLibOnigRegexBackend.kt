package dev.opencode.mobile.markdown

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import oniguruma.ONIG_ENCODING_UTF8
import oniguruma.ONIG_OPTION_CAPTURE_GROUP
import oniguruma.ONIG_OPTION_MULTILINE
import oniguruma.ONIG_OPTION_NONE
import oniguruma.ONIG_SYNTAX_RUBY
import oniguruma.OnigErrorInfo
import oniguruma.OnigRegexType
import oniguruma.onig_free
import oniguruma.onig_new
import oniguruma.onig_region_free
import oniguruma.onig_region_new
import oniguruma.onig_search
import oniguruma.opencode_onig_region_beg_at
import oniguruma.opencode_onig_region_end_at
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
private sealed class LibOnigRegexCacheEntry {
    data class Compiled(
        val regex: CPointer<OnigRegexType>,
    ) : LibOnigRegexCacheEntry()

    data class Decomposed(
        val candidates: List<String>,
    ) : LibOnigRegexCacheEntry()

    data object Failed : LibOnigRegexCacheEntry()
}

internal data class OpenCodeLibOnigAllocationSnapshot(
    val allocations: Long,
    val frees: Long,
) {
    val live: Long = allocations - frees
}

@OptIn(ExperimentalForeignApi::class)
class OpenCodeLibOnigRegexRuntime : OpenCodeTextMateRegexBackend {
    override val name: String = "libonig"

    override val isOnigurumaCompatible: Boolean = true

    private val libOnigRegexCache: MutableMap<String, LibOnigRegexCacheEntry> = mutableMapOf()
    private var libOnigAllocations: Long = 0
    private var libOnigFrees: Long = 0

    private fun resolvePattern(
        pattern: String,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): LibOnigRegexCacheEntry {
        compilePattern(pattern, metricsSink)?.let { regex ->
            return LibOnigRegexCacheEntry.Compiled(regex)
        }

        val candidates =
            OpenCodeTextMateRegexAlternationSupport
                .decompositionCandidates(pattern)
                ?.filter { candidate ->
                    val probe = compilePattern(candidate, metricsSink) ?: return@filter false
                    freeRegex(probe)
                    true
                }
                .orEmpty()
        if (candidates.isNotEmpty()) {
            return LibOnigRegexCacheEntry.Decomposed(candidates)
        }

        return LibOnigRegexCacheEntry.Failed
    }

    private fun compilePattern(
        pattern: String,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): CPointer<OnigRegexType>? {
        val patternBytes = pattern.encodeToByteArray()
        val startedAt = if (metricsSink.isEnabled) TimeSource.Monotonic.markNow() else null
        val compiled =
            memScoped {
                val regexOut = alloc<CPointerVar<OnigRegexType>>()
                val errorInfo = alloc<OnigErrorInfo>()
                withUtf8Range(patternBytes) { patternStart, patternEnd ->
                    val compileResult =
                        onig_new(
                            regexOut.ptr,
                            patternStart,
                            patternEnd,
                            ONIG_OPTION_MULTILINE or ONIG_OPTION_CAPTURE_GROUP,
                            ONIG_ENCODING_UTF8,
                            ONIG_SYNTAX_RUBY,
                            errorInfo.ptr,
                        )
                    if (compileResult != 0) {
                        null
                    } else {
                        regexOut.value?.also { libOnigAllocations += 1 }
                    }
                }
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

    internal fun allocationSnapshot(): OpenCodeLibOnigAllocationSnapshot =
        OpenCodeLibOnigAllocationSnapshot(
            allocations = libOnigAllocations,
            frees = libOnigFrees,
        )

    internal fun clearCache() {
        libOnigRegexCache.values.forEach { entry ->
            if (entry is LibOnigRegexCacheEntry.Compiled) {
                freeRegex(entry.regex)
            }
        }
        libOnigRegexCache.clear()
    }

    private fun freeRegex(regex: CPointer<OnigRegexType>) {
        onig_free(regex)
        libOnigFrees += 1
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

        val entry = libOnigRegexCache.getOrPut(effectivePattern) { resolvePattern(effectivePattern, metricsSink) }

        return when (entry) {
            is LibOnigRegexCacheEntry.Compiled ->
                OpenCodeTextMateRegexContiguousAnchorSupport.enforceContiguousAnchor(
                    pattern = effectivePattern,
                    match = search(entry.regex, input, startIndex, endIndex, metricsSink),
                    contiguousAnchor = contiguousAnchor,
                )
            is LibOnigRegexCacheEntry.Decomposed ->
                entry.candidates
                    .mapNotNull { candidate -> find(candidate, input, startIndex, endIndex, contiguousAnchor, metricsSink) }
                    .minByOrNull { match -> match.start }
            LibOnigRegexCacheEntry.Failed -> null
        }
    }

    private fun search(
        regex: CPointer<OnigRegexType>,
        input: String,
        startIndex: Int,
        endIndex: Int,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): TextMateRegexMatch? {
        val indexMap = Utf8IndexMap(input)
        val inputBytes = indexMap.bytes

        return withUtf8Range(inputBytes) { stringStart, stringEnd ->
            memScoped {
                val region = onig_region_new() ?: return@withUtf8Range null
                try {
                    val searchStartByte = indexMap.byteIndexFor(startIndex.coerceIn(0, input.length))
                    val searchEndByte = indexMap.byteIndexFor(endIndex.coerceIn(0, input.length))
                    val searchStart = stringStart + searchStartByte ?: stringStart
                    val searchEnd = stringStart + searchEndByte ?: stringEnd

                    val matchStart =
                        if (metricsSink.isEnabled) {
                            val startedAt = TimeSource.Monotonic.markNow()
                            var outcome = TextMateMetricOutcome.Success
                            try {
                                onig_search(
                                    regex,
                                    stringStart,
                                    stringEnd,
                                    searchStart,
                                    searchEnd,
                                    region,
                                    ONIG_OPTION_NONE,
                                )
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
                            onig_search(
                                regex,
                                stringStart,
                                stringEnd,
                                searchStart,
                                searchEnd,
                                region,
                                ONIG_OPTION_NONE,
                            )
                        }
                    if (matchStart < 0) return@withUtf8Range null

                    val regionData = region.pointed
                    val captures =
                        (0 until regionData.num_regs)
                            .mapNotNull { index ->
                                val begin = opencode_onig_region_beg_at(region, index)
                                val end = opencode_onig_region_end_at(region, index)
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

                    val matchEnd = opencode_onig_region_end_at(region, 0)
                    TextMateRegexMatch(
                        start = indexMap.charIndexFor(matchStart),
                        end = indexMap.charIndexFor(matchEnd),
                        captures = captures,
                    )
                } finally {
                    onig_region_free(region, 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private val emptyUtf8PointerAnchor = byteArrayOf(0)

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withUtf8Range(
    bytes: ByteArray,
    block: (CPointer<UByteVar>, CPointer<UByteVar>) -> T,
): T {
    if (bytes.isEmpty()) {
        return emptyUtf8PointerAnchor.usePinned { pinned ->
            val start = pinned.addressOf(0).reinterpret<UByteVar>()
            block(start, start)
        }
    }

    return bytes.usePinned { pinned ->
        val start = pinned.addressOf(0).reinterpret<UByteVar>()
        val end = start + bytes.size
        block(start, end ?: start)
    }
}
