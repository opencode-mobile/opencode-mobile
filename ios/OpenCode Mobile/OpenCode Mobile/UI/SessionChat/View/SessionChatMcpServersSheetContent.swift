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
                title: "Checking MCP servers",
                subtitle: "Reading status from the opencode server"
            )
            .padding(.vertical, 24)
        } else if let errorMessage, servers.isEmpty {
            OpenCodeEmptyState(
                title: "Couldn't load MCP servers",
                message: errorMessage,
                icon: .plug
            )
        } else if servers.isEmpty {
            OpenCodeEmptyState(
                title: "No MCP servers",
                message: "None are configured on this opencode server.",
                icon: .plug
            )
        } else {
            VStack(alignment: .leading, spacing: 4) {
                OpenCodeSectionHeader(title: summary.headline, variant: .plain)
                ForEach(servers, id: \.name) { server in
                    OpenCodeListItem(
                        title: server.name,
                        subtitle: server.errorMessage,
                        status: OpenCodeListItemStatus(text: server.status.label, tone: server.status.listItemTone)
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private extension McpServerStatus {
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
