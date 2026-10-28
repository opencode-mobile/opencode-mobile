package dev.opencode.mobile.lab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The engines Session Chat code fences and the File Viewer can use in developer builds (#872). */
enum class HighlightingEngine(
    val storageValue: String,
    val displayName: String,
) {
    TextMate("textmate", "TextMate (default)"),
    HighlightCore("highlight-core", "highlight-core"),
    ;

    companion object {
        fun of(storageValue: String?): HighlightingEngine = entries.firstOrNull { it.storageValue == storageValue } ?: TextMate
    }
}

/**
 * Developer Tools > Highlighting engine, compiled into debug and benchmark builds only. TextMate
 * until changed; highlight-core covers its own languages and leaves the rest to TextMate.
 */
object HighlightingEnginePreferences {
    private const val PREFS_NAME = "opencode_highlighting_engine"
    private const val KEY_ENGINE = "engine"

    var engine: HighlightingEngine by mutableStateOf(HighlightingEngine.TextMate)
        private set

    val usesHighlightCore: Boolean
        get() = engine == HighlightingEngine.HighlightCore

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        engine = HighlightingEngine.of(prefs()?.getString(KEY_ENGINE, null))
    }

    /** True when [value] differs from the current engine and was stored. */
    fun update(value: HighlightingEngine): Boolean {
        if (engine == value) return false
        engine = value
        prefs()?.edit()?.putString(KEY_ENGINE, value.storageValue)?.apply()
        return true
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
