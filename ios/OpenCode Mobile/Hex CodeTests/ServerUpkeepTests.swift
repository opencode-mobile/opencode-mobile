import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

/// The server checks at each setup (app plan, "Settings and finish (M6)" › Server, and "Testing" ›
/// Server and Sign-in): the version from `/api/info`, the address in use, and the sign-in renewal.
@MainActor
final class FakeServerChecks: ServerChecking, @unchecked Sendable {
    var info: [String: ServerInfoResult] = [:]
    var winner: String?
    var renewed: String? = "tok_new"
    private(set) var infoCalls: [String] = []
    private(set) var renewCalls: [String] = []
    private(set) var resolveCalls = 0

    nonisolated init() {}

    func info(_ connection: ServerConnection) async -> ServerInfoResult {
        let address = connection.baseURL.absoluteString
        infoCalls.append(address)
        return info[address] ?? .unreachable
    }

    func renew(_ connection: ServerConnection) async throws -> String {
        renewCalls.append(connection.password ?? "")
        guard let renewed else { throw URLError(.timedOut) }
        return renewed
    }

    func resolveAddress(_ profile: ConnectionProfile) async -> String? {
        resolveCalls += 1
        return winner
    }
}

@MainActor
struct ServerUpkeepTests {
    private let now = Date(timeIntervalSince1970: 1_793_966_400)
    private let lan = "http://192.168.0.47:4196/"
    private let tailnet = "http://100.76.89.86:4196/"

    private func setup(signedDaysAgo: Double?, version: String? = "2.2.4") -> HexSetup {
        HexSetup(
            profile: ConnectionProfile(baseURL: lan, serverURLs: [lan, tailnet], username: "opencode", password: "tok_old", serverProtocol: .v2),
            serverVersion: version,
            serverHostname: "mbp.local",
            project: ChosenProject(name: "hexcode", path: "/p"),
            model: ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil),
            signedInAt: signedDaysAgo.map { now - $0 * 86400 }
        )
    }

    @Test func theRenewalRule() {
        #expect(SignIn.needsRenewal(signedInAt: now - 8 * 86400, now: now))
        #expect(!SignIn.needsRenewal(signedInAt: now - 6 * 86400, now: now))
        // Paired before the date was kept: renewed once, then dated.
        #expect(SignIn.needsRenewal(signedInAt: nil, now: now))
    }

    @Test func theVersionRefreshesFromInfo() async {
        let checks = FakeServerChecks()
        checks.info[lan] = .ok(version: "2.2.5")
        let (next, outcome) = await ServerUpkeep.run(setup(signedDaysAgo: 1), checks: checks, now: now)
        #expect(outcome == .ready)
        #expect(next.serverVersion == "2.2.5")
        #expect(next.serverHostname == "mbp.local")
        #expect(checks.renewCalls.isEmpty)
    }

    @Test func aSignInEightDaysOldIsRenewed() async {
        let checks = FakeServerChecks()
        checks.info[lan] = .ok(version: "2.2.5")
        let (next, _) = await ServerUpkeep.run(setup(signedDaysAgo: 8), checks: checks, now: now)
        #expect(checks.renewCalls == ["tok_old"])
        #expect(next.profile.password == "tok_new")
        #expect(next.signedInAt == now)
    }

    @Test func aSignInSixDaysOldIsnt() async {
        let checks = FakeServerChecks()
        checks.info[lan] = .ok(version: "2.2.5")
        let (next, _) = await ServerUpkeep.run(setup(signedDaysAgo: 6), checks: checks, now: now)
        #expect(checks.renewCalls.isEmpty)
        #expect(next.profile.password == "tok_old")
    }

    @Test func aFailedRenewalKeepsTheOldToken() async {
        let checks = FakeServerChecks()
        checks.info[lan] = .ok(version: "2.2.5")
        checks.renewed = nil
        let old = setup(signedDaysAgo: 8)
        let (next, outcome) = await ServerUpkeep.run(old, checks: checks, now: now)
        #expect(outcome == .ready)
        #expect(next.profile.password == "tok_old")
        #expect(next.signedInAt == old.signedInAt)
    }

    @Test func a401IsSignedOutAndNothingIsRenewed() async {
        let checks = FakeServerChecks()
        checks.info[lan] = .signedOut
        let (_, outcome) = await ServerUpkeep.run(setup(signedDaysAgo: 40), checks: checks, now: now)
        #expect(outcome == .signedOut)
        #expect(checks.renewCalls.isEmpty)
    }

    @Test func anUnreachableAddressSwitchesToOneThatAnswers() async {
        let checks = FakeServerChecks()
        checks.info[tailnet] = .ok(version: "2.2.5")
        checks.winner = tailnet
        let (next, outcome) = await ServerUpkeep.run(setup(signedDaysAgo: 1), checks: checks, now: now)
        #expect(outcome == .ready)
        #expect(next.profile.baseURL == tailnet)
        #expect(checks.infoCalls == [lan, tailnet])
    }

    @Test func nothingAnsweringLeavesTheSetupAsItWas() async {
        let checks = FakeServerChecks()
        let old = setup(signedDaysAgo: 8)
        let (next, outcome) = await ServerUpkeep.run(old, checks: checks, now: now)
        #expect(outcome == .ready)
        #expect(next == old)
        #expect(checks.renewCalls.isEmpty)
    }

    @Test func aWorkingAddressIsntProbed() async {
        let checks = FakeServerChecks()
        checks.info[lan] = .ok(version: "2.2.5")
        _ = await ServerUpkeep.run(setup(signedDaysAgo: 1), checks: checks, now: now)
        #expect(checks.resolveCalls == 0)
    }
}

