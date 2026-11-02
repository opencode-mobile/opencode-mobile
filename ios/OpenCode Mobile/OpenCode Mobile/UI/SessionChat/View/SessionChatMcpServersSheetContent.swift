import SwiftUI

/// Read-only MCP server status page inside the Commands sheet. Connect,
/// disconnect and sign-in stay on the server machine in this release.
struct SessionChatMcpServersSheetContent: View {
    let servers: [McpServer]
    let loading: Bool
    let errorMessage: String?
    let summary: McpServerSummary

    var body: some View {
        if loading, servers.isEmpty {
            OpenCodeStatusStack(
                title: "session_chat_sheet.mcp.loading.title",
                subtitle: "session_chat_sheet.mcp.loading.subtitle"
            )
            .padding(.vertical, 24)
        } else if let errorMessage, servers.isEmpty {
            OpenCodeEmptyState(
                title: "session_chat_sheet.mcp.error.title",
                message: .verbatim(errorMessage, fallback: "session_chat_sheet.mcp.error.message"),
                icon: .plug
            )
        } else if servers.isEmpty {
            OpenCodeEmptyState(
                title: "session_chat_sheet.mcp.empty.title",
                message: "session_chat_sheet.mcp.empty.message",
                icon: .plug
            )
        } else {
            VStack(alignment: .leading, spacing: 4) {
                OpenCodeSectionHeader(title: headline, variant: .plain)
                ForEach(servers, id: \.name) { server in
                    OpenCodeListItem(
                        title: .verbatim(server.name),
                        subtitle: .verbatim(ifPresent: server.errorMessage),
                        status: OpenCodeListItemStatus(text: server.status.sheetLabel, tone: server.status.listItemTone)
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// "2 of 3 connected".
    private var headline: OpenCodeText {
        let connected = summary.connected
        let total = summary.total
        return OpenCodeText(LocalizedStringResource(
            "session_chat_sheet.mcp.headline",
            defaultValue: "\(connected) of \(total) connected"
        ))
    }
}

private extension McpServerStatus {
    var sheetLabel: OpenCodeText {
        switch self {
        case .connected: "session_chat_sheet.mcp.status.connected"
        case .connecting: "session_chat_sheet.mcp.status.connecting"
        case .disabled: "session_chat_sheet.mcp.status.disabled"
        case .failed: "session_chat_sheet.mcp.status.failed"
        case .needsSignIn: "session_chat_sheet.mcp.status.needs_sign_in"
        }
    }

    var listItemTone: OpenCodeListItemStatusTone {
        switch self {
        case .connected: .success
        case .connecting: .warning
        case .disabled: .stale
        case .failed: .error
        case .needsSignIn: .warning
        }
    }
}

extension McpServerSummary {
    /// Commands tile badge: count of servers needing attention, else a health dot.
    var actionTileBadge: OpenCodeActionTileBadge? {
        guard let worst else { return nil }
        return switch worst {
        case .failed: OpenCodeActionTileBadge(tone: .error, count: attention)
        case .needsSignIn: OpenCodeActionTileBadge(tone: .warning, count: attention)
        case .connecting: OpenCodeActionTileBadge(tone: .warning)
        case .connected: OpenCodeActionTileBadge(tone: .success)
        case .disabled: OpenCodeActionTileBadge(tone: .neutral)
        }
    }
}

#Preview {
    SessionChatMcpServersSheetContent(
        servers: [
            McpServer(name: "context7", status: .connected),
            McpServer(name: "github", status: .needsSignIn, errorMessage: "OAuth token expired"),
            McpServer(name: "playwright", status: .failed, errorMessage: "spawn npx ENOENT"),
            McpServer(name: "filesystem", status: .disabled)
        ],
        loading: false,
        errorMessage: nil,
        summary: McpServerCatalog.summary([
            McpServer(name: "context7", status: .connected),
            McpServer(name: "github", status: .needsSignIn),
            McpServer(name: "playwright", status: .failed),
            McpServer(name: "filesystem", status: .disabled)
        ])
    )
    .openCodeTheme()
}
