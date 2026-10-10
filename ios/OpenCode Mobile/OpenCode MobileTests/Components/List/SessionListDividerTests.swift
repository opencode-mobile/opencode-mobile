import Testing
@testable import OpenCode_Mobile

struct SessionListDividerTests {
    private let sessions: [SessionListSession] = ["a", "b", "c", "d"].map { id in
        SessionListSession(
            id: id,
            title: "Session \(id)",
            worktree: "/w",
            letter: "W",
            letterAccent: .violet,
            subtitle: "/w",
            status: .idle,
            caption: ""
        )
    }

    private func dividers(selected: String?) -> [Bool] {
        sessions.indices.map { sessionListShowsDivider(after: $0, in: sessions, selectedSessionId: selected) }
    }

    @Test func noSelection_keepsInnerDividersAndNoneAfterLastRow() {
        #expect(dividers(selected: nil) == [true, true, true, false])
    }

    @Test func selectionOnFirstRow_hidesDividerBelow() {
        #expect(dividers(selected: "a") == [false, true, true, false])
    }

    @Test func selectionOnMiddleRow_hidesDividersAboveAndBelow() {
        #expect(dividers(selected: "b") == [false, false, true, false])
    }

    @Test func selectionOnLastRow_hidesDividerAbove() {
        #expect(dividers(selected: "d") == [true, true, false, false])
    }

    @Test func selectionOutsideSection_changesNothing() {
        #expect(dividers(selected: "zzz") == [true, true, true, false])
    }

    @Test func singleRowSection_hasNoDivider() {
        let single = Array(sessions.prefix(1))
        #expect(!sessionListShowsDivider(after: 0, in: single, selectedSessionId: nil))
        #expect(!sessionListShowsDivider(after: 0, in: single, selectedSessionId: "a"))
    }
}
