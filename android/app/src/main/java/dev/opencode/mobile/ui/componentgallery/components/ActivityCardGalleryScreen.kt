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
import dev.opencode.mobile.ui.components.OpenCodeActivityCard
import dev.opencode.mobile.ui.components.OpenCodeActivityCardSamples
import dev.opencode.mobile.ui.components.OpenCodeActivityCardState
import dev.opencode.mobile.ui.components.OpenCodeActivityLine
import dev.opencode.mobile.ui.components.OpenCodeActivityStatus

@Composable
fun ActivityCardGalleryScreen(modifier: Modifier = Modifier) {
    val nowMs = remember { System.currentTimeMillis() }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        GallerySample("Live, one running step") {
            GalleryCard(
                state = OpenCodeActivityCardState.Live,
                startedAtMs = nowMs - 12_000,
                running = OpenCodeActivityCardSamples.oneRunning(nowMs),
                done = emptyList(),
                summary = "nothing finished yet",
            )
        }
        GallerySample("Live, three running and a child session, collapsed") {
            GalleryCard(
                state = OpenCodeActivityCardState.Live,
                startedAtMs = nowMs - 64_000,
                running = OpenCodeActivityCardSamples.threeRunningWithChild(nowMs),
                done = OpenCodeActivityCardSamples.someDone,
                summary = OpenCodeActivityCardSamples.SOME_DONE_SUMMARY,
            )
        }
        GallerySample("Live, three running and a child session, expanded") {
            GalleryCard(
                state = OpenCodeActivityCardState.Live,
                startedAtMs = nowMs - 64_000,
                running = OpenCodeActivityCardSamples.threeRunningWithChild(nowMs),
                done = OpenCodeActivityCardSamples.someDone,
                summary = OpenCodeActivityCardSamples.SOME_DONE_SUMMARY,
                startExpanded = true,
            )
        }
        GallerySample("Settled with a failure, collapsed (tap to expand)") {
            GalleryCard(
                state = OpenCodeActivityCardState.Settled,
                running = emptyList(),
                done = OpenCodeActivityCardSamples.settledWithFailure,
                summary = OpenCodeActivityCardSamples.SETTLED_SUMMARY,
                worstStatus = OpenCodeActivityStatus.Failed,
            )
        }
        GallerySample("Settled with a failure, expanded") {
            GalleryCard(
                state = OpenCodeActivityCardState.Settled,
                running = emptyList(),
                done = OpenCodeActivityCardSamples.settledWithFailure,
                summary = OpenCodeActivityCardSamples.SETTLED_SUMMARY,
                worstStatus = OpenCodeActivityStatus.Failed,
                startExpanded = true,
            )
        }
        GallerySample("Live, thinking") {
            GalleryCard(
                state = OpenCodeActivityCardState.Live,
                startedAtMs = nowMs - 20_000,
                running = emptyList(),
                done = OpenCodeActivityCardSamples.someDone,
                summary = OpenCodeActivityCardSamples.SOME_DONE_SUMMARY,
                thinking = true,
            )
        }
    }
}

@Composable
private fun GalleryCard(
    state: OpenCodeActivityCardState,
    running: List<OpenCodeActivityLine>,
    done: List<OpenCodeActivityLine>,
    summary: String,
    startedAtMs: Long? = null,
    thinking: Boolean = false,
    worstStatus: OpenCodeActivityStatus = OpenCodeActivityStatus.Completed,
    startExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    OpenCodeActivityCard(
        state = state,
        startedAtMs = startedAtMs,
        endedAtMs = null,
        thinking = thinking,
        runningLines = running,
        doneLines = done,
        summary = summary,
        worstStatus = worstStatus,
        isExpanded = expanded,
        onToggleExpanded = { expanded = !expanded },
        onLineTap = {},
        onChildSessionTap = {},
    )
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
