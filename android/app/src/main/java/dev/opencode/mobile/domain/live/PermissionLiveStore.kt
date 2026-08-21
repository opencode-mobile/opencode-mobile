package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.PermissionRequest
import kotlinx.coroutines.flow.Flow

interface PermissionLiveStore {
    fun observePermissions(directory: String): Flow<List<PermissionRequest>>

    fun clearCache()

    fun invalidate(directory: String?) {}
}
