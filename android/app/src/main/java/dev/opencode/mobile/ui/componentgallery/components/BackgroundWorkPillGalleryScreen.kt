package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import dev.opencode.mobile.domain.shell.AgentShell
import dev.opencode.mobile.domain.shell.AgentShellStatus
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.sessionchat.SessionChatBackgroundWork
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagent
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus
import dev.opencode.mobile.ui.sessionchat.view.SessionChatBackgroundWorkPill
import dev.opencode.mobile.ui.sessionchat.view.SessionChatBackgroundWorkList

@Composable
fun BackgroundWorkPillGalleryScreen(modifier: Modifier = Modifier) {
    var needsInput by rememberSaveable { mutableStateOf(false) }
    var compact by rememberSaveable { mutableStateOf(false) }
    var showSubagents by rememberSaveable { mutableStateOf(true) }
    var showShells by rememberSaveable { mutableStateOf(true) }
    var log by remember { mutableStateOf("Tap the pill or a row") }
    // Fixed so the running timers tick instead of restarting on every recomposition.
    val openedAtMs = remember { System.currentTimeMillis() }
    val subagents = remember(needsInput, showSubagents) { if (showSubagents) gallerySubagents(needsInput, openedAtMs) else emptyList() }
    val shells = remember(showShells) { if (showShells) galleryShells(openedAtMs) else emptyList() }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text =
                "Session Chat background work: a live counter of running subagents and shells. When a subagent waits on a " +
                    "permission or question the pill turns amber with a pulsing dot. Tapping it opens the background work panel.",
            style = MaterialTheme.typography.bodyMedium,
            color = openCodeColor("text-base"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Needs input", selected = needsInput, onClick = { needsInput = !needsInput })
            OpenCodeChoiceBadge(label = "Compact", selected = compact, onClick = { compact = !compact })
            OpenCodeChoiceBadge(label = "Subagents", selected = showSubagents, onClick = { showSubagents = !showSubagents })
            OpenCodeChoiceBadge(label = "Shells", selected = showShells, onClick = { showShells = !showShells })
        }
        SessionChatBackgroundWork.pill(subagents, shells)?.let { pill ->
            SessionChatBackgroundWorkPill(pill = pill, compact = compact, onClick = { log = "Opened background work" })
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Background work panel",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SessionChatBackgroundWorkList(
                subagents = subagents,
                shells = shells,
                onOpenSubagent = { log = "Opened ${it.agentName} · ${it.title.orEmpty()}" },
                onOpenShell = { log = "Opened shell · ${it.command}" },
            )
        }
        Text(
            text = log,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-weak"),
        )
    }
}

private fun gallerySubagents(
    needsInput: Boolean,
    nowMs: Long,
): List<SessionChatSubagent> {
    fun row(
        id: String,
        agent: String,
        title: String,
        status: SessionChatSubagentStatus,
        startedAgoMs: Long,
        finishedAgoMs: Long? = null,
    ) = SessionChatSubagent(
        id = id,
        childSessionId = "ses-$id",
        agentName = agent,
        title = title,
        status = status,
        startedAtMs = nowMs - startedAgoMs,
        finishedAtMs = finishedAgoMs?.let { nowMs - it },
    )
    val general =
        row(
            "prt-1",
            "general",
            "Run the test suite",
            if (needsInput) SessionChatSubagentStatus.WaitingForInput else SessionChatSubagentStatus.Running,
            startedAgoMs = 95_000,
        )
    val running =
        listOf(
            row("prt-2", "explore", "Find the session store", SessionChatSubagentStatus.Running, startedAgoMs = 42_000),
            row("prt-3", "explore", "Map the settings screens", SessionChatSubagentStatus.Running, startedAgoMs = 18_000),
        )
    val finished =
        listOf(
            row("prt-4", "review", "Review the diff", SessionChatSubagentStatus.Done, 300_000, finishedAgoMs = 220_000),
            row("prt-5", "general", "Migrate the fixtures", SessionChatSubagentStatus.Failed, 400_000, finishedAgoMs = 380_000),
            row("prt-6", "explore", "Profile the parser", SessionChatSubagentStatus.Stopped, 500_000, finishedAgoMs = 470_000),
        )
    return if (needsInput) listOf(general) + running + finished else running + general + finished
}

private fun galleryShells(nowMs: Long): List<AgentShell> {
    fun shell(
        id: String,
        command: String,
        description: String?,
        startedAgoMs: Long,
    ) = AgentShell(
        id = id,
        sessionId = "ses-gallery",
        command = command,
        description = description,
        status = AgentShellStatus.Running,
        startedAtMs = nowMs - startedAgoMs,
        finishedAtMs = null,
        canStop = true,
    )
    return listOf(
        shell("sh-1", "npm run dev", "Start the dev server", startedAgoMs = 310_000),
        shell("sh-2", "./gradlew :app:testDebugUnitTest --tests 'dev.example.*'", null, startedAgoMs = 72_000),
    )
}
