package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.markdown.CodeBlockKey
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.HighlightIntent
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightRevision
import dev.opencode.mobile.markdown.OpenCodeSemanticFallbackHighlighter
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** Syntax spans for a File Viewer text file; offsets index into the given source. */
interface ProjectFileHighlighter {
    suspend fun highlight(
        path: String,
        grammar: String,
        source: String,
    ): List<CodeSpan>
}

/**
 * The shared TextMate engine (same one Session Chat code fences use), or the semantic fallback
 * when the user turned TextMate off.
 */
class TextMateProjectFileHighlighter
    @Inject
    constructor(
        private val engine: TextMateHighlightingEngine,
    ) : ProjectFileHighlighter {
        private val revisions = AtomicLong(0)

        override suspend fun highlight(
            path: String,
            grammar: String,
            source: String,
        ): List<CodeSpan> {
            if (!MarkdownHighlightPreferences.textMateEnabled) {
                return withContext(Dispatchers.Default) {
                    OpenCodeSemanticFallbackHighlighter.highlight(grammar, source).spans
                }
            }
            val result =
                engine.highlight(
                    HighlightRequest(
                        key = CodeBlockKey("files:${path.length}:$path"),
                        revision = HighlightRevision(revisions.incrementAndGet()),
                        language = grammar,
                        source = source,
                        intent = HighlightIntent.InteractiveFinal,
                        policyEpoch = MarkdownHighlightPreferences.epoch.toLong(),
                    ),
                )
            return result.spans
        }
    }
