package dev.opencode.mobile.markdown

import kotlinx.serialization.Serializable

@Serializable
enum class TextMateMetricStage {
    GrammarLoad,
    GrammarLookup,
    RuleResolve,
    RegexCompile,
    RegexSearch,
    NativeRegexSearch,
    PreparedLineBuffer,
    Utf8IndexMap,
    Tokenize,
    Repair,
    IncrementalReuse,
}

@Serializable
enum class TextMateMetricOutcome {
    Success,
    Failure,
    CacheHit,
    CacheMiss,
}

@Serializable
data class TextMateMetricEvent(
    val stage: TextMateMetricStage,
    val outcome: TextMateMetricOutcome,
    val durationNanoseconds: Long = 0,
    val backend: String? = null,
    val sourceCharacters: Int? = null,
    val sourceLines: Int? = null,
    val reusedLineCount: Int? = null,
    val retokenizedLineCount: Int? = null,
    val suffixReusedLineCount: Int? = null,
)

fun interface OpenCodeTextMateMetricsSink {
    fun record(event: TextMateMetricEvent)

    val isEnabled: Boolean
        get() = true
}

object NoOpOpenCodeTextMateMetricsSink : OpenCodeTextMateMetricsSink {
    override val isEnabled: Boolean = false

    override fun record(event: TextMateMetricEvent) = Unit
}

@Serializable
enum class TextMateParseOutcome {
    Highlighted,
    NoSpans,
    Unsupported,
    Fallback,
    Failed,
    Cancelled,
    BudgetExceeded,
}

@Serializable
data class TextMateStageSummary(
    val stage: TextMateMetricStage,
    val eventCount: Int,
    val totalNanoseconds: Long,
    val maximumNanoseconds: Long,
    val successCount: Int,
    val failureCount: Int,
    val cacheHitCount: Int,
    val cacheMissCount: Int,
)

@Serializable
data class TextMateParseObservation(
    val schemaVersion: Int = 2,
    val requestedLanguage: String? = null,
    val canonicalLanguage: String? = null,
    val outcome: TextMateParseOutcome,
    val messageCharacters: Int,
    val fenceCharacters: Int,
    val fenceLines: Int,
    val fenceIndex: Int,
    val fenceCount: Int,
    val spanCount: Int,
    val backend: String? = null,
    val totalNanoseconds: Long,
    val stages: List<TextMateStageSummary>,
    val diagnosticKinds: List<MarkdownDiagnosticKind>,
    val queueWaitNanoseconds: Long = 0,
    val cancelRequestedToStopNanoseconds: Long? = null,
    val schedulingReason: String? = null,
    val reusedLineCount: Int = 0,
    val retokenizedLineCount: Int = 0,
    val suffixReusedLineCount: Int = 0,
    val incrementalMode: String? = null,
)

internal class OpenCodeTextMateMetricsAccumulator : OpenCodeTextMateMetricsSink {
    private data class MutableStageSummary(
        var eventCount: Int = 0,
        var totalNanoseconds: Long = 0,
        var maximumNanoseconds: Long = 0,
        var successCount: Int = 0,
        var failureCount: Int = 0,
        var cacheHitCount: Int = 0,
        var cacheMissCount: Int = 0,
    )

    private val summaries = mutableMapOf<TextMateMetricStage, MutableStageSummary>()
    private val backends = linkedSetOf<String>()

    override fun record(event: TextMateMetricEvent) {
        val summary = summaries.getOrPut(event.stage, ::MutableStageSummary)
        summary.eventCount += 1
        summary.totalNanoseconds = summary.totalNanoseconds.saturatedPlus(event.durationNanoseconds)
        summary.maximumNanoseconds = maxOf(summary.maximumNanoseconds, event.durationNanoseconds)
        when (event.outcome) {
            TextMateMetricOutcome.Success -> summary.successCount += 1
            TextMateMetricOutcome.Failure -> summary.failureCount += 1
            TextMateMetricOutcome.CacheHit -> summary.cacheHitCount += 1
            TextMateMetricOutcome.CacheMiss -> summary.cacheMissCount += 1
        }
        event.backend?.takeIf(String::isNotBlank)?.let(backends::add)
    }

    fun stageSummaries(): List<TextMateStageSummary> =
        TextMateMetricStage.entries.mapNotNull { stage ->
            summaries[stage]?.let { summary ->
                TextMateStageSummary(
                    stage = stage,
                    eventCount = summary.eventCount,
                    totalNanoseconds = summary.totalNanoseconds,
                    maximumNanoseconds = summary.maximumNanoseconds,
                    successCount = summary.successCount,
                    failureCount = summary.failureCount,
                    cacheHitCount = summary.cacheHitCount,
                    cacheMissCount = summary.cacheMissCount,
                )
            }
        }

    fun backendName(): String? = backends.takeIf { it.isNotEmpty() }?.joinToString("+")

    private fun Long.saturatedPlus(other: Long): Long =
        if (other > 0 && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
}
