import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionAncestryTests {
    @Test func resolvesUnknownParentsAndRemembersThem() async {
        let server = AncestryServer([
            session("root"),
            session("child", parent: "root"),
            session("grandchild", parent: "child"),
        ])
        let ancestry = SessionAncestry(fetch: server.fetch)

        let first = await ancestry.resolveRoots(ids: ["grandchild"], directory: "/Users/example/project")
        #expect(first.roots == ["grandchild": "root"])
        #expect(first.rootSessions["root"]?.id == "root")
        #expect(first.learned)
        #expect(server.fetched == ["grandchild", "child", "root"])

        let again = await ancestry.resolveRoots(ids: ["grandchild", "child"], directory: "/Users/example/project")
        #expect(again.roots == ["grandchild": "root", "child": "root"])
        #expect(!again.learned)
        #expect(server.fetched.count == 3)
    }

    @Test func seededSessionsNeedNoFetch() async {
        let server = AncestryServer([session("child", parent: "root")])
        let ancestry = SessionAncestry(fetch: server.fetch)
        ancestry.record([session("root")])

        let resolution = await ancestry.resolveRoots(ids: ["child"], directory: nil)

        #expect(resolution.roots == ["child": "root"])
        #expect(server.fetched == ["child"])
    }

    @Test func aFailedLookupIsNotRetriedWithinAMinute() async {
        let clock = AncestryClock()
        let server = AncestryServer([])
        let ancestry = SessionAncestry(fetch: server.fetch, nowMs: { clock.now })

        _ = await ancestry.resolveRoots(ids: ["gone"], directory: nil)
        clock.now += SessionAncestry.failureRetryMs - 1
        let soon = await ancestry.resolveRoots(ids: ["gone"], directory: nil)
        #expect(soon.roots.isEmpty)
        #expect(server.fetched == ["gone"])

        clock.now += 1
        _ = await ancestry.resolveRoots(ids: ["gone"], directory: nil)
        #expect(server.fetched == ["gone", "gone"])
    }

    @Test func clearForgetsEverything() async {
        let ancestry = SessionAncestry(fetch: nil)
        ancestry.record([session("root"), session("child", parent: "root")])
        #expect(ancestry.rootOf("child") == "root")

        ancestry.clear()

        #expect(ancestry.rootOf("child") == nil)
        #expect(ancestry.snapshot() == .empty)
    }

    private func session(_ id: String, parent: String? = nil) -> Session {
        Session(
            id: id, title: id, slug: nil, projectID: nil,
            directory: "/Users/example/project", parentID: parent, updatedAtEpochMs: 1
        )
    }
}

private final class AncestryServer: @unchecked Sendable {
    private let lock = NSLock()
    private let sessions: [String: Session]
    private var _fetched: [String] = []

    init(_ sessions: [Session]) {
        self.sessions = Dictionary(uniqueKeysWithValues: sessions.map { ($0.id, $0) })
    }

    var fetched: [String] { lock.withLock { _fetched } }

    var fetch: SessionAncestry.Fetch {
        { [self] id, _ in
            let session = lock.withLock {
                _fetched.append(id)
                return sessions[id]
            }
            guard let session else { throw SessionError.http(statusCode: 404, message: nil) }
            return session
        }
    }
}

private final class AncestryClock: @unchecked Sendable {
    private let lock = NSLock()
    private var _now: Int64 = 1_000

    var now: Int64 {
        get { lock.withLock { _now } }
        set { lock.withLock { _now = newValue } }
    }
}
