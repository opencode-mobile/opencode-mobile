import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

// MARK: - Fakes

final class FakeProbing: ConnectionProbing, @unchecked Sendable {
    var outcome: Result<ServerProbeOutcome, ServerProbeError> = .success(ServerProbeOutcome(serverProtocol: .v2, serverVersion: "2.2.5"))
    var features = V2ServerFeatures(sessionGenerate: true)
    /// Unauthenticated health answers per host: the live server challenges with 401.
    var silentHosts: Set<String> = []
    private(set) var passwordsSent: [String] = []

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        try await probeServer(connection: connection).serverProtocol
    }

    func probeServer(connection: ServerConnection) async throws -> ServerProbeOutcome {
        passwordsSent.append(connection.password ?? "")
        if silentHosts.contains(connection.baseURL.host() ?? "") { throw ServerProbeError.transport("down") }
        if connection.password == nil { throw ServerProbeError.http(statusCode: 401, message: nil) }
        return try outcome.get()
    }

    func probeV2Features(connection _: ServerConnection) async throws -> V2ServerFeatures { features }
}

final class FakeRedeemer: PairingCodeRedeeming, @unchecked Sendable {
    var result: Result<String, PairingCodeRedeemError> = .success("session-token")
    private(set) var redeemed: [(baseURL: String, code: String)] = []

    func redeem(baseURL: String, code: String) async throws -> String {
        redeemed.append((baseURL, code))
        return try result.get()
    }
}

struct FakePairing: ServerPairing {
    var result: Result<PairedServer, PairingFailure>
    func pair(payload _: String) async -> Result<PairedServer, PairingFailure> { result }
}

struct FakeProjects: ProjectListing {
    var projects: [Project]
    func listProjects() async throws -> [Project] { projects }
}

struct FakeFilesystem: FilesystemRepository {
    var home = "/Users/example"
    var folders: [String: [String]] = [:]
    var repositories: Set<String> = []

    func getPaths() async throws -> ServerPaths { ServerPaths(home: home, directory: home, worktree: home) }

    func listDirectory(path: String) async throws -> DirectoryListing {
        guard let names = folders[path] else { throw FilesystemError.directoryNotFound }
        let nodes = names.map { DirectoryNode(name: $0, absolutePath: path + "/" + $0, isDirectory: true) }
        return DirectoryListing(folders: nodes, isGitRepository: repositories.contains(path))
    }
}

struct FakeModels: ModelRepository {
    var catalog: ModelSelectionCatalog
    func listModelOptions(directory _: String?) async throws -> ModelSelectionCatalog { catalog }
}

struct FakeTester: ModelTesting {
    var result: ModelTestState
    func test(_: ModelChoice, directory _: String?) async -> ModelTestState { result }
}

final class FakeStore: HexSetupStoring, @unchecked Sendable {
    private(set) var saved: HexSetup?
    func load() async -> HexSetup? { saved }
    func save(_ setup: HexSetup) async throws { saved = setup }
    func clear() async { saved = nil }
}

struct FakeServices: FirstRunServices {
    var pairing: any ServerPairing
    var projects = FakeProjects(projects: [])
    var filesystem = FakeFilesystem()
    var models = FakeModels(catalog: ModelPickerStateTests.catalog)
    var testResult: ModelTestState = .passed(seconds: 1)

    func projectListing(_: ServerConnection) -> any ProjectListing { projects }
    func filesystem(_: ServerConnection) -> any FilesystemRepository { filesystem }
    func models(_: ServerConnection) -> any ModelRepository { models }
    func tester(_: ServerConnection) -> any ModelTesting { FakeTester(result: testResult) }
}

let pairedServer = PairedServer(
    profile: ConnectionProfile(baseURL: "http://192.168.1.2:4096/", username: "opencode", password: "session-token", serverProtocol: .v2),
    version: "2.2.5"
)

func repository(_ path: String, activity: Int64?) -> Project {
    Project(id: path, worktree: path, displayName: ServerPathPolicy.name(path), activityAtEpochMs: activity, isRepository: true)
}

