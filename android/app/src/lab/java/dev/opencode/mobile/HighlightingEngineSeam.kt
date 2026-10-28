package dev.opencode.mobile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.opencode.mobile.domain.filesystem.ProjectFileHighlightSession
import dev.opencode.mobile.lab.HighlightCoreFileSession
import dev.opencode.mobile.lab.HighlightingEnginePreferences
import dev.opencode.mobile.lab.HighlightingEngineSettingRow
import dev.opencode.mobile.lab.HighlightingEngineSwitch
import dev.opencode.mobile.markdown.TextMateHighlightingEngine

/**
 * Developer half of the Highlighting engine seam (#872), compiled into debug and benchmark
 * builds with the Lab. Release builds get no-ops with the same names: TextMate everywhere, no
 * setting, and no highlight-core use in the app.
 */
internal object HighlightingEngineSeam {
    fun initialize(context: Context) {
        HighlightingEnginePreferences.initialize(context)
    }

    /** Session Chat's engine: [textMate], or highlight-core while the setting picks it. */
    fun chatEngine(textMate: TextMateHighlightingEngine): TextMateHighlightingEngine = HighlightingEngineSwitch(textMate)

    /** A progressive File Viewer session while highlight-core is picked and knows [grammar]; null keeps TextMate. */
    fun fileSession(
        grammar: String,
        source: String,
    ): ProjectFileHighlightSession? =
        if (HighlightingEnginePreferences.usesHighlightCore) HighlightCoreFileSession.open(grammar, source) else null

    /** The Developer Tools row; [onChange] runs after the engine changes. */
    fun settingRow(onChange: () -> Unit): (@Composable (Modifier) -> Unit)? =
        { modifier -> HighlightingEngineSettingRow(onChange = onChange, modifier = modifier) }
}
