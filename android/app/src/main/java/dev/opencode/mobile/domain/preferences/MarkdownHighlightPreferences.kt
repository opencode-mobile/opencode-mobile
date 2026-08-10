package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencode.mobile.markdown.TextMateLanguageGateRegistry
import dev.opencode.mobile.markdown.TextMateLanguageOverrideGate

/**
 * Controls whether Session Chat code fences use TextMate (expensive) or the
 * semantic-fallback highlighter. Enabled by default for all packaged languages
 * when the preference key is unset; an explicit user off stays off.
 *
 * Developer Tools language overrides: opt out of any packaged language (disabled
 * set). Force-enable remains for legacy unpromoted overrides.
 *
 * Callers that change [textMateEnabled] or language overrides should also clear
 * prepared-markdown caches so settle-reuse cannot keep the old highlighter's
 * documents.
 */
object MarkdownHighlightPreferences {
    private const val PREFS_NAME = "opencode_markdown_highlight"
    private const val KEY_TEXT_MATE_ENABLED = "text_mate_enabled"
    private const val KEY_DISABLED_LANGUAGES = "text_mate_disabled_languages"
    private const val KEY_FORCE_ENABLED_LANGUAGES = "text_mate_force_enabled_languages"

    var textMateEnabled: Boolean by mutableStateOf(true)
        private set

    var disabledLanguages: Set<String> by mutableStateOf(emptySet())
        private set

    var forceEnabledLanguages: Set<String> by mutableStateOf(emptySet())
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
        disabledLanguages = prefs.getStringSet(KEY_DISABLED_LANGUAGES, emptySet())?.toSet().orEmpty()
        forceEnabledLanguages =
            prefs.getStringSet(KEY_FORCE_ENABLED_LANGUAGES, emptySet())?.toSet().orEmpty()
        publishGate()
    }

    fun updateTextMateEnabled(value: Boolean) {
        if (textMateEnabled == value) return
        textMateEnabled = value
        prefs()?.edit()?.putBoolean(KEY_TEXT_MATE_ENABLED, value)?.apply()
        epoch += 1
    }

    fun isLanguageEnabled(canonicalLanguage: String): Boolean {
        val canonical = canonicalLanguage.trim().lowercase()
        if (canonical.isEmpty()) return false
        // Allowlist v2: every packaged/named language defaults on unless opted out.
        if (canonical in forceEnabledLanguages) return true
        return canonical !in disabledLanguages
    }

    fun setLanguageEnabled(
        canonicalLanguage: String,
        enabled: Boolean,
    ) {
        val canonical = canonicalLanguage.trim().lowercase()
        if (canonical.isEmpty()) return
        val nextDisabled =
            if (enabled) {
                disabledLanguages - canonical
            } else {
                disabledLanguages + canonical
            }
        // Drop legacy force-enable once the language is back on the default path.
        val nextForced = forceEnabledLanguages - canonical
        if (nextDisabled == disabledLanguages && nextForced == forceEnabledLanguages) return
        disabledLanguages = nextDisabled
        forceEnabledLanguages = nextForced
        persistLanguageOverrides()
        publishGate()
        epoch += 1
    }

    /** Restores all-packaged-on defaults without changing TextMate on/off. */
    fun resetLanguageOverrides() {
        val changed = disabledLanguages.isNotEmpty() || forceEnabledLanguages.isNotEmpty()
        if (!changed) return
        disabledLanguages = emptySet()
        forceEnabledLanguages = emptySet()
        persistLanguageOverrides()
        publishGate()
        epoch += 1
    }

    fun clear() {
        prefs()
            ?.edit()
            ?.remove(KEY_TEXT_MATE_ENABLED)
            ?.remove(KEY_DISABLED_LANGUAGES)
            ?.remove(KEY_FORCE_ENABLED_LANGUAGES)
            ?.apply()
        val changed =
            !textMateEnabled ||
                disabledLanguages.isNotEmpty() ||
                forceEnabledLanguages.isNotEmpty() ||
                epoch != 0
        textMateEnabled = true
        disabledLanguages = emptySet()
        forceEnabledLanguages = emptySet()
        publishGate()
        if (changed) {
            epoch += 1
        }
    }

    fun publishGate() {
        TextMateLanguageGateRegistry.update(
            TextMateLanguageOverrideGate(
                disabledCanonicalLanguages = disabledLanguages,
                forceEnabledCanonicalLanguages = forceEnabledLanguages,
            ),
        )
    }

    private fun persistLanguageOverrides() {
        prefs()
            ?.edit()
            ?.putStringSet(KEY_DISABLED_LANGUAGES, disabledLanguages)
            ?.putStringSet(KEY_FORCE_ENABLED_LANGUAGES, forceEnabledLanguages)
            ?.apply()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
