import Foundation

enum SnapshotEvidenceRole: Sendable {
    case user
    case assistant
}

struct SnapshotEvidenceMessage: Identifiable, Sendable {
    let id: String
    let role: SnapshotEvidenceRole
    let text: String
    let metadataLabel: String?
}

enum SnapshotEvidenceMocks {
    static let sessionTitle = "Assistant message"
    static let userMetadata = "Build · DeepSeek V4 Flash · 9:05 PM"

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
}
