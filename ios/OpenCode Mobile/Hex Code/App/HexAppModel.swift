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
        // `-HexCodeLastDate yyyy-MM-dd` sets the date of the last session the app opened, to try the
        // new-day notice.
        if let lastDate = UserDefaults.standard.string(forKey: "HexCodeLastDate") {
            UserDefaults.standard.set(lastDate, forKey: DailySessionDefaults.key)
        }
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
        let firstRun = FirstRunModel(
            store: store,
            onScanStarted: { [weak self] in self?.prepareSpeech() },
            onFinished: { [weak self] setup in
                self?.firstRun = nil
                self?.showMain(setup)
            }
        )
        self.firstRun = firstRun
        phase = .firstRun
        #if DEBUG
        // `-HexCodePair <pairing link>` pairs as if the camera had read that code (simulators have
        // no camera).
        if let link = UserDefaults.standard.string(forKey: "HexCodePair") {
            firstRun.startScan()
            firstRun.scanned(link)
        }
        #endif
    }

    private func showMain(_ setup: HexSetup) {
        // `-HexCodeDemoSession <preset>` starts on a sample session (debug builds; see `TodaySessionDemo`).
        main = HexMainModel(
            setup: setup,
            home: HomeModel(recorder: TakeCapture(voice: voice)),
            samplePreset: TodaySessionDemo.launchPreset,
            store: store
        )
        phase = .main
    }
}
