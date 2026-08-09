package dev.opencode.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * OPE-124 screen-view effects. Route destinations report on every resume so a
 * backgrounded app that returns to the same surface still produces a view.
 */
@Composable
fun ProductRouteScreenViewEffect(
    screenId: String,
    presentation: String = ProductAnalytics.Presentation.ROUTE,
) {
    var hasReported by remember(screenId) { mutableStateOf(false) }
    LifecycleResumeEffect(screenId, presentation) {
        ProductAnalytics.logScreenView(
            screenId = screenId,
            presentation = presentation,
            trigger =
                if (hasReported) {
                    ProductAnalytics.Trigger.RESUME
                } else {
                    ProductAnalytics.Trigger.PUSH
                },
        )
        hasReported = true
        onPauseOrDispose {}
    }
}

/** Sheets, dialogs, menus, and embedded home states report once per appearance. */
@Composable
fun ProductPresentedScreenViewEffect(
    screenId: String,
    presentation: String,
    visible: Boolean,
) {
    LaunchedEffect(screenId, presentation, visible) {
        if (!visible) return@LaunchedEffect
        ProductAnalytics.logScreenView(
            screenId = screenId,
            presentation = presentation,
            trigger = ProductAnalytics.Trigger.PRESENT,
        )
    }
}
