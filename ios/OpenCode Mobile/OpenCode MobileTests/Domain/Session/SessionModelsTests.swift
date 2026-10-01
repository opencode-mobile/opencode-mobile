import Foundation
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

    @Test func v2ForkIsTopLevelSession() throws {
        let json = #"{"id":"ses_fork","title":"Fork","fork":{"sessionID":"ses_src","boundary":{"type":"through","messageID":"msg_1"}}}"#
        let dto = try JSONDecoder().decode(V2SessionDTO.self, from: Data(json.utf8))

        let fork = SessionDomainAdapter().session(from: dto)

        #expect(fork.parentID == nil)
        #expect([fork].rootSessions() == [fork])
    }

    @Test func v2ParentIDStillMarksChildSession() throws {
        let json = #"{"id":"ses_child","title":"Child","parentID":"ses_1"}"#
        let dto = try JSONDecoder().decode(V2SessionDTO.self, from: Data(json.utf8))

        let child = SessionDomainAdapter().session(from: dto)

        #expect(child.parentID == "ses_1")
        #expect([child].rootSessions().isEmpty)
    }
}
