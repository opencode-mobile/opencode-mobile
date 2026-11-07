import Foundation
import Observation
import OpenCodeKit
import SwiftUI

/// Pair again (Server details, or Signed out): first run's scan screen as a full-screen cover, with
/// Cancel, and first run's checks, the version check included. What happens next depends on the
/// computer the code came from (`PairAgain.isSameComputer`).
@MainActor
@Observable
final class PairAgainModel: Identifiable {
    let id = UUID()
    private(set) var scan: FirstRunModel.ScanPhase = .scanning
    private(set) var attempt = 0
    /// Another computer paired: "Switch to <name>?" asks first.
    var switchCandidate: PairedServer?

    @ObservationIgnored private let pairing: any ServerPairing
    @ObservationIgnored private let connectedPause: Duration
    @ObservationIgnored private let current: HexSetup
    @ObservationIgnored private let onSameComputer: (PairedServer) async -> Void
    @ObservationIgnored private let onSwitch: (PairedServer) -> Void
    @ObservationIgnored private var task: Task<Void, Never>?

    init(
        current: HexSetup,
        pairing: any ServerPairing,
        connectedPause: Duration = .milliseconds(900),
        onSameComputer: @escaping (PairedServer) async -> Void,
        onSwitch: @escaping (PairedServer) -> Void
    ) {
        self.current = current
        self.pairing = pairing
        self.connectedPause = connectedPause
        self.onSameComputer = onSameComputer
        self.onSwitch = onSwitch
    }

    /// A QR was read; only the first read of an attempt counts.
    func scanned(_ payload: String) {
        guard scan == .scanning else { return }
        scan = .connecting
        let attempt = attempt
        task = Task { [weak self, pairing] in
            let result = await pairing.pair(payload: payload)
            guard let self, !Task.isCancelled, attempt == self.attempt else { return }
            switch result {
            case let .success(server):
                self.scan = .connected(server: server)
                try? await Task.sleep(for: self.connectedPause)
                guard !Task.isCancelled, attempt == self.attempt else { return }
                if PairAgain.isSameComputer(server, as: self.current) {
                    await self.onSameComputer(server)
                } else {
                    self.switchCandidate = server
                }
            case let .failure(failure):
                self.scan = .failed(failure)
            }
        }
    }

    func scanAgain() {
        task?.cancel()
        task = nil
        switchCandidate = nil
        scan = .scanning
        attempt += 1
    }

    func cancel() {
        task?.cancel()
        task = nil
    }

    /// Switch: on to first run's project and model pages on the other computer.
    func confirmSwitch() {
        guard let server = switchCandidate else { return }
        switchCandidate = nil
        onSwitch(server)
    }
}

enum PairAgain {
    /// The same computer: the same hostname (lowercased, without `.local`), or, with no hostname on
    /// either side, the same first address.
    static func isSameComputer(_ server: PairedServer, as setup: HexSetup) -> Bool {
        let new = ServerName.seed(hostname: server.hostname, fallbackAddress: ServerHost.firstAddress(of: server.profile))
        let old = ServerName.seed(hostname: setup.serverHostname, fallbackAddress: ServerHost.firstAddress(of: setup.profile))
        return new == old
    }

    /// The same computer paired again: its new sign-in and addresses replace the old ones; the
    /// project, model and today's session stay.
    static func replacing(_ setup: HexSetup, with server: PairedServer, now: Date) -> HexSetup {
        var next = setup
        next.profile = server.profile
        next.serverVersion = server.version ?? setup.serverVersion
        next.serverHostname = server.hostname ?? setup.serverHostname
        next.signedInAt = now
        return next
    }

    static func pairedNotice(name: String) -> HexToastItem {
        HexToastItem(
            tone: .steer,
            title: String(localized: "server.paired", defaultValue: "Paired again"),
            detail: String(localized: "server.paired.sub", defaultValue: "Signed in to \(name).")
        )
    }
}

/// The scan cover for Pair again, with Cancel, and the Switch confirmation for another computer.
struct PairAgainView: View {
    @Bindable var model: PairAgainModel
    let onCancel: () -> Void

    var body: some View {
        ScanView(
            phase: model.scan,
            attempt: model.attempt,
            onScanned: { model.scanned($0) },
            onScanAgain: { model.scanAgain() },
            onClose: {
                model.cancel()
                onCancel()
            },
            cancelTitle: LocalizedStringResource("common.cancel", defaultValue: "Cancel")
        )
        #if DEBUG
        // `-HexCodePairAgain <pairing link>`: Pair again reads that code (simulators have no camera).
        .task {
            if let link = UserDefaults.standard.string(forKey: "HexCodePairAgain") { model.scanned(link) }
        }
        #endif
        .alert(
            Text(LocalizedStringResource("server.switch.title", defaultValue: "Switch to \(model.switchCandidate?.displayName ?? "")?")),
            isPresented: Binding(get: { model.switchCandidate != nil }, set: { if !$0 { model.switchCandidate = nil } }),
            presenting: model.switchCandidate
        ) { _ in
            Button(role: .cancel) { model.scanAgain() } label: {
                Text(LocalizedStringResource("common.cancel", defaultValue: "Cancel"))
            }
            Button { model.confirmSwitch() } label: {
                Text(LocalizedStringResource("server.switch.confirm", defaultValue: "Switch"))
            }
        } message: { server in
            Text(LocalizedStringResource(
                "server.switch.body",
                defaultValue: "Hex Code works with one computer at a time. Next, pick a project and a model on \(server.displayName). Sessions stay on each computer."
            ))
        }
    }
}
