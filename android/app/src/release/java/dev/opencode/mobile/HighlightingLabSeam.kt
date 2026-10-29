package dev.opencode.mobile

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/**
 * Release half of the Highlighting Lab seam (#872). The Lab is compiled into debug and
 * benchmark builds only (`src/lab`); release builds get these no-ops, so they carry no Lab code.
 */
internal object HighlightingLabSeam {
    val isAvailable: Boolean
        get() = false

    val homeRoute: NavKey? = null

    @Suppress("UNUSED_PARAMETER")
    fun fileRoute(
        path: String,
        source: String,
    ): NavKey? = null

    @Suppress("UNUSED_PARAMETER")
    fun startHeadlessRun(
        activity: ComponentActivity,
        intent: Intent?,
    ): Boolean = false
}

@Suppress("UNUSED_PARAMETER", "UnusedReceiverParameter")
internal fun EntryProviderScope<NavKey>.highlightingLabEntries(
    push: (NavKey) -> Unit,
    navigateBack: () -> Unit,
) = Unit
