package dev.opencode.mobile

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.opencode.mobile.lab.HighlightingLabHeadless
import dev.opencode.mobile.lab.LabDataSetStore
import dev.opencode.mobile.lab.LabHome
import dev.opencode.mobile.lab.LabViewer
import dev.opencode.mobile.lab.labEntries

/**
 * Lab half of the Highlighting Lab seam (#872), compiled into debug and benchmark builds. Main
 * code reaches the Lab only through this object and [highlightingLabEntries]; release builds get
 * no-ops with the same names.
 */
internal object HighlightingLabSeam {
    val isAvailable: Boolean
        get() = true

    val homeRoute: NavKey? = LabHome

    /** Keeps [source] as a custom data set for this process and returns the viewer route for it. */
    fun fileRoute(
        path: String,
        source: String,
    ): NavKey? = LabViewer(LabDataSetStore.shared.addFile(path, source).id)

    /** Starts a headless run when [intent] carries `labRun`; true means the activity is taken over. */
    fun startHeadlessRun(
        activity: ComponentActivity,
        intent: Intent?,
    ): Boolean = HighlightingLabHeadless.start(activity, intent)
}

internal fun EntryProviderScope<NavKey>.highlightingLabEntries(
    push: (NavKey) -> Unit,
    navigateBack: () -> Unit,
) = labEntries(push, navigateBack)
