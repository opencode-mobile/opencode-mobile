import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionChatShellsTests {
    private let running = AgentShell(id: "sh_1", sessionID: "ses-1", command: "sleep 60", status: .running, startedAtMs: 1, canStop: true)

    private func makeRegion(_ repository: any AgentShellRepository) -> SessionChatShells {
        SessionChatShells(sessionID: "ses-1", worktree: "/tmp/project", repository: repository, nowMs: { 42 })
    }

    @Test func openShellThatLeavesTheListEndsAsExited() async throws {
        let repository = ScriptedShellRepository()
        let region = makeRegion(repository)
        region.start()
        repository.yieldList([running])
        try #require(await waitUntil { region.state.shells.count == 1 })
        region.openOutput(shellID: "sh_1")

        repository.yieldList([])

        try #require(await waitUntil { region.state.shells.isEmpty })
        let output = try #require(region.state.output)
        #expect(output.shell.status == .exited(code: nil))
        #expect(output.shell.finishedAtMs == 42)
        #expect(!output.canStop)
        #expect(!output.isStopping)
        region.stop()
    }

    @Test func openShellThatLeavesTheListWhileStoppingEndsAsStopped() async throws {
        let repository = ScriptedShellRepository()
        let region = makeRegion(repository)
        region.start()
        repository.yieldList([running])
        try #require(await waitUntil { region.state.shells.count == 1 })
        region.openOutput(shellID: "sh_1")
        region.stopShell(shellID: "sh_1")
        try #require(await waitUntil { repository.stopCount == 1 })
        #expect(region.state.output?.isStopping == true)

        repository.yieldList([])

        try #require(await waitUntil { region.state.output?.shell.status == .stopped })
        #expect(region.state.output?.isStopping == false)
        #expect(region.state.output?.shell.finishedAtMs == 42)
        region.stop()
    }

    @Test func outputThatEndsOnItsOwnEndsTheOpenShell() async throws {
        let repository = ScriptedShellRepository()
        let region = makeRegion(repository)
        region.start()
        repository.yieldList([running])
        try #require(await waitUntil { region.state.shells.count == 1 })
        region.openOutput(shellID: "sh_1")
        let watch = Task { await region.watchOutput() }

        repository.yieldOutput(AgentShellOutput(text: "last\n", isTruncated: false))
        repository.finishOutput()
        await watch.value

        #expect(region.state.output?.shell.status == .exited(code: nil))
        #expect(region.state.output?.output.text == "last\n")
        #expect(region.state.output?.canStop == false)
        region.stop()
    }

    @Test func listThatFinishesCanStartAgain() async throws {
        let repository = ScriptedShellRepository()
        let region = makeRegion(repository)
        region.start()
        repository.finishList()
        // `start()` is a no-op while the first list still runs; once it ended it subscribes again.
        try #require(await waitUntil {
            region.start()
            return repository.listSubscriptions == 2
        })
        repository.yieldList([running])

        try #require(await waitUntil { region.state.shells == [self.running] })
        region.stop()
    }

    @Test func remotesThatDoNotReadTheTranscriptNeverSubscribe() async throws {
        let region = makeRegion(UnavailableAgentShellRepository())
        region.start()
        region.transcriptChanged([])
        region.openOutput(shellID: "none")
        await region.watchOutput()
        try await Task.sleep(for: .milliseconds(50))

        #expect(region.transcriptSubscriberCount == 0)
        region.stop()
    }

    @Test func transcriptSubscriptionEndsWithItsReader() async throws {
        let region = makeRegion(DefaultAgentShellRepository(
            v1: V1AgentShellRemote(),
            v2: UnavailableAgentShellRemote(),
            activeConnectionManager: nil
        ))
        region.start()
        try #require(await waitUntil { region.transcriptSubscriberCount == 1 })

        region.stop()

        try #require(await waitUntil { region.transcriptSubscriberCount == 0 })
    }

    private func waitUntil(timeoutMs: Int = 3_000, _ predicate: @MainActor () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }
}

/// Each `shells` call opens a fresh list stream; output and Stop are scripted.
final class ScriptedShellRepository: AgentShellRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var listContinuation: AsyncStream<[AgentShell]>.Continuation?
    private var _listSubscriptions = 0
    private var _stopCount = 0
    private let outputs = AsyncStream.makeStream(of: AgentShellOutput.self)

    var listSubscriptions: Int { lock.withLock { _listSubscriptions } }
    var stopCount: Int { lock.withLock { _stopCount } }

    func yieldList(_ shells: [AgentShell]) { _ = lock.withLock { listContinuation }?.yield(shells) }
    func finishList() { lock.withLock { listContinuation }?.finish() }
    func yieldOutput(_ output: AgentShellOutput) { outputs.continuation.yield(output) }
    func finishOutput() { outputs.continuation.finish() }

    func shells(sessionID _: String, directory _: String, transcript _: @escaping AgentShellTranscript) -> AsyncStream<[AgentShell]> {
        let (stream, continuation) = AsyncStream.makeStream(of: [AgentShell].self)
        lock.withLock {
            listContinuation = continuation
            _listSubscriptions += 1
        }
        return stream
    }

    func output(shell _: AgentShell, directory _: String, transcript _: @escaping AgentShellTranscript) -> AsyncStream<AgentShellOutput> {
        outputs.stream
    }

    func stop(shell _: AgentShell, directory _: String) async throws {
        lock.withLock { _stopCount += 1 }
    }
}
