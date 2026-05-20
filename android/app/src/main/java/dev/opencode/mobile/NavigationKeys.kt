package dev.opencode.mobile

import androidx.navigation3.runtime.NavKey
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntryId
import kotlinx.serialization.Serializable

@Serializable data object ProjectListHome : NavKey
@Serializable data object ComponentGalleryHome : NavKey

@Serializable data class ComponentGalleryDetail(val entryId: ComponentGalleryEntryId) : NavKey
@Serializable data class SessionChat(val sessionId: String, val sessionTitle: String, val worktree: String) : NavKey
