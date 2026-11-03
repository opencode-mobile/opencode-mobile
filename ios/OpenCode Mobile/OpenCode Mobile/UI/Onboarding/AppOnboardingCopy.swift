import Foundation

enum AppOnboardingCopy {
    static let serverDocsURL = URL(string: "https://opencode.ai/docs/server/")!

    struct FeatureRow: Identifiable, Hashable {
        let id: String
        let title: OpenCodeText
        let detail: OpenCodeText
    }

    struct Page: Identifiable, Hashable {
        let id: String
        let title: OpenCodeText
        let body: OpenCodeText?
        let badge: OpenCodeText?
        let setupLink: OpenCodeText?
        let features: [FeatureRow]
        let illustration: OpenCodeSkeletonIllustrationTopic
    }

    static let pages: [Page] = [
        Page(
            id: "unofficial",
            title: "onboarding.unofficial.title",
            body: "onboarding.unofficial.body",
            badge: "onboarding.unofficial.badge",
            setupLink: nil,
            features: [],
            illustration: .unofficial
        ),
        Page(
            id: "connect",
            title: "onboarding.connect_page.title",
            body: "onboarding.connect_page.body",
            badge: nil,
            setupLink: "onboarding.connect_page.setup_link",
            features: [],
            illustration: .connectForm
        ),
        Page(
            id: "features",
            title: "onboarding.features.title",
            body: nil,
            badge: nil,
            setupLink: nil,
            features: [
                FeatureRow(id: "sessions", title: "onboarding.features.sessions.title", detail: "onboarding.features.sessions.detail"),
                FeatureRow(id: "chat", title: "onboarding.features.chat.title", detail: "onboarding.features.chat.detail"),
                FeatureRow(
                    id: "permissions",
                    title: "onboarding.features.permissions.title",
                    detail: "onboarding.features.permissions.detail"
                ),
                FeatureRow(id: "changes", title: "onboarding.features.changes.title", detail: "onboarding.features.changes.detail"),
                FeatureRow(id: "live", title: "onboarding.features.live.title", detail: "onboarding.features.live.detail"),
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
