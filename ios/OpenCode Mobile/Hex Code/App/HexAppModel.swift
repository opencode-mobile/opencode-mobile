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
        case main(HexSetup)
    }

    private(set) var phase: Phase = .launching
    private(set) var firstRun: FirstRunModel?
    let voice = VoiceEngine()

    @ObservationIgnored private let store: any HexSetupStoring
    @ObservationIgnored private var speechTask: Task<Void, Never>?

    init(store: any HexSetupStoring = HexSetupStore()) {
        self.store = store
    }

    func launch() async {
        guard phase == .launching else { return }
        if let setup = await store.load() {
            phase = .main(setup)
            prepareSpeech()
        } else {
            beginFirstRun()
        }
    }

    /// Forgets the server, project and model and starts first run again.
    func startOver() async {
        await store.clear()
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
                self?.phase = .main(setup)
            }
        )
        phase = .firstRun
    }
}
