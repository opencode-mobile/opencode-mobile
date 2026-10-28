package dev.opencode.mobile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.opencode.mobile.domain.filesystem.ProjectFileHighlightSession
import dev.opencode.mobile.markdown.TextMateHighlightingEngine

/**
 * Release half of the Highlighting engine seam (#872). The developer setting and the
 * highlight-core adapters live in `src/lab` (debug and benchmark); release keeps TextMate.
 */
internal object HighlightingEngineSeam {
    @Suppress("UNUSED_PARAMETER")
    fun initialize(context: Context) = Unit

    fun chatEngine(textMate: TextMateHighlightingEngine): TextMateHighlightingEngine = textMate

    @Suppress("UNUSED_PARAMETER")
    fun fileSession(
        grammar: String,
        source: String,
    ): ProjectFileHighlightSession? = null

    @Suppress("UNUSED_PARAMETER")
    fun settingRow(onChange: () -> Unit): (@Composable (Modifier) -> Unit)? = null
}
