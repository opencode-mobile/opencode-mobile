package dev.opencode.mobile.ui.playground

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.message.DisplayMessagePreparing
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.asDisplayMessagePreparing
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.SessionChatScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Developer Tools → Session Chat Playground: the real Session Chat screen over a
 * [PlaygroundWorld], with a bar to pick a scenario and play, pause or restart its script.
 */
@HiltViewModel
class SessionChatPlaygroundViewModel
    internal constructor(
        private val preparing: DisplayMessagePreparing,
        private val nowMs: () -> Long = { System.currentTimeMillis() },
    ) : ViewModel() {
        @Inject
        constructor(contentPreparer: MessageContentPreparer) : this(contentPreparer.asDisplayMessagePreparing())

        // Nothing the playground does may reach analytics or the real preferences.
        private val isolation = PlaygroundIsolation.begin()
        private var generation = 0
        private val _session = MutableStateFlow(open(PlaygroundScenarioId.Everything))

        /** The open scenario; a new value is a fresh world and screen model. */
        val session: StateFlow<OpenedPlayground> = _session.asStateFlow()

        fun select(id: PlaygroundScenarioId) {
            _session.value.session.close()
            _session.value = open(id)
        }

        fun restart() = select(_session.value.session.scenario.id)

        private fun open(id: PlaygroundScenarioId): OpenedPlayground {
            generation += 1
            val scenario = PlaygroundScenarios.build(id, nowMs())
            return OpenedPlayground(generation, PlaygroundSession(scenario, preparing, viewModelScope, nowMs))
        }

        override fun onCleared() {
            _session.value.session.close()
            isolation.close()
        }
    }

data class OpenedPlayground(
    val generation: Int,
    val session: PlaygroundSession,
)

@Composable
fun SessionChatPlaygroundScreen(
    onNavigateBack: () -> Unit,
    viewModel: SessionChatPlaygroundViewModel = hiltViewModel(),
) {
    val opened by viewModel.session.collectAsStateWithLifecycle()
    val session = opened.session
    val scenario = session.scenario
    val ready by session.ready.collectAsStateWithLifecycle()
    val progress by session.player.progress.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var snackbar by remember { mutableStateOf<OpenCodeSnackbarMessage?>(null) }

    LaunchedEffect(session) {
        session.world.notices.collect { snackbar = OpenCodeSnackbarMessage(it) }
    }
    val notAvailable: (String) -> Unit = { action -> session.world.notice(action) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBarOverflowHost(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                entries =
                    listOf(
                        OpenCodeOverflowMenuEntry(
                            title = "Restart scenario",
                            icon = OpenCodeIcons.Redo,
                            onClick = {
                                menuExpanded = false
                                viewModel.restart()
                            },
                            testTag = "playgroundRestart",
                        ),
                    ) +
                        PlaygroundScenarioId.entries.map { id ->
                            OpenCodeOverflowMenuEntry(
                                title = id.title,
                                subtitle = id.summary,
                                icon = if (id == scenario.id) OpenCodeIcons.Check else null,
                                onClick = {
                                    menuExpanded = false
                                    viewModel.select(id)
                                },
                                testTag = "playgroundScenario.${id.name}",
                            )
                        },
            ) {
                OpenCodeTopBar(
                    title = "Playground",
                    subtitle = scenario.id.title + if (scenario.isScripted) " · ${playbackLabel(progress)}" else "",
                    leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
                    actions =
                        listOfNotNull(
                            playbackAction(progress, scenario.isScripted, session.player, viewModel::restart),
                            OpenCodeTopBarAction.Icon(
                                imageVector = OpenCodeIcons.MoreVert,
                                contentDescription = "Scenarios",
                                onClick = { menuExpanded = true },
                            ),
                        ),
                    showDivider = true,
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (ready) {
                    key(opened.generation) {
                        val bound = scenario.boundSession
                        SessionChatScreen(
                            sessionId = bound.id,
                            sessionTitle = bound.title,
                            worktree = PlaygroundContent.WORKTREE,
                            parentId = bound.parentId,
                            onNavigateBack = onNavigateBack,
                            onSessionContextClick = { notAvailable("Session context") },
                            onReviewChangesClick = { notAvailable("Changes") },
                            onChildSessionClick = { notAvailable("Opening a subagent") },
                            onToolGroupClick = { notAvailable("Tool groups") },
                            onSessionReplaceRequested = { notAvailable("Switching sessions") },
                            onTerminalRequested = { _, _ -> notAvailable("Terminal") },
                            onFilesRequested = { notAvailable("Files") },
                            onReportProblem = { notAvailable("Report a problem") },
                            viewModel = session.chatViewModel,
                        )
                    }
                }
            }
        }
        OpenCodeSnackbarHost(
            message = snackbar,
            onDismissed = { snackbar = null },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun playbackLabel(progress: PlaygroundPlayer.Progress): String =
    when {
        progress.isFinished -> "finished"
        progress.isPlaying -> "step ${progress.nextStep + 1} of ${progress.stepCount}"
        else -> "paused"
    }

private fun playbackAction(
    progress: PlaygroundPlayer.Progress,
    scripted: Boolean,
    player: PlaygroundPlayer,
    restart: () -> Unit,
): OpenCodeTopBarAction? {
    if (!scripted) return null
    return when {
        progress.isFinished -> OpenCodeTopBarAction.Label(title = "Restart", onClick = restart)
        progress.isPlaying -> OpenCodeTopBarAction.Label(title = "Pause", onClick = player::pause)
        else -> OpenCodeTopBarAction.Label(title = "Play", onClick = player::play)
    }
}
