import Foundation

enum SnapshotEvidenceRole: Sendable {
    case user
    case assistant
    case childSession
    /// A completed tool card; its `imageCount` images show as a gallery under it.
    case tool
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
    /// Drawn sample images (`SnapshotEvidenceImageMocks`) shown as a gallery with the message.
    let imageCount: Int

    init(
        id: String,
        role: SnapshotEvidenceRole,
        text: String,
        metadataLabel: String? = nil,
        childSession: SnapshotEvidenceChildSession? = nil,
        imageCount: Int = 0
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.metadataLabel = metadataLabel
        self.childSession = childSession
        self.imageCount = imageCount
    }
}

enum SnapshotEvidenceMocks {
    static let sessionTitle = "Assistant message"
    static let userMetadata = "Build · DeepSeek V4 Flash · 9:05 PM"
    static let childSessionTitle = "Find auth bugs"
    /// Home filtered to a worktree with no sessions while sibling worktrees have some.
    static let worktreeEmptyMessage = SessionListWorktreeEmptyCopy.otherWorktrees

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

    /// Image galleries: two attached screenshots, a tool that returned five, and the reply.
    static let imageConversation: [SnapshotEvidenceMessage] = [
        SnapshotEvidenceMessage(
            id: "snapshot-images-user-1",
            role: .user,
            text: "The login button clips on small phones. Can you fix it?",
            metadataLabel: userMetadata,
            imageCount: 2
        ),
        SnapshotEvidenceMessage(
            id: "snapshot-images-tool-1",
            role: .tool,
            text: "Screenshot simulators",
            imageCount: 5
        ),
        SnapshotEvidenceMessage(
            id: "snapshot-images-assistant-1",
            role: .assistant,
            text: "The button now fits on every size. The captures above show each phone after the fix."
        ),
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
