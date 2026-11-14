import Foundation
import Observation
import UIKit

/// A spoken answer in a sheet (Say what to do instead, Say your own answer): hold the row to
/// dictate, as the talk button. Release ends the take and it is transcribed whole; holding again
/// records it again. A press shorter than a hold is a tap.
@MainActor
@Observable
final class SpokenAnswer {
    enum State: Equatable {
        case idle
        /// "Listening… · release to finish".
        case listening
        /// "Transcribing…".
        case transcribing
    }

    private(set) var state: State = .idle
    /// The latest text; "" until something was said.
    private(set) var text = ""

    @ObservationIgnored private let begin: () -> Bool
    @ObservationIgnored private let end: () async -> String?
    @ObservationIgnored private let cancel: () async -> Void
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private var pressedAt: Date?

    init(
        begin: @escaping () -> Bool,
        end: @escaping () async -> String?,
        cancel: @escaping () async -> Void,
        now: @escaping () -> Date = Date.init
    ) {
        self.begin = begin
        self.end = end
        self.cancel = cancel
        self.now = now
    }

    /// The take's own recorder and speech model: home's, which the draft uses too.
    convenience init(home: HomeModel) {
        self.init(
            begin: { [weak home] in home?.beginSideTake() ?? false },
            end: { [weak home] in await home?.endSideTake() },
            cancel: { [weak home] in await home?.cancelSideTake() }
        )
    }

    /// Next and Submit wait while a take is listening or transcribing.
    var isBusy: Bool { state != .idle }

    func pressBegan() {
        guard state == .idle, begin() else { return }
        pressedAt = now()
        state = .listening
    }

    /// Release: a hold's text goes to `onText` (an empty take keeps the earlier text); a press
    /// shorter than a hold is a tap and goes to `onTap`.
    func pressEnded(onText: (String) -> Void, onTap: () -> Void = {}) async {
        guard state == .listening, let pressedAt else { return }
        self.pressedAt = nil
        if now().timeIntervalSince(pressedAt) < HomeModel.minimumHold {
            await cancel()
            state = .idle
            onTap()
            return
        }
        state = .transcribing
        let result = await end()?.trimmingCharacters(in: .whitespacesAndNewlines)
        state = .idle
        guard let result, !result.isEmpty else { return }
        text = result
        onText(result)
    }

    /// The sheet went: a take still recording is dropped.
    func close() async {
        guard state == .listening else { return }
        pressedAt = nil
        state = .idle
        await cancel()
    }
}

/// The dock's stop button held for 3 s (app plan, "Stop" › Hold, Warning and Cancel). The press
/// shows the warning at once with a light haptic and ticks each second; at 3 s it stops the agent
/// with a success haptic. Letting go before then, or sliding 44 pt off the button, cancels. Toasts
/// are hidden while holding. A dimmed stop does nothing.
@MainActor
@Observable
final class StopHoldModel {
    enum Haptic: Equatable {
        case press
        case tick
        case success
    }

    /// Since when the button is held; nil when it isn't.
    private(set) var heldSince: Date?

    @ObservationIgnored private let toasts: HexToaster
    @ObservationIgnored private let canStop: () -> Bool
    @ObservationIgnored private let stop: () async -> Void
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored var haptic: (Haptic) -> Void = StopHoldModel.play
    @ObservationIgnored private var timer: Task<Void, Never>?

    init(
        toasts: HexToaster,
        canStop: @escaping () -> Bool,
        stop: @escaping () async -> Void,
        now: @escaping () -> Date = Date.init,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.toasts = toasts
        self.canStop = canStop
        self.stop = stop
        self.now = now
        self.sleep = sleep
    }

    func pressBegan() {
        guard heldSince == nil, canStop() else { return }
        let start = now()
        heldSince = start
        toasts.isHeld = true
        haptic(.press)
        timer?.cancel()
        let sleep = sleep
        timer = Task { [weak self] in
            for second in 1 ... Int(StopHold.duration) {
                guard (try? await sleep(.seconds(1))) != nil, let self, self.heldSince == start else { return }
                if second < Int(StopHold.duration) {
                    self.haptic(.tick)
                } else {
                    await self.fire()
                }
            }
        }
    }

    /// The finger slid; more than 44 pt off the button cancels.
    func moved(outside distance: CGFloat) {
        guard heldSince != nil, distance > StopHold.slideLimit else { return }
        release()
    }

    func pressEnded() {
        release()
    }

    private func release() {
        guard let start = heldSince else { return }
        timer?.cancel()
        timer = nil
        heldSince = nil
        toasts.isHeld = false
        switch StopHold.release(after: now().timeIntervalSince(start)) {
        case .tap: toasts.show(TodaySession.holdToStopNotice())
        case .cancelled: toasts.show(TodaySession.stopCancelledNotice())
        case .stopped: break
        }
    }

    /// 3 s: the warning goes as the first request is sent.
    private func fire() async {
        timer = nil
        heldSince = nil
        toasts.isHeld = false
        haptic(.success)
        await stop()
    }

    private static func play(_ haptic: Haptic) {
        switch haptic {
        case .press: UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .tick: UISelectionFeedbackGenerator().selectionChanged()
        case .success: UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
    }
}
