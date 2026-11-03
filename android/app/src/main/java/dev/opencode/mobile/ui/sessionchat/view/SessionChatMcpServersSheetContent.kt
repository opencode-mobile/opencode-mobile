package dev.opencode.mobile.ui.sessionchat.view

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
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
                title = stringResource(R.string.session_chat_sheet_mcp_loading_title),
                subtitle = stringResource(R.string.session_chat_sheet_mcp_loading_subtitle),
                modifier = modifier.padding(vertical = 24.dp),
            )
        errorMessage != null && servers.isEmpty() ->
            OpenCodeEmptyState(
                title = stringResource(R.string.session_chat_sheet_mcp_error_title),
                message = errorMessage.ifBlank { stringResource(R.string.session_chat_sheet_mcp_error_message) },
                icon = OpenCodeIcons.Plug,
                modifier = modifier,
            )
        servers.isEmpty() ->
            OpenCodeEmptyState(
                title = stringResource(R.string.session_chat_sheet_mcp_empty_title),
                message = stringResource(R.string.session_chat_sheet_mcp_empty_message),
                icon = OpenCodeIcons.Plug,
                modifier = modifier,
            )
        else ->
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OpenCodeSectionHeader(
                    title = stringResource(R.string.session_chat_sheet_mcp_headline, summary.connected, summary.total),
                    variant = OpenCodeSectionHeaderVariant.Plain,
                )
                servers.forEach { server ->
                    OpenCodeListItem(
                        title = server.name,
                        subtitle = server.errorMessage,
                        status =
                            OpenCodeListItemStatus(
                                text = stringResource(server.status.labelRes()),
                                tone = server.status.listItemTone(),
                            ),
                    )
                }
            }
    }
}

@StringRes
private fun McpServerStatus.labelRes(): Int =
    when (this) {
        McpServerStatus.Connected -> R.string.session_chat_sheet_mcp_status_connected
        McpServerStatus.Connecting -> R.string.session_chat_sheet_mcp_status_connecting
        McpServerStatus.Disabled -> R.string.session_chat_sheet_mcp_status_disabled
        McpServerStatus.Failed -> R.string.session_chat_sheet_mcp_status_failed
        McpServerStatus.NeedsSignIn -> R.string.session_chat_sheet_mcp_status_needs_sign_in
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
