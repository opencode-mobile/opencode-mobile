package dev.opencode.mobile.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Counts content splits currently inside a sidebar split. The count is transient:
 * collapsing the sidebar for a nested split must not be written to preferences.
 */
class OpenCodeSplitNesting {
    var contentSplitCount by mutableIntStateOf(0)
        private set

    val isContentSplitVisible: Boolean
        get() = contentSplitCount > 0

    fun enter() {
        contentSplitCount += 1
    }

    fun leave() {
        contentSplitCount = maxOf(0, contentSplitCount - 1)
    }
}

val LocalOpenCodeSplitNesting = compositionLocalOf<OpenCodeSplitNesting?> { null }
