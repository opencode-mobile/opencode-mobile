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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeActivityCard
import dev.opencode.mobile.ui.components.OpenCodeActivityCardSamples
import dev.opencode.mobile.ui.components.OpenCodeActivityCardState
import dev.opencode.mobile.ui.components.OpenCodeActivitySheet
import dev.opencode.mobile.ui.components.OpenCodeActivitySheetSamples
import dev.opencode.mobile.ui.components.OpenCodeActivityStatus

@Composable
fun ActivityCardGalleryScreen(modifier: Modifier = Modifier) {
    val nowMs = remember { System.currentTimeMillis() }
    var sheetLive by remember { mutableStateOf<Boolean?>(null) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        GallerySample("Live, one running tool (tap the band for the sheet)") {
            OpenCodeActivityCard(
                state = OpenCodeActivityCardState.Live(elapsedStartMs = nowMs - 12_000),
                summary = "1 step · 1 command",
                agents = emptyList(),
                current = OpenCodeActivityCardSamples.oneTool(nowMs),
                idleText = null,
                onAgentTap = {},
                onOpen = { sheetLive = true },
            )
        }
        GallerySample("Live, two agents and +1") {
            OpenCodeActivityCard(
                state = OpenCodeActivityCardState.Live(elapsedStartMs = nowMs - 64_000),
                summary = OpenCodeActivityCardSamples.LIVE_SUMMARY,
                agents = OpenCodeActivityCardSamples.twoAgents(nowMs),
                current = OpenCodeActivityCardSamples.editPlusOne(nowMs),
                idleText = null,
                onAgentTap = {},
                onOpen = { sheetLive = true },
            )
        }
        GallerySample("Live, last tool just finished") {
            OpenCodeActivityCard(
                state = OpenCodeActivityCardState.Live(elapsedStartMs = nowMs - 9_000),
                summary = "3 steps · 2 reads · 1 search",
                agents = emptyList(),
                current = OpenCodeActivityCardSamples.justFinished,
                idleText = null,
                onAgentTap = {},
                onOpen = { sheetLive = true },
            )
        }
        GallerySample("Live, waiting for agents") {
            OpenCodeActivityCard(
                state = OpenCodeActivityCardState.Live(elapsedStartMs = nowMs - 40_000),
                summary = "4 steps · 2 reads · 2 agents",
                agents = OpenCodeActivityCardSamples.twoAgents(nowMs),
                current = null,
                idleText = "Waiting for agents",
                onAgentTap = {},
                onOpen = { sheetLive = true },
            )
        }
        GallerySample("Settled, completed (tap for the settled sheet)") {
            OpenCodeActivityCard(
                state = OpenCodeActivityCardState.Settled(worstStatus = OpenCodeActivityStatus.Completed, stepCount = 6),
                summary = OpenCodeActivityCardSamples.SETTLED_NAMES,
                agents = emptyList(),
                current = null,
                idleText = null,
                onAgentTap = {},
                onOpen = { sheetLive = false },
            )
        }
        GallerySample("Settled with a failure") {
            OpenCodeActivityCard(
                state = OpenCodeActivityCardState.Settled(worstStatus = OpenCodeActivityStatus.Failed, stepCount = 7),
                summary = OpenCodeActivityCardSamples.SETTLED_FAILED_NAMES,
                agents = emptyList(),
                current = null,
                idleText = null,
                onAgentTap = {},
                onOpen = { sheetLive = false },
            )
        }
        GallerySample("Settled with agents") {
            OpenCodeActivityCard(
                state = OpenCodeActivityCardState.Settled(worstStatus = OpenCodeActivityStatus.Completed, stepCount = 4),
                summary = OpenCodeActivityCardSamples.SETTLED_AGENT_NAMES,
                agents = OpenCodeActivityCardSamples.settledAgents,
                current = null,
                idleText = null,
                onAgentTap = {},
                onOpen = { sheetLive = false },
            )
        }
    }
    OpenCodeActivitySheet(
        visible = sheetLive != null,
        live = sheetLive == true,
        stepCount = 6,
        startedAtMs = nowMs - 64_000,
        agents = OpenCodeActivitySheetSamples.agents(nowMs),
        steps = OpenCodeActivitySheetSamples.steps(nowMs),
        onAgentTap = {},
        onStepTap = {},
        onDismissRequest = { sheetLive = null },
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