/// A `401` while setting up today's session is Signed out, with Pair again (app plan, "Signed out").
@MainActor
struct SignedOutTests {
    @Test func a401FromTheListIsSignedOut() async {
        let server = FakeSessionServer()
        server.listError = SessionStateAPIError.httpStatus(401, "Authentication required")
        let setup = DailySessionSetup(service: server, memory: FakeMemory("2026-11-05"))
        #expect(await setup.open(today: "2026-11-05", directory: "/p", model: ModelChoice(providerID: "o", modelID: "m", variant: nil)) == .signedOut)
    }

    @Test func todaysSessionShowsSignedOutAndOpensAgainAfterPairing() async {
        let server = FakeSessionServer()
        var signedOut = true
        let session = TodaySession(
            setup: DailySessionSetup(service: server, memory: FakeMemory("2026-11-05")),
            summary: StatusSummaryModel(),
            directory: { "/p" },
            model: { ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil) },
            toasts: HexToaster(),
            api: server,
            now: { Date(timeIntervalSince1970: 1_793_880_000) },
            timeZone: TimeZone(identifier: "UTC")!
        )
        session.serverCheck = { signedOut ? .signedOut : .ready }
        await session.open()
        #expect(session.setup == .signedOut)
        #expect(session.agentState == nil)
        #expect(server.calls.isEmpty)
        // Pair again replaced the sign-in; today's session opens.
        signedOut = false
        await session.open()
        #expect(session.sessionID == "ses_new1")
    }
}

/// A Keychain stand-in that keeps passwords in memory.
final class MemoryCredentials: SecureCredentialStore, @unchecked Sendable {
    private let lock = NSLock()
    private var passwords: [String: String] = [:]

    var isEmpty: Bool {
        lock.lock()
        defer { lock.unlock() }
        return passwords.isEmpty
    }

    func readPassword(serverBaseURL: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return passwords[serverBaseURL]
    }

    func writePassword(serverBaseURL: String, password: String) throws {
        lock.lock()
        passwords[serverBaseURL] = password
        lock.unlock()
    }

    func deletePassword(serverBaseURL: String) {
        lock.lock()
        passwords[serverBaseURL] = nil
        lock.unlock()
    }

    func clearAll() {
        lock.lock()
        passwords = [:]
        lock.unlock()
    }
}

/// Forget this computer (app plan, "Forget this computer"; "Testing" › Server).
struct ForgetComputerTests {
    private func suite() -> UserDefaults {
        let name = "hexcode.tests.forget.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    private let setup = HexSetup(
        profile: ConnectionProfile(baseURL: "http://192.168.0.47:4196/", serverURLs: ["http://192.168.0.47:4196/"], username: "opencode", password: "tok_secret", serverProtocol: .v2),
        serverVersion: "2.2.5",
        serverHostname: "mbp.local",
        project: ChosenProject(name: "hexcode", path: "/p"),
        model: ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil),
        signedInAt: Date()
    )

    @Test func forgetDeletesThePasswordFromTheKeychain() async throws {
        let defaults = suite()
        let credentials = MemoryCredentials()
        let store = HexSetupStore(defaults: defaults, profiles: UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: credentials))
        try await store.save(setup)
        #expect(!credentials.isEmpty)
        #expect(await store.load()?.profile.password == "tok_secret")
        #expect(await store.load()?.signedInAt != nil)
        await store.clear()
        #expect(credentials.isEmpty)
        #expect(await store.load() == nil)
    }

    @Test @MainActor func forgetClearsTheSessionMemoryTheCardAndTheAskHistory() {
        let defaults = suite()
        let memory = DailySessionDefaults(defaults: defaults)
        memory.lastOpenedDay = "2026-11-07"
        memory.lastSessionID = "ses_a"
        SummaryDefaults(defaults: defaults).save(SavedSummary(sessionID: "ses_a", summary: .card(StatusCard(title: "T", now: "N")), updatedAt: Date()))
        AskDefaults(defaults: defaults).save(SavedAsks(sessionID: "ses_a", shown: nil, history: []))
        defaults.set("dark", forKey: HexAppearance.key)
        ForgetComputer.clearSessionData(defaults)
        #expect(memory.lastOpenedDay == nil && memory.lastSessionID == nil)
        #expect(SummaryDefaults(defaults: defaults).load() == nil)
        #expect(AskDefaults(defaults: defaults).load() == nil)
        // Appearance stays.
        #expect(defaults.string(forKey: HexAppearance.key) == "dark")
    }

    @Test func pairingAgainSavesOneProfileAndDeletesTheOldPassword() async throws {
        let defaults = suite()
        let credentials = MemoryCredentials()
        let store = HexSetupStore(defaults: defaults, profiles: UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: credentials))
        try await store.save(setup)
        let paired = PairedServer(
            profile: ConnectionProfile(baseURL: "http://100.76.89.86:4196/", serverURLs: ["http://100.76.89.86:4196/"], username: "opencode", password: "tok_fresh", serverProtocol: .v2),
            version: "2.2.5",
            hostname: "MBP.local"
        )
        let next = PairAgain.replacing(setup, with: paired, now: Date())
        try await store.save(next)
        let loaded = await store.load()
        #expect(loaded?.profile.password == "tok_fresh")
        #expect(loaded?.project == setup.project)
        #expect(loaded?.model == setup.model)
        #expect(!credentials.isEmpty)
        #expect(credentials.readPassword(serverBaseURL: setup.profile.id) == nil)
    }
}

