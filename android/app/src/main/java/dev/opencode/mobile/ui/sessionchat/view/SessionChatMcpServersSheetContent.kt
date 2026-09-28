package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.mcp.McpServer
import dev.opencode.mobile.domain.mcp.McpServerStatus
import dev.opencode.mobile.domain.mcp.McpServerSummary
import dev.opencode.mobile.ui.components.OpenCodeActionTileBadge
import dev.opencode.mobile.ui.components.OpenCodeActionTileBadgeTone
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderVariant
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/**
 * Read-only MCP server status page inside the Commands sheet. Connect,
 * disconnect and sign-in stay on the server machine in this release.
 */
@Composable
fun SessionChatMcpServersSheetContent(
    servers: List<McpServer>,
    loading: Boolean,
    errorMessage: String?,
    summary: McpServerSummary,
    modifier: Modifier = Modifier,
) {
    when {
        loading && servers.isEmpty() ->
            OpenCodeStatusStack(
                title = "Checking MCP servers",
                subtitle = "Reading status from the opencode server",
                modifier = modifier.padding(vertical = 24.dp),
            )
        errorMessage != null && servers.isEmpty() ->
            OpenCodeEmptyState(
                title = "Couldn't load MCP servers",
                message = errorMessage,
                icon = OpenCodeIcons.Plug,
                modifier = modifier,
            )
        servers.isEmpty() ->
            OpenCodeEmptyState(
                title = "No MCP servers",
                message = "None are configured on this opencode server.",
                icon = OpenCodeIcons.Plug,
                modifier = modifier,
            )
        else ->
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OpenCodeSectionHeader(title = summary.headline, variant = OpenCodeSectionHeaderVariant.Plain)
                servers.forEach { server ->
                    OpenCodeListItem(
                        title = server.name,
                        subtitle = server.errorMessage,
                        status = OpenCodeListItemStatus(text = server.status.label, tone = server.status.listItemTone()),
                    )
                }
            }
    }
}

private fun McpServerStatus.listItemTone(): OpenCodeListItemStatusTone =
    when (this) {
        McpServerStatus.Connected -> OpenCodeListItemStatusTone.Success
        McpServerStatus.Connecting -> OpenCodeListItemStatusTone.Warning
        McpServerStatus.Disabled -> OpenCodeListItemStatusTone.Stale
        McpServerStatus.Failed -> OpenCodeListItemStatusTone.Error
        McpServerStatus.NeedsSignIn -> OpenCodeListItemStatusTone.Warning
    }

/** Commands tile badge: count of servers needing attention, else a health dot. */
fun McpServerSummary.toActionTileBadge(): OpenCodeActionTileBadge? {
    val worst = worst ?: return null
    return when (worst) {
        McpServerStatus.Failed -> OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Error, count = attention)
        McpServerStatus.NeedsSignIn -> OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Warning, count = attention)
        McpServerStatus.Connecting -> OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Warning)
        McpServerStatus.Connected -> OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Success)
        McpServerStatus.Disabled -> OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Neutral)
    }
}
