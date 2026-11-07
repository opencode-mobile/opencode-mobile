import Foundation
import Observation
import WebKit
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
    let voice: VoiceEngine
    /// The speech model; it starts loading with the process when it is already on the phone.
    let speech: SpeechModel

    @ObservationIgnored private let store: any HexSetupStoring
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var speechTask: Task<Void, Never>?

    init(store: any HexSetupStoring = HexSetupStore(), defaults: UserDefaults = .standard) {
        self.store = store
        self.defaults = defaults
        // Prepared images live only as long as their draft; any left from an earlier run go.
        ImagePreparer.emptyCache()
        let voice = VoiceEngine()
        self.voice = voice
        speech = SpeechModel(voice: voice)
        // Load at process start (app plan, "The speech model"). On first run the download still
        // starts at the scan step.
        if VoiceEngine.hasDownloadedModel { prepareSpeech() }
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

    /// Forget this computer: the server and its password, the project and model, today's
    /// session's memory, card and Ask history, and the in-app browser's website data. First run
    /// starts again at Welcome.
    func startOver() async {
        main?.close()
        await store.clear()
        ForgetComputer.clearSessionData(defaults)
        await Self.clearWebsiteData()
        main = nil
        beginFirstRun()
    }

    /// Kept between opens so a dev server's sign-in survives; Forget clears it.
    static func clearWebsiteData() async {
        let store = WKWebsiteDataStore.default()
        await store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)
    }

    /// Pair again found another computer and Switch was confirmed: first run's project and model
    /// pages there, then today's session there. Backing out returns to the computer in use, which
    /// stays saved until Start.
    private func switchServer(to server: PairedServer) {
        let firstRun = FirstRunModel(
            store: store,
            paired: server,
            onCancel: { [weak self] in
                guard let self, self.main != nil else { return }
                self.firstRun = nil
                self.phase = .main
            },
            onFinished: { [weak self] setup in
                guard let self else { return }
                // The old computer's session memory, card and Ask history don't apply there.
                self.main?.close()
                ForgetComputer.clearSessionData(self.defaults)
                self.firstRun = nil
                self.showMain(setup)
            }
        )
        self.firstRun = firstRun
        phase = .firstRun
        Task { await firstRun.showPairedProjects() }
    }

    /// The speech model downloads once, in the background; later calls only load it.
    func prepareSpeech() {
        speech.start()
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
        let main = HexMainModel(
            setup: setup,
            home: HomeModel(recorder: TakeCapture(pauses: { [voice] in voice.makePauseFinder() }), speech: speech),
            samplePreset: TodaySessionDemo.launchPreset,
            store: store
        )
        main.onSwitchServer = { [weak self] server in self?.switchServer(to: server) }
        self.main = main
        phase = .main
    }
}
