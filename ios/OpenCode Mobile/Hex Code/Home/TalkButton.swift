import SwiftUI
import VoiceEngineKit

/// The talk button on home: `HexTalkButton` driven by the home model and the speech engine.
/// Hold to talk; slide right to lock, left to drop the take; a tap pauses a locked take.
struct TalkButton: View {
    let home: HomeModel
    let voice: VoiceEngine

    var body: some View {
        HexTalkButton(
            phase: HexTalkButton.Phase(
                label: label,
                ready: voice.modelState == .ready,
                quiet: !home.draft.isEmpty,
                recording: home.isRecording,
                pressing: home.isPressing,
                locked: home.locked,
                offset: home.dragOffset,
                arm: home.arm
            ),
            level: { Double(home.level) },
            accessibilityText: accessibilityText,
            onPressBegan: { home.pressBegan() },
            onPressMoved: { home.pressMoved(dx: $0) },
            onPressEnded: { Task { await home.pressEnded() } }
        )
    }

    private var label: String {
        switch voice.modelState {
        case let .preparing(fraction, _):
            return String(localized: "talk.preparing", defaultValue: "Getting speech ready · \(Int((fraction * 100).rounded()))%")
        case .notLoaded:
            return String(localized: "talk.waiting", defaultValue: "Getting speech ready")
        case .failed:
            return String(localized: "talk.failed", defaultValue: "Speech isn’t available")
        case .ready:
            if home.draft.selectedIndex != nil {
                return String(localized: "talk.rerecord", defaultValue: "Hold to re-record")
            }
            return home.draft.isEmpty
                ? String(localized: "talk.hold", defaultValue: "Hold to talk")
                : String(localized: "talk.more", defaultValue: "Hold to keep talking")
        }
    }

    private var accessibilityText: String {
        if home.locked { return String(localized: "talk.a11y.locked", defaultValue: "Recording, locked. Tap to pause.") }
        if home.isRecording { return String(localized: "talk.a11y.recording", defaultValue: "Recording. Release to pause.") }
        return label
    }
}
