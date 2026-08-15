import Foundation

enum SnapshotEvidenceRole: Sendable {
    case user
    case assistant
    case childSession
}

struct SnapshotEvidenceChildSession: Sendable {
    let agentName: String
    let letter: String
    let isBackground: Bool
    let secondLine: String?
    /// Elapsed offset from now, so ImageRenderer captures a stable `m:ss`.
    let elapsedMs: Int64?
    let showsChevron: Bool

    var timeStartEpochMs: Int64? {
        guard let elapsedMs else { return nil }
        return Int64(Date().timeIntervalSince1970 * 1000) - elapsedMs
    }
}

struct SnapshotEvidenceMessage: Identifiable, Sendable {
    let id: String
    let role: SnapshotEvidenceRole
    let text: String
    let metadataLabel: String?
    let childSession: SnapshotEvidenceChildSession?

    init(
        id: String,
        role: SnapshotEvidenceRole,
        text: String,
        metadataLabel: String? = nil,
        childSession: SnapshotEvidenceChildSession? = nil
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.metadataLabel = metadataLabel
        self.childSession = childSession
    }
}

enum SnapshotEvidenceMocks {
    static let sessionTitle = "Assistant message"
    static let userMetadata = "Build · DeepSeek V4 Flash · 9:05 PM"
    static let childSessionTitle = "Find auth bugs"

    static let userPrompt = SnapshotEvidenceMessage(
        id: "snapshot-user-1",
        role: .user,
        text: "Walk me through the assistant message on mobile.",
        metadataLabel: userMetadata
    )

    static let assistantReply = SnapshotEvidenceMessage(
        id: "snapshot-assistant-1",
        role: .assistant,
        text: """
        The timeline should alternate outgoing prompts and incoming replies with comfortable spacing.
        When the latest messages are off-screen, a floating scroll-to-end pill appears above the composer.
        """,
        metadataLabel: nil
    )

    static let conversation: [SnapshotEvidenceMessage] = [userPrompt, assistantReply]

    static let childSessionUserPrompt = SnapshotEvidenceMessage(
        id: "snapshot-child-user-1",
        role: .user,
        text: "Find auth bugs in the login flow.",
        metadataLabel: userMetadata
    )

    static let runningChildSession = SnapshotEvidenceChildSession(
        agentName: "explore",
        letter: "E",
        isBackground: true,
        secondLine: "Find auth bugs",
        elapsedMs: 84_000,
        showsChevron: true
    )

    static let runningChildSessionMessage = SnapshotEvidenceMessage(
        id: "snapshot-child-1",
        role: .childSession,
        text: "",
        childSession: runningChildSession
    )

    static let childSessionConversation: [SnapshotEvidenceMessage] = [
        childSessionUserPrompt,
        runningChildSessionMessage,
    ]

    static var idleComposer: OpenCodeSessionComposerState {
        .prompt(
            .init(
                text: "",
                selectedModelLabel: "Claude Sonnet 4",
                selectedModeLabel: "Build",
                selectedVariantLabel: "Default",
                showVariant: true,
                sendEnabled: false,
                showStop: false,
                stopEnabled: false
            )
        )
    }

    static let childLockoutAssistantReply = SnapshotEvidenceMessage(
        id: "snapshot-child-assistant-1",
        role: .assistant,
        text: "Login sets a session cookie without SameSite. CSRF on POST /login is missing."
    )

    static let childLockoutConversation: [SnapshotEvidenceMessage] = [
        childSessionUserPrompt,
        childLockoutAssistantReply,
    ]

    static var childLockoutComposer: OpenCodeSessionComposerState {
        .childLockout(
            .init(
                selectedModelLabel: "Claude Sonnet 4",
                selectedVariantLabel: "High",
                showVariant: true
            )
        )
    }
}
