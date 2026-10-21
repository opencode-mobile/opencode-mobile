package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeStepRail
import dev.opencode.mobile.ui.components.OpenCodeStepRailItem
import dev.opencode.mobile.ui.components.OpenCodeStepRailSamples
import dev.opencode.mobile.ui.components.OpenCodeStepRailStatus

@Composable
fun StepRailGalleryScreen(modifier: Modifier = Modifier) {
    val nowMs = remember { System.currentTimeMillis() }
    var unfolded by rememberSaveable { mutableStateOf(false) }
    val foldedSteps =
        if (unfolded) {
            List(6) { index ->
                OpenCodeStepRailItem.Step(
                    id = "earlier-$index",
                    title = if (index == 2) "Ran npm run lint" else "File$index.kt",
                    detail = if (index == 2) "Exited with code 2" else null,
                    status = if (index == 2) OpenCodeStepRailStatus.Failed else OpenCodeStepRailStatus.Completed,
                )
            } + OpenCodeStepRailSamples.foldedWithFailure.drop(1)
        } else {
            OpenCodeStepRailSamples.foldedWithFailure
        }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        GallerySample("Live, one running step") {
            OpenCodeStepRail(
                steps = OpenCodeStepRailSamples.liveOneRunning(nowMs),
                isLive = true,
                thinking = false,
                onStepTap = {},
                onFoldTap = {},
                onChildSessionTap = {},
            )
        }
        GallerySample("Live, parallel steps and a child session") {
            OpenCodeStepRail(
                steps = OpenCodeStepRailSamples.liveParallelWithChild(nowMs),
                isLive = true,
                thinking = false,
                onStepTap = {},
                onFoldTap = {},
                onChildSessionTap = {},
            )
        }
        GallerySample("Folded with a failure, thinking (tap the fold row)") {
            OpenCodeStepRail(
                steps = foldedSteps,
                isLive = true,
                thinking = true,
                onStepTap = {},
                onFoldTap = { unfolded = !unfolded },
                onChildSessionTap = {},
            )
        }
        GallerySample("Finished") {
            OpenCodeStepRail(
                steps = OpenCodeStepRailSamples.finished,
                isLive = false,
                thinking = false,
                onStepTap = {},
                onFoldTap = {},
                onChildSessionTap = {},
            )
        }
    }
}

@Composable
private fun GallerySample(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}
