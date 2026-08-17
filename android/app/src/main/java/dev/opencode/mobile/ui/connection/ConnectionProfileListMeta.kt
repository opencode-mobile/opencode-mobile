package dev.opencode.mobile.ui.connection

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone

internal fun ConnectionProfile.listCaption(isActive: Boolean): String? =
    serverProtocol?.takeIf { isActive }?.apiCaption

internal fun ConnectionProfile.listStatus(isActive: Boolean): OpenCodeListItemStatus? =
    when {
        isActive -> OpenCodeListItemStatus("Active", OpenCodeListItemStatusTone.Interactive)
        serverProtocol != null ->
            OpenCodeListItemStatus(serverProtocol.shortLabel, OpenCodeListItemStatusTone.Info)
        else -> null
    }
