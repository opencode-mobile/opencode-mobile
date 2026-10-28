package dev.opencode.mobile.highlight.bench

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * The seam the benchmark measures: any highlighting engine, current or new, behind one
 * synchronous call. Adapters live with the engine (markdown-core wraps the TextMate engine).
 */
interface HighlightBenchEngine {
    /** Stable key for pairing results, for example "textmate". */
    val id: String

    /** Shown in tables and reports, for example "TextMate". */
    val displayName: String

    /**
     * A fresh engine instance with its own caches. Cold start and the heavy cases each take a
     * new one; creating it is not timed, its first call is.
     */
    fun newHighlighter(): HighlightBenchHighlighter

    /**
     * False for a language the engine has no highlighter for yet. A run skips such cases for this
     * engine instead of timing a call that returns plain text; viewers still call the highlighter.
     */
    fun supportsLanguage(language: String): Boolean = true
}

fun interface HighlightBenchHighlighter {
    /**
     * Highlights [source] on the calling thread. Implementations poll [cancel] as often as
     * their engine checks cancellation; the benchmark measures how quickly they stop.
     * Benchmarks pass [collectSpans] false and need only [HighlightBenchOutput.spanCount];
     * viewers pass true to get [HighlightBenchOutput.spans] for rendering.
     */
    fun highlight(
        language: String,
        source: String,
        cancel: HighlightBenchCancelSignal,
        collectSpans: Boolean,
    ): HighlightBenchOutput
}

/** What one engine call produced, in engine-agnostic terms. */
data class HighlightBenchOutput(
    /** The engine's own outcome name, for example "Highlighted" or "Cancelled". */
    val outcome: String,
    val spanCount: Int,
    /** False when the engine fell back or gave up; a cold start without highlighting is a failure. */
    val highlighted: Boolean,
    /** Sorted, non-overlapping spans in UTF-16 offsets; null unless the caller collected them. */
    val spans: List<HighlightBenchSpan>? = null,
)

/** A colored range of the source, [start] inclusive and [end] exclusive, in UTF-16 offsets. */
data class HighlightBenchSpan(
    val start: Int,
    val end: Int,
    /** The engine's semantic role name, for example "Keyword"; colors stay in the app design system. */
    val role: String,
)

/**
 * Cancellation for one engine call. It turns true on its own once the call's deadline passes
 * (no watcher thread needed) or as soon as the caller's [HighlightBenchCanceller] cancels.
 * Safe to read from any thread.
 */
class HighlightBenchCancelSignal internal constructor(
    private val deadline: TimeSource.Monotonic.ValueTimeMark,
    private val canceller: HighlightBenchCanceller,
) {
    val isCancelled: Boolean
        get() = canceller.isCancelled || deadline.hasPassedNow()
}

/**
 * The caller's side of cancellation: [cancel] from any thread turns the cancel signal of the
 * call in progress (and of every later call it is passed to) true at once. A
 * [HighlightBenchRun] owns one for [HighlightBenchRun.stop]; a viewer passes its own to
 * [HighlightBenchProbe.highlight] per call.
 */
class HighlightBenchCanceller {
    @Volatile
    private var cancelledAt: TimeSource.Monotonic.ValueTimeMark? = null

    val isCancelled: Boolean
        get() = cancelledAt != null

    fun cancel() {
        if (cancelledAt == null) cancelledAt = TimeSource.Monotonic.markNow()
    }

    /** Milliseconds since [cancel], or null before it. */
    internal val cancelledForMs: Long?
        get() = cancelledAt?.elapsedNow()?.inWholeMilliseconds
}
