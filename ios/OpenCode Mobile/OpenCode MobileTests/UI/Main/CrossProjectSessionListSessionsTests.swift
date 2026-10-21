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
        #expect(sessions.first?.worktree == "path:/later")
        #expect(sessions.first?.updatedAtEpochMs == 20)
    }

    @Test func sameId_equalTimestamps_keepsEarlierProject() {
        let sessions = [
            section(worktree: "/earlier", session(id: "ses", title: "first", updatedAtEpochMs: 10)),
            section(worktree: "/later", session(id: "ses", title: "second", updatedAtEpochMs: 10)),
        ].crossProjectSessionListSessions()

        #expect(sessions.map(\.id) == ["ses"])
        #expect(sessions.first?.title == "first")
        #expect(sessions.first?.worktree == "path:/earlier")
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

        #expect(realThenNull.map(\.worktree) == ["path:/real"])
        #expect(realThenNull.first?.updatedAtEpochMs == 50)
        #expect(nullThenReal.map(\.worktree) == ["path:/real"])
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
        let projects = HomeProjectGrouping.group(addedPaths: sections.map(\.worktree), resolutions: [:])
        let tabs = SessionListHomeChrome.filterTabs(
            projects: projects,
            sessions: sections.crossProjectSessionListSessions(projects: projects)
        )
        let counts = Dictionary(uniqueKeysWithValues: tabs.map { ($0.id, $0.count) })

        #expect(counts["path:/earlier"] == 1)
        #expect(counts["path:/later"] == 1)
        #expect(counts["path:/empty-after-dedupe"] == 0)
        #expect(counts[SessionListFilterTab.allId] == 2)
    }

    @Test func worktreeSessionStaysInAddedProjectButOpensItsOwnDirectory() {
        let sessions = [
            section(
                worktree: "/repo",
                session(
                    id: "ses_worktree",
                    title: "Add project sheet prototype",
                    updatedAtEpochMs: 20,
                    directory: "/worktrees/add-project-browse"
                )
            ),
        ].crossProjectSessionListSessions()

        #expect(sessions.first?.worktree == "path:/repo")
        #expect(sessions.first?.directory == "/worktrees/add-project-browse")
    }

    @Test func mergedWorktreesListEachSessionOnceUnderOneProject() {
        let resolution = HomeProjectResolution(id: "prj", canonical: "/repo", isRepository: true)
        let sections = [
            section(
                worktree: "/repo",
                session(id: "a", title: "Main", updatedAtEpochMs: 30, directory: "/repo"),
                session(id: "b", title: "Feature", updatedAtEpochMs: 20, directory: "/repo-wt")
            ),
            // v2 lists the whole project for every added worktree.
            section(
                worktree: "/repo-wt",
                session(id: "a", title: "Main", updatedAtEpochMs: 30, directory: "/repo"),
                session(id: "b", title: "Feature", updatedAtEpochMs: 20, directory: "/repo-wt")
            ),
        ]
        let projects = HomeProjectGrouping.group(
            addedPaths: ["/repo", "/repo-wt"],
            resolutions: ["/repo": resolution, "/repo-wt": resolution],
            branches: ["/repo": "main", "/repo-wt": "feature/b"]
        )

        let sessions = sections.crossProjectSessionListSessions(projects: projects)

        #expect(sessions.map(\.id) == ["a", "b"])
        #expect(sessions.map(\.worktree) == ["prj", "prj"])
        #expect(sessions.map(\.worktreeScopeID) == [
            HomeScope.worktreeID(projectKey: "prj", directory: "/repo"),
            HomeScope.worktreeID(projectKey: "prj", directory: "/repo-wt"),
        ])
        #expect(sessions[0].location?.parts == [.text("repo"), .branch("main")])
        #expect(sessions[0].scopedLocation?.parts == [.branch("main")])
        #expect(sessions[1].scopedLocation?.parts == [.branch("feature/b"), .text("repo-wt")])

        let scoped = sessions.visibleForFilter(HomeScope.worktreeID(projectKey: "prj", directory: "/repo-wt"))
        #expect(scoped.map(\.id) == ["b"])
        #expect(sessions.visibleForFilter("prj").map(\.id) == ["a", "b"])
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

    private func session(
        id: String,
        title: String,
        updatedAtEpochMs: Int64?,
        directory: String? = nil
    ) -> SessionListItem {
        SessionListItem(
            id: id,
            title: title,
            worktree: worktreePlaceholder,
            updatedAtEpochMs: updatedAtEpochMs,
            directory: directory
        )
    }

    private let worktreePlaceholder = ""
}
