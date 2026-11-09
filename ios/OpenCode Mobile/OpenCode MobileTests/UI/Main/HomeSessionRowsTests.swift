import Foundation
import Testing
@testable import OpenCode_Mobile

struct HomeSessionRowsTests {
    @Test func tenThousandRootsWithFiftyBusyChildrenMarkTheirRoots() {
        let roots = (0 ..< 10_000).map { index in
            row("root-\(index)", updatedAt: Int64(index))
        }
        // Every 200th root has a busy sub-agent whose parent the ancestry already knows.
        let children = (0 ..< 50).map { index in
            row("child-\(index)", parent: "root-\(index * 200)", updatedAt: 0)
        }
        var ancestry = SessionAncestryMap()
        children.forEach { ancestry.record($0) }
        let activity = SessionActivitySnapshot(
            entries: Dictionary(uniqueKeysWithValues: children.map {
                ($0.id, SessionActivityEntry(activity: .running, source: .server, observedAtMs: 1))
            }),
            seeded: true
        )

        let result = HomeSessionRows.build(HomeSessionRowsInput(
            worktree: "/Users/example/project",
            sessions: roots,
            activity: activity,
            readyIDs: [],
            ancestry: ancestry,
            readsServerUnread: false
        ))

        #expect(result.items.count == 10_000)
        #expect(result.unresolvedBusyIDs.isEmpty)
        #expect(result.items.first?.id == "root-9999")
        let working = Set(result.items.filter { $0.statusLabel == "Working" }.map(\.id))
        #expect(working == Set((0 ..< 50).map { "root-\($0 * 200)" }))
    }

    @Test func childrenAndArchivedRowsAnOlderServerListedAreSkipped() {
        var archived = row("archived", updatedAt: 3)
        archived.archivedAtEpochMs = 4
        let result = HomeSessionRows.build(HomeSessionRowsInput(
            worktree: "/Users/example/project",
            sessions: [row("root", updatedAt: 1), row("child", parent: "root", updatedAt: 2), archived],
            activity: nil,
            readyIDs: [],
            ancestry: .empty,
            readsServerUnread: false
        ))

        #expect(result.items.map(\.id) == ["root"])
    }

    @Test func busyRootOfTheSameProjectInAnotherWorktreeJoinsTheSection() {
        let other = Session(
            id: "other", title: "other", slug: nil, projectID: "prj",
            directory: "/Users/example/project-wt", parentID: nil, updatedAtEpochMs: 1
        )
        var ancestry = SessionAncestryMap()
        ancestry.record(other)
        let activity = SessionActivitySnapshot(
            entries: ["other": SessionActivityEntry(activity: .running, source: .server, observedAtMs: 1)],
            seeded: true
        )
        let input = { (projectID: String?) in
            HomeSessionRowsInput(
                worktree: "/Users/example/project",
                sessions: [self.row("root", updatedAt: 5)],
                activity: activity,
                readyIDs: [],
                ancestry: ancestry,
                projectID: projectID,
                readsServerUnread: true
            )
        }

        #expect(HomeSessionRows.build(input("prj")).items.map(\.id) == ["root", "other"])
        #expect(HomeSessionRows.build(input(nil)).items.map(\.id) == ["root"])
    }

    private func row(_ id: String, parent: String? = nil, updatedAt: Int64) -> Session {
        Session(
            id: id,
            title: id,
            slug: nil,
            projectID: nil,
            directory: "/Users/example/project",
            parentID: parent,
            updatedAtEpochMs: updatedAt
        )
    }
}
