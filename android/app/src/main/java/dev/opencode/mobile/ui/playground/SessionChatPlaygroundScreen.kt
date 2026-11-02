package dev.opencode.mobile.ui.playground

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.sessionchat.SessionChatScreen
import dev.opencode.mobile.ui.sessionchat.SessionChatTitleMenu
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Developer Tools → Session Chat Playground: the real Session Chat screen over a
 * [PlaygroundWorld]. The chat title picks the scenario; a floating capsule plays its script.
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
        private val _session = MutableStateFlow(open(PlaygroundScenarioId.entries.first(), fast = false))

        /** The open scenario; a new value is a fresh world and screen model. */
        val session: StateFlow<OpenedPlayground> = _session.asStateFlow()

        /** How many steps each scenario's script has; zero for a still scenario. */
        val stepCounts: Map<PlaygroundScenarioId, Int> =
            PlaygroundScenarioId.entries.associateWith { PlaygroundScenarios.build(it, nowMs()).steps.size }

        /** Opens [id] fresh; a scripted one plays at once, at the speed the last one had. */
        fun select(id: PlaygroundScenarioId) {
            val current = _session.value.session
            val fast = current.player.progress.value.isFast
            current.close()
            _session.value = open(id, fast)
        }

        fun restart() = select(_session.value.session.scenario.id)

        fun selectPrevious() = select(neighbour(-1))

        fun selectNext() = select(neighbour(1))

        private fun neighbour(offset: Int): PlaygroundScenarioId {
            val all = PlaygroundScenarioId.entries
            val index = all.indexOf(_session.value.session.scenario.id)
            return all[(index + offset).mod(all.size)]
        }

        private fun open(
            id: PlaygroundScenarioId,
            fast: Boolean,
        ): OpenedPlayground {
            generation += 1
            val scenario = PlaygroundScenarios.build(id, nowMs())
            val session = PlaygroundSession(scenario, preparing, viewModelScope, nowMs)
            session.player.setFast(fast)
            return OpenedPlayground(generation, session)
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
    var sheetVisible by remember { mutableStateOf(false) }
    var snackbar by remember { mutableStateOf<OpenCodeSnackbarMessage?>(null) }
    // Kept across scenarios, so the capsule stays where it was put.
    val capsule = remember { PlaygroundCapsulePlacement() }

    LaunchedEffect(session) {
        session.world.notices.collect { snackbar = OpenCodeSnackbarMessage(it) }
    }
    val notAvailable: (String) -> Unit = { action -> session.world.notice(action) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (ready) {
            key(opened.generation) {
                val bound = scenario.boundSession
                SessionChatScreen(
                    sessionId = bound.id,
                    sessionTitle = bound.title,
                    worktree = bound.directory ?: PlaygroundContent.WORKTREE,
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
                    titleMenu = SessionChatTitleMenu(scenario.id.title) { sheetVisible = true },
                    viewModel = session.chatViewModel,
                )
            }
        }
        if (scenario.isScripted) {
            PlaygroundPlaybackCapsule(
                code = scenario.id.code,
                progress = progress,
                idleLabel = scenario.hint ?: "Ready",
                placement = capsule,
                onPlayPause = { if (progress.isPlaying) session.player.pause() else session.player.play() },
                onNext = session.player::next,
                onRestart = viewModel::restart,
                onToggleSpeed = { session.player.setFast(!progress.isFast) },
            )
        }
        PlaygroundScenarioSheet(
            visible = sheetVisible,
            current = scenario.id,
            stepCounts = viewModel.stepCounts,
            onSelect = { id ->
                sheetVisible = false
                viewModel.select(id)
            },
            onPrevious = viewModel::selectPrevious,
            onNext = viewModel::selectNext,
            onDismissRequest = { sheetVisible = false },
        )
        OpenCodeSnackbarHost(
            message = snackbar,
            onDismissed = { snackbar = null },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
