import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
struct OpenCodeURLSessionsTests {
    @Test func restSessionUsesShortTimeoutsWithoutWaitingForConnectivity() {
        assertRestTimeouts(OpenCodeURLSessions.rest)
    }

    @Test func replaceRestSwapsTheLivePoolAndLeavesInjectedSessionsFixed() {
        let original = OpenCodeURLSessions.rest
        let explicit = URLSession(configuration: .ephemeral)
        let live = AgentsAPIClient(connectionProvider: { nil })
        let fixed = AgentsAPIClient(connectionProvider: { nil }, urlSession: explicit)
        #expect(live.resolvedURLSession() === original)
        #expect(fixed.resolvedURLSession() === explicit)

        OpenCodeURLSessions.replaceRest()
        let replaced = OpenCodeURLSessions.rest
        defer {
            // `original` is cancelled. Leave a live pool so later tests are not stuck on it.
            if OpenCodeURLSessions.rest !== original {
                OpenCodeURLSessions.replaceRest()
            }
        }

        #expect(replaced !== original)
        #expect(live.resolvedURLSession() === replaced)
        #expect(live.resolvedURLSession() !== original)
        #expect(fixed.resolvedURLSession() === explicit)
        assertRestTimeouts(replaced)
        assertRestTimeouts(OpenCodeURLSessions.rest)
    }

    @Test func replaceRestMovesTheLiveWorktreesClientToTheNewPool() {
        let original = OpenCodeURLSessions.rest
        let explicit = URLSession(configuration: .ephemeral)
        let live = WorktreesAPIClient(connectionProvider: { nil })
        let fixed = WorktreesAPIClient(connectionProvider: { nil }, urlSession: explicit)
        #expect(live.resolvedURLSession() === original)
        #expect(fixed.resolvedURLSession() === explicit)

        OpenCodeURLSessions.replaceRest()
        let replaced = OpenCodeURLSessions.rest
        defer {
            if OpenCodeURLSessions.rest !== original {
                OpenCodeURLSessions.replaceRest()
            }
        }

        #expect(replaced !== original)
        #expect(live.resolvedURLSession() === replaced)
        #expect(fixed.resolvedURLSession() === explicit)
    }

    @Test func replaceRestLeavesThePreviousPoolUsableForAStragglerTask() throws {
        let original = OpenCodeURLSessions.rest
        OpenCodeURLSessions.replaceRest()
        defer { OpenCodeURLSessions.replaceRest() }

        // A caller that read `original` just before the swap still creates its task. An immediate
        // invalidation would raise NSGenericException here and crash the test process (#777).
        let url = try #require(URL(string: "http://127.0.0.1:9/"))
        let task = original.dataTask(with: url)
        task.cancel()
        #expect(OpenCodeURLSessions.rest !== original)
    }

    private func assertRestTimeouts(_ session: URLSession) {
        let configuration = session.configuration
        #expect(configuration.timeoutIntervalForRequest == 15)
        #expect(configuration.timeoutIntervalForResource == 60)
        #expect(configuration.waitsForConnectivity == false)
    }
}
