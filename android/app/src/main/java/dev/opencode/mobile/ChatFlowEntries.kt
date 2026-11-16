package dev.opencode.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.opencode.mobile.ui.changes.ChangeSource
import dev.opencode.mobile.ui.changes.ChangesScreen
import dev.opencode.mobile.ui.changes.TurnDiffUnavailable
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.files.FilesScreen
import dev.opencode.mobile.ui.main.newSessionOpenTarget
import dev.opencode.mobile.ui.sessionchat.SessionChatScreen
import dev.opencode.mobile.ui.sessionchat.SessionToolGroupMembersState
import dev.opencode.mobile.ui.sessionchat.SessionToolGroupViewModel
import dev.opencode.mobile.ui.sessionchat.view.LocalSessionChatToolDetailResolver
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailResolver
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolGroupScreen
import dev.opencode.mobile.ui.sessionchat.view.toTarget
import dev.opencode.mobile.ui.sessioncontext.SessionContextScreen
import dev.opencode.mobile.ui.terminal.TerminalScreen

/**
 * Back stack operations behind the Session Chat flow, so the root stack (compact width) and the
 * Home split pane (wide windows) render the same [chatFlowEntries].
 */
internal class ChatFlowNavigator(
    val push: (NavKey) -> Unit,
    val navigateBack: () -> Unit,
    val replaceSession: (SessionChat) -> Unit,
    /** Opens another session the way Home opens one: pushed when stacked, selected in the pane. */
    val openSession: (SessionChat) -> Unit,
)

internal fun MutableList<NavKey>.rootChatFlowNavigator(): ChatFlowNavigator =
    ChatFlowNavigator(
        push = { route -> add(route) },
        navigateBack = { popRouteUnlessRoot() },
        replaceSession = { session ->
            popRouteUnlessRoot()
            add(session)
        },
        openSession = { session -> add(session) },
    )

internal fun MutableList<NavKey>.paneChatFlowNavigator(): ChatFlowNavigator =
    ChatFlowNavigator(
        push = { route -> add(route) },
        navigateBack = { navigateBackInPane() },
        replaceSession = { session -> replacePaneSession(session) },
        openSession = { session -> selectPaneSession(session) },
    )

/**
 * Session Chat and the screens it opens. [sessionChatDetailControl] gives a chat the split
 * pane's sidebar toggle in place of Back; it is read during composition, so it may read state.
 */
