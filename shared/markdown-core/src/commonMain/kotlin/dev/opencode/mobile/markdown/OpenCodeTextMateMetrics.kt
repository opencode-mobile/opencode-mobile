package dev.opencode.mobile.markdown

enum class TextMateMetricStage {
    GrammarLoad,
    GrammarLookup,
    RuleResolve,
    RegexCompile,
    RegexSearch,
    NativeRegexSearch,
    Tokenize,
    Repair,
}

enum class TextMateMetricOutcome {
    Success,
    Failure,
    CacheHit,
    CacheMiss,
}

data class TextMateMetricEvent(
    val stage: TextMateMetricStage,
    val outcome: TextMateMetricOutcome,
    val durationNanoseconds: Long = 0,
    val backend: String? = null,
    val sourceCharacters: Int? = null,
    val sourceLines: Int? = null,
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
