package dev.opencode.mobile

import androidx.navigation3.runtime.NavKey
import dev.opencode.mobile.ui.changes.ChangeSource
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntryId
import kotlinx.serialization.Serializable

@Serializable data object ProjectListHome : NavKey

@Serializable data object SettingsHome : NavKey

@Serializable data object TextMateLogbookHome : NavKey

@Serializable data object ComponentGalleryHome : NavKey

@Serializable data class ComponentGalleryDetail(
    val entryId: ComponentGalleryEntryId,
) : NavKey

@Serializable data class SessionChat(
    val sessionId: String,
    val sessionTitle: String,
    val worktree: String,
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
