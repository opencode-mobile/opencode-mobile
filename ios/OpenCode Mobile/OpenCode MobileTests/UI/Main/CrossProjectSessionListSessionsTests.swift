import Testing
@testable import OpenCode_Mobile

struct CrossProjectSessionListSessionsTests {
    @Test func sameIdInTwoProjects_keepsNewerRowFromLaterProject() {
        let sessions = [
            section(worktree: "/earlier", session(id: "ses", title: "older", updatedAtEpochMs: 10)),
            section(worktree: "/later", session(id: "ses", title: "newer", updatedAtEpochMs: 20)),
        ].crossProjectSessionListSessions()

        #expect(sessions.map(\.id) == ["ses"])
        #expect(sessions.first?.title == "newer")
        #expect(sessions.first?.worktree == "/later")
        #expect(sessions.first?.updatedAtEpochMs == 20)
    }

    @Test func sameId_equalTimestamps_keepsEarlierProject() {
        let sessions = [
            section(worktree: "/earlier", session(id: "ses", title: "first", updatedAtEpochMs: 10)),
            section(worktree: "/later", session(id: "ses", title: "second", updatedAtEpochMs: 10)),
        ].crossProjectSessionListSessions()

        #expect(sessions.map(\.id) == ["ses"])
        #expect(sessions.first?.title == "first")
        #expect(sessions.first?.worktree == "/earlier")
    }

    @Test func sameId_nullTimestampLosesToRealTimestamp() {
        let realThenNull = [
            section(worktree: "/real", session(id: "ses", title: "dated", updatedAtEpochMs: 50)),
            section(worktree: "/missing", session(id: "ses", title: "undated", updatedAtEpochMs: nil)),
        ].crossProjectSessionListSessions()
        let nullThenReal = [
            section(worktree: "/missing", session(id: "ses", title: "undated", updatedAtEpochMs: nil)),
            section(worktree: "/real", session(id: "ses", title: "dated", updatedAtEpochMs: 50)),
        ].crossProjectSessionListSessions()

        #expect(realThenNull.map(\.worktree) == ["/real"])
        #expect(realThenNull.first?.updatedAtEpochMs == 50)
        #expect(nullThenReal.map(\.worktree) == ["/real"])
        #expect(nullThenReal.first?.updatedAtEpochMs == 50)
    }

    @Test func distinctIds_stayNewestFirst() {
        let sessions = [
            section(
                worktree: "/a",
                session(id: "old", title: "Old", updatedAtEpochMs: 100),
                session(id: "tie-early", title: "Early", updatedAtEpochMs: 50)
            ),
            section(
                worktree: "/b",
                session(id: "tie-late", title: "Late", updatedAtEpochMs: 50),
                session(id: "newest", title: "Newest", updatedAtEpochMs: 200)
            ),
        ].crossProjectSessionListSessions()

        #expect(sessions.map(\.id) == ["newest", "old", "tie-early", "tie-late"])
    }

    @Test func sameIdTwiceInOneProject_keepsNewerRow() {
        let newerLast = [
            section(
                worktree: "/a",
                session(id: "ses", title: "older", updatedAtEpochMs: 10),
                session(id: "ses", title: "newer", updatedAtEpochMs: 30)
            ),
        ].crossProjectSessionListSessions()
        let newerFirst = [
            section(
                worktree: "/a",
                session(id: "ses", title: "newer", updatedAtEpochMs: 30),
                session(id: "ses", title: "older", updatedAtEpochMs: 10)
            ),
        ].crossProjectSessionListSessions()

        #expect(newerLast.map(\.title) == ["newer"])
        #expect(newerLast.first?.updatedAtEpochMs == 30)
        #expect(newerFirst.map(\.title) == ["newer"])
        #expect(newerFirst.first?.updatedAtEpochMs == 30)
    }

    @Test func projectCounts_followTheWinningWorktree() {
        let sections = [
            section(
                worktree: "/earlier",
                session(id: "shared", title: "older", updatedAtEpochMs: 10),
                session(id: "local", title: "stays", updatedAtEpochMs: 5)
            ),
            section(worktree: "/later", session(id: "shared", title: "newer", updatedAtEpochMs: 20)),
            section(worktree: "/empty-after-dedupe", session(id: "shared", title: "oldest", updatedAtEpochMs: 1)),
        ]
        let counts = Dictionary(
            uniqueKeysWithValues: sections.homeChromeProjects().map { ($0.worktree, $0.sessionCount) }
        )

        #expect(counts["/earlier"] == 1)
        #expect(counts["/later"] == 1)
        #expect(counts["/empty-after-dedupe"] == 0)
    }

    private func section(worktree: String, _ sessions: SessionListItem...) -> ProjectSectionItem {
        ProjectSectionItem(
            id: worktree,
            worktree: worktree,
            displayName: worktree,
            pathSubtitle: worktree,
            letter: "P",
            letterAccent: .violet,
            allSessions: sessions,
            loadError: nil
        )
    }

    private func session(id: String, title: String, updatedAtEpochMs: Int64?) -> SessionListItem {
        SessionListItem(
            id: id,
            title: title,
            worktree: worktreePlaceholder,
            updatedAtEpochMs: updatedAtEpochMs
        )
    }

    private let worktreePlaceholder = ""
}