/// Pair again: the same computer replaces the sign-in; another asks first.
struct PairAgainTests {
    private let setup = HexSetup(
        profile: ConnectionProfile(baseURL: "http://192.168.0.47:4196/", serverURLs: ["http://192.168.0.47:4196/"], username: "opencode", password: "tok_old", serverProtocol: .v2),
        serverVersion: "2.2.4",
        serverHostname: "Ivans-MacBook-Pro.local",
        project: ChosenProject(name: "hexcode", path: "/p"),
        model: ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil)
    )

    private func paired(_ hostname: String?, address: String = "http://100.76.89.86:4196/") -> PairedServer {
        PairedServer(
            profile: ConnectionProfile(baseURL: address, serverURLs: [address], username: "opencode", password: "tok_new", serverProtocol: .v2),
            version: "2.2.5",
            hostname: hostname
        )
    }

    @Test func theSameHostnameIsTheSameComputer() {
        #expect(PairAgain.isSameComputer(paired("ivans-macbook-pro"), as: setup))
        #expect(PairAgain.isSameComputer(paired("Ivans-MacBook-Pro.local"), as: setup))
        #expect(!PairAgain.isSameComputer(paired("mbp.local"), as: setup))
    }

    @Test func theSameComputerKeepsProjectModelAndReplacesTheRest() {
        let now = Date(timeIntervalSince1970: 1_793_966_400)
        let next = PairAgain.replacing(setup, with: paired("Ivans-MacBook-Pro.local"), now: now)
        #expect(next.profile.password == "tok_new")
        #expect(next.profile.serverURLs == ["http://100.76.89.86:4196/"])
        #expect(next.project == setup.project && next.model == setup.model)
        #expect(next.serverVersion == "2.2.5")
        #expect(next.signedInAt == now)
        #expect(next.serverName == setup.serverName)
    }

    @Test @MainActor func anotherComputerAsksBeforeSwitching() async {
        struct Pairing: ServerPairing {
            let server: PairedServer
            func pair(payload: String) async -> Result<PairedServer, PairingFailure> { .success(server) }
        }
        var same: PairedServer?
        var switched: PairedServer?
        let model = PairAgainModel(
            current: setup,
            pairing: Pairing(server: paired("studio.local")),
            connectedPause: .zero,
            onSameComputer: { same = $0 },
            onSwitch: { switched = $0 }
        )
        model.scanned("opencode://pair")
        for _ in 0 ..< 100 where model.switchCandidate == nil { try? await Task.sleep(for: .milliseconds(5)) }
        #expect(model.switchCandidate?.hostname == "studio.local")
        #expect(same == nil && switched == nil)
        model.confirmSwitch()
        #expect(switched?.hostname == "studio.local")
    }

    @Test @MainActor func theSameComputerDoesntAsk() async {
        struct Pairing: ServerPairing {
            let server: PairedServer
            func pair(payload: String) async -> Result<PairedServer, PairingFailure> { .success(server) }
        }
        var same: PairedServer?
        let model = PairAgainModel(
            current: setup,
            pairing: Pairing(server: paired("ivans-macbook-pro.local")),
            connectedPause: .zero,
            onSameComputer: { same = $0 },
            onSwitch: { _ in }
        )
        model.scanned("opencode://pair")
        for _ in 0 ..< 100 where same == nil { try? await Task.sleep(for: .milliseconds(5)) }
        #expect(same?.profile.password == "tok_new")
        #expect(model.switchCandidate == nil)
    }
}