internal fun EntryProviderScope<NavKey>.chatFlowEntries(
    navigator: ChatFlowNavigator,
    sessionChatDetailControl: (SessionChat) -> OpenCodeSplitPaneDetailControl? = { null },
) {
    entry<Terminal> { key ->
        RouteSurface {
            ProductRouteScreenViewEffect(
                screenId = ProductAnalytics.Screen.TERMINAL,
            )
            TerminalScreen(
                worktree = key.worktree,
                projectTitle = key.projectTitle,
                ptyId = key.ptyId,
                onNavigateBack = navigator.navigateBack,
            )
        }
    }
    entry<SessionChat> { key ->
        val detailControl = sessionChatDetailControl(key)
        RouteSurface(edgeToEdgeBottom = true) {
            ProductRouteScreenViewEffect(
                screenId = ProductAnalytics.Screen.SESSION_CHAT,
                presentation =
                    if (detailControl != null) {
                        ProductAnalytics.Presentation.EMBEDDED
                    } else {
                        ProductAnalytics.Presentation.ROUTE
                    },
            )
            SessionChatScreen(
                sessionId = key.sessionId,
                sessionTitle = key.sessionTitle,
                worktree = key.worktree,
                optimisticNavigationInstanceId = key.optimisticNavigationInstanceId,
                parentId = key.parentId,
                detailControl = detailControl,
                onNavigateBack = navigator.navigateBack,
                onNewSessionRequested = { worktree ->
                    val target = newSessionOpenTarget(worktree)
                    navigator.openSession(
                        SessionChat(
                            sessionId = target.sessionId,
                            sessionTitle = target.title,
                            worktree = target.worktree,
                            optimisticNavigationInstanceId = target.optimisticNavigationInstanceId,
                        ),
                    )
                },
                onSessionContextClick = {
                    navigator.push(SessionContext(key.sessionId, key.worktree))
                },
                onReviewChangesClick = { target ->
                    navigator.push(
                        Changes(
                            target.sessionId,
                            target.worktree,
                            target.messageId,
                            target.initialSource,
                            target.initialFilePath,
                        ),
                    )
                },
                onChildSessionClick = { target ->
                    navigator.push(
                        SessionChat(
                            sessionId = target.sessionId,
                            sessionTitle = target.sessionTitle,
                            worktree = target.worktree,
                            optimisticNavigationInstanceId = target.optimisticNavigationInstanceId,
                            parentId = key.sessionId,
                        ),
                    )
                },
                onToolGroupClick = { target ->
                    navigator.push(target)
                },
                onFilesRequested = { worktree ->
                    navigator.push(FileBrowser(worktree = worktree))
                },
                onTerminalRequested = { worktree, projectTitle ->
                    navigator.push(
                        Terminal(
                            worktree = worktree,
                            projectTitle = projectTitle,
                        ),
                    )
                },
                onSessionReplaceRequested = { target ->
                    navigator.replaceSession(
                        SessionChat(
                            sessionId = target.sessionId,
                            sessionTitle = target.sessionTitle,
                            worktree = target.worktree,
                            optimisticNavigationInstanceId = target.optimisticNavigationInstanceId,
                        ),
                    )
                },
            )
        }
    }
    entry<SessionContext> { key ->
        RouteSurface {
            ProductRouteScreenViewEffect(
                screenId = ProductAnalytics.Screen.SESSION_CONTEXT,
            )
            SessionContextScreen(
                sessionId = key.sessionId,
                worktree = key.worktree,
                onNavigateBack = navigator.navigateBack,
            )
        }
    }
    entry<Changes> { key ->
        RouteSurface {
            ChangesScreen(
                sessionId = key.sessionId,
                worktree = key.worktree,
                messageId = key.messageId,
                initialSource = key.initialSource,
                initialFilePath = key.initialFilePath,
                onNavigateBack = navigator.navigateBack,
            )
        }
    }
    entry<FileBrowser> { key ->
        RouteSurface {
            FilesScreen(
                worktree = key.worktree,
                onNavigateBack = navigator.navigateBack,
                onOpenInHighlightingLab =
                    if (HighlightingLabSeam.isAvailable) {
                        { path, source -> HighlightingLabSeam.fileRoute(path, source)?.let(navigator.push) }
                    } else {
                        null
                    },
            )
        }
    }
    entry<SessionToolGroup> { key ->
        RouteSurface {
            val viewModel: SessionToolGroupViewModel = hiltViewModel(key = "tool-group-${key.sessionId}")
            // Bound during composition so the first frame already shows the members the store holds.
            remember(key) { viewModel.bind(key) }
            val membersState by viewModel.members.collectAsStateWithLifecycle()
            if (membersState == SessionToolGroupMembersState.Gone) {
                // The session moved on and the group is no longer in it: go back, as the activity sheet closes.
                LaunchedEffect(key) { navigator.navigateBack() }
            }
            val members = (membersState as? SessionToolGroupMembersState.Ready)?.members.orEmpty()
            val toolDetails = remember(viewModel) { SessionChatToolDetailResolver(viewModel::toolDetail) }
            var toolDetailTarget by remember {
                mutableStateOf<SessionChatToolDetailTarget?>(null)
            }
            var blockedTurnDiff by remember { mutableStateOf<OpenCodeSnackbarMessage?>(null) }
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalSessionChatToolDetailResolver provides toolDetails) {
                    SessionChatToolGroupScreen(
                        target = key.toTarget(members),
                        supportsSessionMessageDiff = key.supportsSessionMessageDiff,
                        onNavigateBack = navigator.navigateBack,
                        onToolDetailTap = {
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.SESSION_CHAT,
                                actionId = ProductAnalytics.Action.CHAT_OPEN_TOOL_DETAIL,
                            )
                            toolDetailTarget = it
                        },
                        onChildSessionTap = { childSessionId, childTitle ->
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.SESSION_CHAT,
                                actionId = ProductAnalytics.Action.CHAT_OPEN_CHILD_SESSION,
                            )
                            navigator.push(
                                SessionChat(
                                    sessionId = childSessionId,
                                    sessionTitle = childTitle,
                                    worktree = key.worktree,
                                    parentId = key.sessionId,
                                ),
                            )
                        },
                        onChangesReviewTap = changesReview@{ _, initialFilePath ->
                            val owningTurn = key.owningTurnMessageId
                            if (!key.supportsSessionMessageDiff || owningTurn == null) {
                                return@changesReview
                            }
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.SESSION_CHAT,
                                actionId = ProductAnalytics.Action.CHAT_OPEN_CHANGES_FROM_TOOL,
                            )
                            if (key.lastTurnDiffUnavailable) {
                                blockedTurnDiff =
                                    OpenCodeSnackbarMessage(
                                        text = TurnDiffUnavailable.MESSAGE,
                                        variant = OpenCodeSnackbarVariant.Error,
                                    )
                                return@changesReview
                            }
                            navigator.push(
                                Changes(
                                    sessionId = key.sessionId,
                                    worktree = key.worktree,
                                    messageId = owningTurn,
                                    initialSource = ChangeSource.LastTurn,
                                    initialFilePath = initialFilePath,
                                ),
                            )
                        },
                    )
                }
                SessionChatToolDetailSheet(
                    target = toolDetailTarget,
                    onDismiss = { toolDetailTarget = null },
                )
                OpenCodeSnackbarHost(
                    message = blockedTurnDiff,
                    onDismissed = { blockedTurnDiff = null },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
    // The File Viewer opens Lab screens in whichever stack shows it; empty in release builds.
    highlightingLabEntries(navigator.push, navigator.navigateBack)
}
