import Testing
@testable import OpenCode_Mobile

struct MessageSnapshotAuthorityTests {
    @Test func shorterRemoteTextIsBehind() {
        #expect(
            MessageSnapshotAuthority.isAheadOrEqual(
                remote: [assistant("Hi", streaming: false)],
                local: [assistant("Hello", streaming: true)]
            ) == false
        )
    }

    @Test func longerRemoteTextIsAhead() {
        #expect(
            MessageSnapshotAuthority.isAheadOrEqual(
                remote: [assistant("Hello there", streaming: false)],
                local: [assistant("Hello", streaming: true)]
            )
        )
    }

    @Test func equalTextWithRemoteFinalizedIsAhead() {
        #expect(
            MessageSnapshotAuthority.isAheadOrEqual(
                remote: [assistant("Hello", streaming: false)],
                local: [assistant("Hello", streaming: true)]
            )
        )
    }

    @Test func equalSnapshotsAreAheadOrEqual() {
        #expect(
            MessageSnapshotAuthority.isAheadOrEqual(
                remote: [assistant("Hello", streaming: true)],
                local: [assistant("Hello", streaming: true)]
            )
        )
    }

    private func assistant(_ text: String, streaming: Bool) -> Message {
        Message(
            id: "msg-2",
            role: .assistant,
            text: text,
            parts: [.text(TextPart(text: text, isStreaming: streaming))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
    }
}
