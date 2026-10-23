package dev.opencode.mobile

import androidx.navigation3.runtime.NavKey
import dev.opencode.mobile.ui.changes.ChangeSource
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntryId
import kotlinx.serialization.Serializable

@Serializable data object ProjectListHome : NavKey

@Serializable data object SettingsHome : NavKey

@Serializable data object ConnectionSetupHome : NavKey

@Serializable data object ServersHome : NavKey

@Serializable data object AppearanceHome : NavKey

@Serializable data object ThemePickerHome : NavKey

@Serializable data object SyntaxPickerHome : NavKey

@Serializable data object TextMateLogbookHome : NavKey

@Serializable data object DiagnosticLogsHome : NavKey

@Serializable data object TextMateLanguagesHome : NavKey

@Serializable data object FeatureFlagsHome : NavKey

@Serializable data object ComponentGalleryHome : NavKey

@Serializable data class ComponentGalleryDetail(
    val entryId: ComponentGalleryEntryId,
) : NavKey

@Serializable data object TerminalsCatalog : NavKey

/** Root of the Home split view's trailing pane stack while no session is selected. */
@Serializable data object HomeSessionPanePlaceholder : NavKey

@Serializable data class Terminal(
    val worktree: String,
    val projectTitle: String,
    val ptyId: String? = null,
) : NavKey

@Serializable data class SessionChat(
    val sessionId: String,
    val sessionTitle: String,
    val worktree: String,
    val optimisticNavigationInstanceId: String? = null,
    val parentId: String? = null,
) : NavKey

@Serializable data class SessionContext(
    val sessionId: String,
    val worktree: String,
) : NavKey

@Serializable data class Changes(
    val sessionId: String,
    val worktree: String,
    val messageId: String? = null,
    val initialSource: ChangeSource = ChangeSource.LastTurn,
    val initialFilePath: String? = null,
) : NavKey

/** Read-only File Browser rooted at a Session Chat worktree. */
@Serializable data class FileBrowser(
    val worktree: String,
) : NavKey

@Serializable data class SessionToolGroup(
    val sessionId: String,
    val worktree: String,
    val parentId: String? = null,
    val blockID: String,
    val title: String,
    val subtitle: String? = null,
    val messageId: String,
    val owningTurnMessageId: String? = null,
    val supportsSessionMessageDiff: Boolean,
    val members: List<SessionToolGroupMember>,
) : NavKey

@Serializable data class SessionToolGroupMember(
    val blockID: String,
    val toolName: String,
    val status: String,
    val title: String,
    val subtitle: String? = null,
    val errorText: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val detailText: String? = null,
    val presentation: dev.opencode.mobile.ui.sessionchat.ToolResultPresentation? = null,
    val childSessionId: String? = null,
    val opensChangesReview: Boolean = false,
    val changesInitialFilePath: String? = null,
)