@MainActor
func waitUntil(_ condition: () -> Bool) async -> Bool {
    for _ in 0 ..< 200 {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return condition()
}

// MARK: - Pairing

struct ServerPairingTests {
    static let payload = #"{"code":"Abc123_-","urls":["http://127.0.0.1:4096","http://192.168.1.2:4096","http://100.64.0.3:4096"]}"#

    @Test func redeemsOnTheTailscaleHostAndKeepsEveryAddress() async throws {
        let probing = FakeProbing()
        let redeemer = FakeRedeemer()
        var pairing = OpenCodeServerPairing(probing: probing, redeemer: redeemer)
        pairing.hostname = { _ in nil }
        let server = try await pairing.pair(payload: Self.payload).get()
        #expect(redeemer.redeemed.count == 1)
        #expect(redeemer.redeemed.first?.baseURL == "http://100.64.0.3:4096/")
        #expect(redeemer.redeemed.first?.code == "Abc123_-")
        #expect(server.profile.baseURL == "http://100.64.0.3:4096/")
        #expect(server.profile.password == "session-token")
        #expect(server.profile.username == "opencode")
        #expect(server.profile.serverURLs == ["http://192.168.1.2:4096/", "http://100.64.0.3:4096/"])
        #expect(server.profile.serverProtocol == .v2)
        #expect(server.version == "2.2.5")
        // The token only ever goes to the chosen host.
        #expect(probing.passwordsSent.filter { $0 == "session-token" }.count == 1)
    }

    @Test func namesTheServerFromItsHostname() async throws {
        var pairing = OpenCodeServerPairing(probing: FakeProbing(), redeemer: FakeRedeemer())
        pairing.hostname = { _ in "Example-MacBook-Pro.local" }
        let server = try await pairing.pair(payload: Self.payload).get()
        #expect(server.hostname == "Example-MacBook-Pro.local")
        #expect(server.displayName == "Bold Quokka")
        pairing.hostname = { _ in nil }
        let unnamed = try await pairing.pair(payload: Self.payload).get()
        // No hostname: the name comes from the code's first address.
        #expect(unnamed.displayName == ServerName.name(seed: "http://192.168.1.2:4096"))
    }

    @Test func rejectsWhatIsNotAPairingCode() async {
        let pairing = OpenCodeServerPairing(probing: FakeProbing(), redeemer: FakeRedeemer())
        #expect(await pairing.pair(payload: "https://example.com") == .failure(.notAPairingCode))
        #expect(await pairing.pair(payload: #"{"code":"x","urls":["http://127.0.0.1:4096"]}"#) == .failure(.onlyLocalAddresses))
    }

    @Test func expiredCode() async {
        let redeemer = FakeRedeemer()
        redeemer.result = .failure(.expired)
        let pairing = OpenCodeServerPairing(probing: FakeProbing(), redeemer: redeemer)
        #expect(await pairing.pair(payload: Self.payload) == .failure(.codeExpired))
    }

    @Test func nothingAnswers() async {
        let probing = FakeProbing()
        probing.silentHosts = ["192.168.1.2", "100.64.0.3"]
        let pairing = OpenCodeServerPairing(probing: probing, redeemer: FakeRedeemer())
        #expect(await pairing.pair(payload: Self.payload) == .failure(.unreachable))
    }

    @Test func serverChecksShowOnlyOnFailure() async {
        let probing = FakeProbing()
        let pairing = OpenCodeServerPairing(probing: probing, redeemer: FakeRedeemer())
        probing.outcome = .success(ServerProbeOutcome(serverProtocol: .v1, serverVersion: "1.19.5"))
        #expect(await pairing.pair(payload: Self.payload) == .failure(.notOpenCode2))
        probing.outcome = .success(ServerProbeOutcome(serverProtocol: .v2, serverVersion: "2.2.2"))
        #expect(await pairing.pair(payload: Self.payload) == .failure(.tooOld(version: "2.2.2")))
        probing.outcome = .success(ServerProbeOutcome(serverProtocol: .v2, serverVersion: "2.3.0"))
        probing.features = V2ServerFeatures(sessionGenerate: false)
        #expect(await pairing.pair(payload: Self.payload) == .failure(.missingRoutes))
    }

    @Test func versionComparison() {
        #expect(OpenCodeServerPairing.meetsMinimum("2.2.5"))
        #expect(OpenCodeServerPairing.meetsMinimum("v2.10.0"))
        #expect(OpenCodeServerPairing.meetsMinimum("2.2.5-beta.1"))
        #expect(OpenCodeServerPairing.meetsMinimum("3"))
        #expect(!OpenCodeServerPairing.meetsMinimum("2.2.4"))
        #expect(!OpenCodeServerPairing.meetsMinimum("2.1.9"))
        // A local build has no release number to judge.
        #expect(OpenCodeServerPairing.meetsMinimum("local"))
    }
}

// MARK: - Project selection

@MainActor
struct ProjectPickerModelTests {
    @Test func showsUpToFiveRepositoriesNewestFirst() async {
        let projects = (1 ... 7).map { repository("/Users/example/Projects/p\($0)", activity: Int64($0)) }
            + [Project(id: "plain", worktree: "/Users/example/notes", displayName: "notes")]
        let picker = ProjectPickerModel(projects: FakeProjects(projects: projects), filesystem: FakeFilesystem())
        await picker.load()
        #expect(picker.sheet.suggestionsResolved)
        #expect(picker.sheet.suggestions.map(\.name) == ["p7", "p6", "p5", "p4", "p3"])
        #expect(picker.suggestion(at: "/Users/example/Projects/p6") == ChosenProject(name: "p6", path: "/Users/example/Projects/p6"))
    }

    @Test func browseStartsAtHomeAndUsesTheFolderInView() async {
        let filesystem = FakeFilesystem(
            folders: [
                "/Users/example": ["Projects", ".cache"],
                "/Users/example/Projects": ["hexcode", "notes"],
                "/Users/example/Projects/hexcode": ["prototype"],
            ],
            repositories: ["/Users/example/Projects/hexcode"]
        )
        let picker = ProjectPickerModel(projects: FakeProjects(projects: []), filesystem: filesystem, listingDebounce: .zero)
        await picker.load()
        await picker.browse()
        #expect(picker.isBrowsing)
        #expect(picker.sheet.entries.map(\.name) == ["Projects"])
        // Home itself can be used, under its own title.
        #expect(picker.folderToUse == ChosenProject(name: "example", path: "/Users/example"))
        await picker.openFolder("/Users/example/Projects")
        #expect(picker.sheet.entries.map(\.name) == ["hexcode", "notes"])
        await picker.openFolder("/Users/example/Projects/hexcode")
        #expect(picker.sheet.barTarget?.isGitRepository == true)
        #expect(picker.folderToUse == ChosenProject(name: "hexcode", path: "/Users/example/Projects/hexcode"))
        await picker.up()
        #expect(picker.folderToUse?.path == "/Users/example/Projects")
    }

    @Test func rowSummariesArriveTogetherAndKeepTheRowOrder() async {
        let filesystem = FakeFilesystem(
            folders: [
                "/Users/example": ["b", "a", "c"],
                "/Users/example/a": ["x"],
                "/Users/example/b": ["x", "y"],
                "/Users/example/c": [],
            ],
            repositories: ["/Users/example/b"]
        )
        let picker = ProjectPickerModel(projects: FakeProjects(projects: []), filesystem: filesystem, listingDebounce: .zero)
        await picker.load()
        await picker.browse()
        let order = picker.sheet.entries.map(\.name)
        #expect(picker.sheet.entries.allSatisfy { $0.folderCount == nil })
        for entry in picker.sheet.entries { picker.rowAppeared(entry.path) }
        #expect(await waitUntil { picker.sheet.entries.allSatisfy { $0.folderCount != nil } })
        #expect(picker.sheet.entries.map(\.name) == order)
        let byName = Dictionary(uniqueKeysWithValues: picker.sheet.entries.map { ($0.name, $0) })
        #expect(byName["a"]?.folderCount == 1)
        #expect(byName["b"]?.folderCount == 2)
        #expect(byName["c"]?.folderCount == 0)
        #expect(byName["b"]?.isGitRepository == true)
        #expect(byName["a"]?.isGitRepository == false)
    }

    @Test func aMissingFolderCannotBeUsed() async {
        let filesystem = FakeFilesystem(folders: ["/Users/example": []])
        let picker = ProjectPickerModel(projects: FakeProjects(projects: []), filesystem: filesystem, listingDebounce: .zero)
        await picker.load()
        await picker.browse()
        await picker.openFolder("/Users/example/gone")
        #expect(picker.sheet.pathNotFound)
        #expect(picker.folderToUse == nil)
    }

    @Test func closingTheBrowserReturnsToTheList() async {
        let picker = ProjectPickerModel(projects: FakeProjects(projects: []), filesystem: FakeFilesystem(folders: ["/Users/example": []]))
        await picker.load()
        await picker.browse()
        picker.closeBrowser()
        #expect(!picker.isBrowsing)
    }
}

// MARK: - The whole first run

@MainActor
struct FirstRunModelTests {
    @Test func welcomeScanProjectModelStart() async throws {
        let store = FakeStore()
        var scanStarted = 0
        var finished: HexSetup?
        let services = FakeServices(
            pairing: FakePairing(result: .success(pairedServer)),
            projects: FakeProjects(projects: [repository("/Users/example/Projects/hexcode", activity: 1)])
        )
        let model = FirstRunModel(
            services: services, store: store, connectedPause: .zero,
            onScanStarted: { scanStarted += 1 }, onFinished: { finished = $0 }
        )
        #expect(model.step == .welcome)
        model.startScan()
        #expect(model.step == .scan)
        #expect(scanStarted == 1)
        model.scanned("payload")
        #expect(model.scan == .connecting)
        #expect(await waitUntil { model.step == .project })
        let picker = try #require(model.projectPicker)
        #expect(await waitUntil { picker.sheet.suggestionsResolved })
        let chosen = try #require(picker.suggestion(at: "/Users/example/Projects/hexcode"))
        await model.pickProject(chosen)
        #expect(model.step == .model)
        let page = try #require(model.modelPage)
        let models = try #require(page.picker)
        #expect(!models.canStart)

        // Start is not possible before the test passes.
        await model.start()
        #expect(finished == nil)

        page.test()
        #expect(await waitUntil { page.picker?.canStart == true })
        await model.start()
        let setup = try #require(finished)
        #expect(setup.project == chosen)
        #expect(setup.model == ModelChoice(providerID: "anthropic", modelID: "claude-opus-5-5", variant: nil))
        #expect(setup.profile == pairedServer.profile)
        #expect(store.saved == setup)
    }

    @Test func aFailedTestKeepsStartAway() async throws {
        let services = FakeServices(pairing: FakePairing(result: .success(pairedServer)), testResult: .failed("No API key"))
        let model = FirstRunModel(services: services, store: FakeStore(), connectedPause: .zero)
        model.startScan()
        model.scanned("payload")
        #expect(await waitUntil { model.step == .project })
        await model.pickProject(ChosenProject(path: "/Users/example/p"))
        let page = try #require(model.modelPage)
        page.test()
        #expect(await waitUntil { page.picker?.currentTest == .failed("No API key") })
        #expect(page.passedChoice == nil)
    }

    @Test func pairingFailureStaysOnScanUntilScanAgain() async {
        let model = FirstRunModel(services: FakeServices(pairing: FakePairing(result: .failure(.codeExpired))), store: FakeStore())
        model.startScan()
        let attempt = model.scanAttempt
        model.scanned("payload")
        #expect(await waitUntil { model.scan == .failed(.codeExpired) })
        #expect(model.step == .scan)
        // A second read of the same attempt is ignored.
        model.scanned("payload")
        #expect(model.scan == .failed(.codeExpired))
        model.scanAgain()
        #expect(model.scan == .scanning)
        #expect(model.scanAttempt == attempt + 1)
    }
}

// MARK: - The server's name

struct ServerNameTests {
    @Test func knownHostnamesGiveKnownNames() {
        #expect(ServerName.name(hostname: "Example-MacBook-Pro.local", fallbackAddress: "x") == "Bold Quokka")
        #expect(ServerName.name(hostname: "mbp", fallbackAddress: "x") == "Spry Seal")
        #expect(ServerName.name(hostname: "opencode-dev", fallbackAddress: "x") == "Quiet Lynx")
    }

    @Test func caseAndDotLocalDoNotChangeTheName() {
        #expect(ServerName.name(hostname: "MBP.local", fallbackAddress: "x") == "Spry Seal")
        #expect(ServerName.name(hostname: "  ", fallbackAddress: "mbp") == "Spry Seal")
        #expect(ServerName.name(hostname: nil, fallbackAddress: "mbp") == "Spry Seal")
    }

    @Test func fnv1aMatchesTheReference() {
        #expect(ServerName.fnv1a("") == 0x811C_9DC5)
        #expect(ServerName.fnv1a("a") == 0xE40C_292C)
    }
}
