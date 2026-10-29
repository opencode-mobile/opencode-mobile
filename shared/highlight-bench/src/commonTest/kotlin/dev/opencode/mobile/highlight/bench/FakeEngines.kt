package dev.opencode.mobile.highlight.bench

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Runs isolated calls on a platform thread, like the Lab does. */
internal expect fun platformThreadGuard(): HighlightBenchCallGuard

/** Runs [block] on a new platform thread, like a viewer's Cancel button on the main thread. */
internal expect fun onOtherThread(block: () -> Unit)

internal class FakeEngine(
    override val id: String = "fake",
    private val languages: Set<String>? = null,
    private val behavior: (language: String, source: String, cancel: HighlightBenchCancelSignal) -> HighlightBenchOutput,
) : HighlightBenchEngine {
    var instances = 0
        private set
    var calls = 0
        private set

    override val displayName: String = id.replaceFirstChar { it.uppercase() }

    override fun supportsLanguage(language: String): Boolean = languages == null || language in languages

    override fun newHighlighter(): HighlightBenchHighlighter {
        instances += 1
        return HighlightBenchHighlighter { language, source, cancel, collectSpans ->
            calls += 1
            val output = behavior(language, source, cancel)
            if (collectSpans) output.copy(spans = List(output.spanCount) { HighlightBenchSpan(it * 10, it * 10 + 5, "Keyword") }) else output
        }
    }
}

internal class Latch {
    @Volatile
    var isOpen: Boolean = false
}

internal fun highlighted(source: String) = HighlightBenchOutput("Highlighted", source.length / 10, highlighted = true)

internal val fast = FakeEngine { _, source, _ -> highlighted(source) }

/** Busy-waits until [cancel] fires, like an engine that checks cancellation every line. */
internal fun honorsCancel() =
    FakeEngine { _, source, cancel ->
        if (source.isMarker()) {
            while (!cancel.isCancelled) Unit
            HighlightBenchOutput("Cancelled", 0, highlighted = false)
        } else {
            highlighted(source)
        }
    }

/** Keeps going for [duration] whatever its cancel signal says. */
internal fun ignoresCancel(duration: Duration) =
    FakeEngine { _, source, _ ->
        if (source.isMarker()) spin(duration)
        highlighted(source)
    }

internal fun throws() =
    FakeEngine { _, source, _ ->
        if (source.isMarker()) error("boom")
        highlighted(source)
    }

/** Ignores cancellation until [release] opens. */
internal fun hangsUntil(release: Latch) =
    FakeEngine { _, source, _ ->
        if (source.isMarker()) {
            while (!release.isOpen) Unit
        }
        highlighted(source)
    }

internal const val MARKER = "<<measured>>"

/** Only the measured custom input misbehaves; warm-up calls are fast. */
private fun String.isMarker() = startsWith(MARKER)

internal fun spin(duration: Duration) {
    val startedAt = TimeSource.Monotonic.markNow()
    while (startedAt.elapsedNow() < duration) Unit
}

internal fun settings(
    limitMs: Long = 30,
    graceMs: Long = 200,
    policy: HighlightBenchStuckPolicy = HighlightBenchStuckPolicy.SkipIsolatedCases,
) = HighlightBenchSettings(callLimitMs = limitMs, cancelGraceMs = graceMs, whileCallStuck = policy)

internal fun custom(name: String = "input") =
    HighlightBenchCase.measuring(HighlightBenchDataSet.custom(name, name, "typescript", "$MARKER $name\nconst a = 1;\n"))

internal fun plain(name: String) = HighlightBenchCase.measuring(HighlightBenchDataSet.custom(name, name, "kotlin", "val a = 1\n"))
