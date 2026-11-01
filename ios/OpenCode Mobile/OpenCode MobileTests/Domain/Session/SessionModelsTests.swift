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

    @Test func homeActivity_runningChildMarksIdleRoot() {
        let sessions = [
            session("root"),
            session("child", parentID: "root"),
            session("other"),
            session("other-child", parentID: "other"),
        ]
        let snapshot = busySnapshot(["child": .running, "other-child": .running])

        #expect(snapshot.homeActivitySessionID(rootID: "root", sessions: sessions) == "child")
        #expect(snapshot.homeActivitySessionID(rootID: "other", sessions: sessions) == "other-child")
        #expect(snapshot.statusLabel(for: "child") == "Working")
    }

    @Test func homeActivity_nestedChildRollsUpToRoot() {
        let sessions = [
            session("root"),
            session("child", parentID: "root"),
            session("grandchild", parentID: "child"),
        ]
        let snapshot = busySnapshot(["grandchild": .running])

        #expect(snapshot.homeActivitySessionID(rootID: "root", sessions: sessions) == "grandchild")
    }

    @Test func homeActivity_keepsOwnLabelWhenRootIsBusiest() {
        let sessions = [session("root"), session("child", parentID: "root")]
        let snapshot = busySnapshot([
            "root": .retrying(attempt: 2, nextAtMs: nil, message: nil, errorType: nil, actionLabel: nil, actionLink: nil),
            "child": .retrying(attempt: 1, nextAtMs: nil, message: nil, errorType: nil, actionLabel: nil, actionLink: nil),
        ])

        #expect(snapshot.homeActivitySessionID(rootID: "root", sessions: sessions) == "root")
    }

    @Test func homeActivity_runningChildOutranksRetryingRoot() {
        let sessions = [session("root"), session("child", parentID: "root")]
        let snapshot = busySnapshot([
            "root": .retrying(attempt: 2, nextAtMs: nil, message: nil, errorType: nil, actionLabel: nil, actionLink: nil),
            "child": .running,
        ])

        #expect(snapshot.homeActivitySessionID(rootID: "root", sessions: sessions) == "child")
    }

    @Test func homeActivity_ignoresArchivedChildAndUnseededStatus() {
        var archived = session("child", parentID: "root")
        archived.archivedAtEpochMs = 10
        let sessions = [session("root"), archived]
        let unseeded = SessionActivitySnapshot(
            entries: ["child": SessionActivityEntry(activity: .running, source: .server, observedAtMs: 1)],
            seeded: false
        )

        #expect(unseeded.homeActivitySessionID(rootID: "root", sessions: sessions) == nil)
        #expect(busySnapshot(["child": .running]).homeActivitySessionID(rootID: "root", sessions: sessions) == nil)
    }
}

private func session(_ id: String, parentID: String? = nil) -> Session {
    Session(id: id, title: id, slug: nil, projectID: nil, directory: nil, parentID: parentID, updatedAtEpochMs: 1)
}

private func busySnapshot(_ activities: [String: SessionActivity]) -> SessionActivitySnapshot {
    SessionActivitySnapshot(
        entries: activities.mapValues { SessionActivityEntry(activity: $0, source: .server, observedAtMs: 1) },
        seeded: true
    )
}
