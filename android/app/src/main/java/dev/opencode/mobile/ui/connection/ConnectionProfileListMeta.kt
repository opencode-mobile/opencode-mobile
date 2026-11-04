package dev.opencode.mobile.ui.connection

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone

internal fun ConnectionProfile.listCaption(isActive: Boolean): String? = serverProtocol?.takeIf { isActive }?.apiCaption

/** [activeLabel] is the localised "Active" text (`R.string.servers_status_active`). */
internal fun ConnectionProfile.listStatus(
    isActive: Boolean,
    activeLabel: String,
): OpenCodeListItemStatus? =
    when {
        isActive -> OpenCodeListItemStatus(activeLabel, OpenCodeListItemStatusTone.Interactive)
        else -> serverProtocol?.let { OpenCodeListItemStatus(it.shortLabel, OpenCodeListItemStatusTone.Info) }
    }
