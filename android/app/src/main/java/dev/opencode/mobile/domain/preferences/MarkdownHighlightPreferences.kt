package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Controls whether Session Chat code fences use TextMate (expensive) or the
 * semantic-fallback highlighter. Enabled by default for allowlisted languages
 * when the preference key is unset; an explicit user off stays off.
 *
 * Callers that change [textMateEnabled] should also clear prepared-markdown
 * caches (preparer LRU + block decoration) so settle-reuse cannot keep the old
 * highlighter's documents.
 */
object MarkdownHighlightPreferences {
    private const val PREFS_NAME = "opencode_markdown_highlight"
    private const val KEY_TEXT_MATE_ENABLED = "text_mate_enabled"

    var textMateEnabled: Boolean by mutableStateOf(true)
        private set

    /** Bumped whenever highlighting policy changes so prepared docs cannot settle-reuse. */
    var epoch: Int by mutableIntStateOf(0)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        textMateEnabled =
            if (prefs.contains(KEY_TEXT_MATE_ENABLED)) {
                prefs.getBoolean(KEY_TEXT_MATE_ENABLED, true)
            } else {
                true
            }
    }

    fun updateTextMateEnabled(value: Boolean) {
        if (textMateEnabled == value) return
        textMateEnabled = value
        prefs()?.edit()?.putBoolean(KEY_TEXT_MATE_ENABLED, value)?.apply()
        epoch += 1
    }

    fun clear() {
        prefs()?.edit()?.remove(KEY_TEXT_MATE_ENABLED)?.apply()
        if (textMateEnabled && epoch == 0) return
        textMateEnabled = true
        epoch += 1
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
