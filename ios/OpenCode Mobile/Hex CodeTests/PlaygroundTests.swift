import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

/// The playground sample session (`PlaygroundScript`): a take plays a scripted run through the real
/// `TodaySession`, waiting for the approval and the question, and Stop ends it.
@MainActor
struct PlaygroundTests {
    @Test func aTakePlaysTheWholeRunWaitingForTheApprovalAndTheQuestion() async throws {
        let session = try await readySession()

        _ = try await session.send(PlaygroundScript.suggestion, images: [], delivery: .steer)
        await eventually { session.pendingItems.first?.kind == .approval }
        #expect(session.busySince != nil)
        #expect(session.sentMessage == PlaygroundScript.suggestion)
        #expect(session.todos.map(\.content) == PlaygroundScript.wholeRunTodos)

        // The approval waits: nothing moves on until it is answered.
        await settle()
        #expect(session.pendingItems.map(\.id) == [PlaygroundScript.approvalID])
        #expect(session.busySince != nil)

        let approval = try #require(session.pendingItems.first)
        #expect(await session.reply(to: approval, .once) == .sent)
        await eventually { session.pendingItems.first?.kind == .question }

        // So does the question.
        await settle()
        #expect(session.pendingItems.map(\.id) == [PlaygroundScript.questionID])
        #expect(session.busySince != nil)

        let question = try #require(session.pendingItems.first)
        #expect(await session.submit(question, answer: ["q0": .choices(["Open a pull request"])]) == .sent)
        await eventually { session.busySince == nil && session.summary.summary?.asCard.artifact != nil }

        let card = try #require(session.summary.summary?.asCard)
        #expect(card.title == "Sample change done")
        #expect(card.lines.map(\.text).joined().contains(PlaygroundScript.suggestion))
        #expect(card.lines.map(\.text).joined().contains("tests pass"))
        #expect(card.next?.contains("Open a pull request") == true)
        #expect(card.artifact == PlaygroundScript.artifact)
        #expect(session.todos.allSatisfy { $0.status == .completed })
        #expect(session.pendingItems.isEmpty)
        #expect(session.agentState == .idle)
    }

    @Test func aDenyCarriesOnAndTheCardSaysTheTestsDidNotRun() async throws {
        let session = try await readySession()
        _ = try await session.send("Make the header blue", images: [], delivery: .steer)
        await eventually { session.pendingItems.first?.kind == .approval }

        let approval = try #require(session.pendingItems.first)
        #expect(await session.reply(to: approval, .reject) == .sent)
        await eventually { session.pendingItems.first?.kind == .question }
        // The tests' item is cancelled, so it leaves the list.
        #expect(!session.todos.map(\.content).contains("Run `npm test`"))

        let question = try #require(session.pendingItems.first)
        #expect(await session.dismiss(question) == .sent)
        await eventually { session.busySince == nil && session.summary.summary?.asCard.artifact != nil }

        let card = try #require(session.summary.summary?.asCard)
        #expect(card.lines.map(\.text).joined().contains("Make the header blue"))
        #expect(card.lines.map(\.text).joined().contains("didn't run `npm test`"))
    }

    @Test func stopEndsTheRunAndALaterTakePlaysTheShortOne() async throws {
        let session = try await readySession()
        _ = try await session.send(PlaygroundScript.suggestion, images: [], delivery: .steer)
        await eventually { session.pendingItems.first?.kind == .approval }

        await session.stop()
        await eventually { session.busySince == nil && session.summary.summary?.asCard.title == "Stopped" }
        #expect(session.pendingItems.isEmpty)

        // The next take: busy, to-dos, then the card; no approval or question.
        _ = try await session.send("Add a footer", images: [], delivery: .steer)
        await eventually { session.busySince == nil && session.summary.summary?.asCard.title == "Sample change done" }
        #expect(session.pendingItems.isEmpty)
        #expect(session.todos.count == 3)
        #expect(session.summary.summary?.asCard.lines.map(\.text).joined().contains("Add a footer") == true)
    }

    /// Today's playground, opened, with its "Ready when you are" card and no waits between steps.
    private func readySession() async throws -> TodaySession {
        let session = PlaygroundScript.session(toasts: HexToaster(), sleep: { _ in await Task.yield() })
        await TodaySessionDemo.playground.start(session)
        await eventually { session.summary.summary != nil }
        #expect(session.summary.summary?.asCard.title == "Ready when you are")
        #expect(session.agentState == .idle)
        return session
    }

    /// Long enough for a step that isn't waiting to have run.
    private func settle() async {
        for _ in 0 ..< 50 { await Task.yield() }
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 400 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }
}
