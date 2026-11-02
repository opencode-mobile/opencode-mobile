package dev.opencode.mobile.platform

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * Process-wide switches that keep Compose Foundation bugs out of release builds.
 * Apply once from `Application.onCreate`, before any composition.
 */
object ComposeFoundationWorkarounds {
    @OptIn(ExperimentalFoundationApi::class)
    fun apply() {
        // Foundation 1.12 lets a mouse press in a SelectionContainer's empty space
        // start a selection. A press below the last Text crashes in
        // MultiSelectionLayout.crossStatus (#942). Fixed in 1.13.0-beta02; remove
        // this once the BOM ships Foundation 1.13 stable.
        ComposeFoundationFlags.isMouseSelectionBetweenTextEnabled = false
    }
}
