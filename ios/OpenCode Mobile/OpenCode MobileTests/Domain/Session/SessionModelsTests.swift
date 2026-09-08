import Testing
@testable import OpenCode_Mobile

struct SessionModelsTests {
    @Test func activeSessionsExcludesArchived() {
        let active = Session(
            id: "a",
            title: "Active",
            slug: nil,
            projectID: nil,
            directory: nil,
            parentID: nil,
            updatedAtEpochMs: 1
        )
        var archived = Session(
            id: "b",
            title: "Archived",
            slug: nil,
            projectID: nil,
            directory: nil,
            parentID: nil,
            updatedAtEpochMs: 1
        )
        archived.archivedAtEpochMs = 10

        #expect([active, archived].activeSessions() == [active])
    }

    @Test func rootSessionsExcludesChildSessions() {
        let root = Session(
            id: "root",
            title: "Root",
            slug: nil,
            projectID: nil,
            directory: nil,
            parentID: nil,
            updatedAtEpochMs: 1
        )
        let child = Session(
            id: "child",
            title: "Child",
            slug: nil,
            projectID: nil,
            directory: nil,
            parentID: "root",
            updatedAtEpochMs: 1
        )

        #expect([root, child].rootSessions() == [root])
    }
}
