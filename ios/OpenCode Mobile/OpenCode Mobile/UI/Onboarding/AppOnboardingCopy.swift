import Foundation

enum AppOnboardingCopy {
    static let serverDocsURL = URL(string: "https://opencode.ai/docs/server/")!

    struct FeatureRow: Identifiable, Hashable {
        let id: String
        let title: String
        let detail: String
    }

    struct Page: Identifiable, Hashable {
        let id: String
        let title: String
        let body: String?
        let badge: String?
        let setupLink: String?
        let features: [FeatureRow]
        let illustration: OpenCodeSkeletonIllustrationTopic
    }

    static let pages: [Page] = [
        Page(
            id: "unofficial",
            title: "An unofficial\nopencode client",
            body: "OpenCode Mobile is a native client for opencode sessions — work in progress. It is not an official OpenCode product.",
            badge: "Unofficial",
            setupLink: nil,
            features: [],
            illustration: .unofficial
        ),
        Page(
            id: "connect",
            title: "Connect to\nyour servers",
            body: "Point the app at an opencode server on your machine. We recommend Tailscale so your phone can reach it securely from anywhere.",
            badge: nil,
            setupLink: "Need the server first?",
            features: [],
            illustration: .connectForm
        ),
        Page(
            id: "features",
            title: "What you can\ndo from here",
            body: nil,
            badge: nil,
            setupLink: nil,
            features: [
                FeatureRow(id: "sessions", title: "Sessions", detail: "List, open, create, rename, fork, archive, delete"),
                FeatureRow(id: "chat", title: "Chat & abort", detail: "Send prompts and stop work in flight"),
                FeatureRow(id: "permissions", title: "Permissions", detail: "Approve or deny requests (no lasting remember)"),
                FeatureRow(id: "changes", title: "Changes", detail: "Inspect session diffs"),
                FeatureRow(id: "live", title: "Live while open", detail: "Foreground event stream; refresh after reconnect"),
            ],
            illustration: .sessionsLive
        ),
    ]

    static func featureIcon(for id: String) -> OpenCodeIcon {
        switch id {
        case "sessions": .list
        case "chat": .stopCircle
        case "permissions": .check
        case "changes": .description
        case "live": .cloud
        default: .widgets
        }
    }
}
