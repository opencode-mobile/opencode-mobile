import Foundation
import Observation
import OpenCodeKit
import VoiceEngineKit

/// App-wide state: whether first run is done, and the speech engine, which first run starts
/// downloading in the background.
@MainActor
@Observable
final class HexAppModel {
    enum Phase: Equatable {
        case launching
        case firstRun
        case main
    }

    private(set) var phase: Phase = .launching
    private(set) var firstRun: FirstRunModel?
    private(set) var main: HexMainModel?
    let voice = VoiceEngine()

    @ObservationIgnored private let store: any HexSetupStoring
    @ObservationIgnored private var speechTask: Task<Void, Never>?

    init(store: any HexSetupStoring = HexSetupStore()) {
        self.store = store
    }

    func launch() async {
        guard phase == .launching else { return }
        #if DEBUG
        // `-HexCodeDemoSetup YES` skips first run with placeholder values, for simulator checks of home.
        if UserDefaults.standard.bool(forKey: "HexCodeDemoSetup") {
            showMain(HexSetup(
                profile: ConnectionProfile(baseURL: "http://192.168.1.2:4096/", username: "opencode", password: "test-password", serverProtocol: .v2),
                serverVersion: "2.2.5",
                serverHostname: "mbp.local",
                project: ChosenProject(name: "hexcode", path: "/Users/example/Projects/hexcode"),
                model: ModelChoice(providerID: "anthropic", modelID: "claude-sonnet-5-5", variant: "high"),
                modelName: "Claude Sonnet 5.5"
            ))
            prepareSpeech()
            return
        }
        #endif
        if let setup = await store.load() {
            showMain(setup)
            prepareSpeech()
        } else {
            beginFirstRun()
        }
    }

    /// Forgets the server, project and model and starts first run again.
    func startOver() async {
        await store.clear()
        main = nil
        beginFirstRun()
    }

    /// The speech model downloads once, in the background; later calls only load it.
    func prepareSpeech() {
        guard speechTask == nil else { return }
        speechTask = Task { [voice] in await voice.prepare() }
    }

    private func beginFirstRun() {
        firstRun = FirstRunModel(
            store: store,
            onScanStarted: { [weak self] in self?.prepareSpeech() },
            onFinished: { [weak self] setup in
                self?.firstRun = nil
                self?.showMain(setup)
            }
        )
        phase = .firstRun
    }

    private func showMain(_ setup: HexSetup) {
        main = HexMainModel(setup: setup, home: HomeModel(recorder: voice), store: store)
        phase = .main
    }
}
