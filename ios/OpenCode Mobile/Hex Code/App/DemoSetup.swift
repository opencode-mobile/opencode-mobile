import Foundation
import OpenCodeKit

/// Placeholder choices that are never saved: a made-up computer, project and model. The sample
/// session from Welcome runs on them, and so does `-HexCodeDemoSetup YES` (debug builds), which
/// skips first run for simulator checks of home. The same values as Android's `DemoSetup`.
enum DemoSetup {
    static let setup = HexSetup(
        profile: ConnectionProfile(baseURL: "http://192.168.1.2:4096/", username: "opencode", password: "test-password", serverProtocol: .v2),
        serverVersion: "2.2.5",
        serverHostname: "mbp.local",
        project: ChosenProject(name: "hexcode", path: "/Users/example/Projects/hexcode"),
        model: ModelChoice(providerID: "anthropic", modelID: "claude-sonnet-5-5", variant: "high"),
        modelName: "Claude Sonnet 5.5"
    )
}

/// A setup store that keeps nothing, for the sample session opened from Welcome.
struct NoSetupStore: HexSetupStoring {
    func load() async -> HexSetup? { nil }
    func save(_ setup: HexSetup) async throws {}
    func clear() async {}
}
