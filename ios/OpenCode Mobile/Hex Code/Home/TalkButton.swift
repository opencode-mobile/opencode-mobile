import SwiftUI
import VoiceEngineKit

/// The talk button on home: `HexTalkButton` driven by the home model and the speech engine.
/// Hold to talk; slide right to lock, left to drop the take; a tap pauses a locked take.
struct TalkButton: View {
    let home: HomeModel
    let voice: VoiceEngine

    var body: some View {
        // A locked take's label carries its clock, so it is read again each second.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            button(at: context.date)
        }
    }

    private func button(at date: Date) -> some View {
        HexTalkButton(
            phase: HexTalkButton.Phase(
                label: label,
                quiet: !home.draft.isEmpty,
                recording: home.isRecording,
                pressing: home.isPressing,
                locked: home.locked,
                startedAt: home.takeStartedAt,
                offset: home.dragOffset,
                arm: home.arm
            ),
            level: { home.level.map(Double.init) },
            accessibilityText: label,
            accessibilityValue: accessibilityValue(at: date),
            onPressBegan: { home.pressBegan() },
            onPressMoved: { home.pressMoved(dx: $0) },
            onPressEnded: { Task { await home.pressEnded() } }
        )
        // VoiceOver can't hold and drag: activating starts or ends a hands-free take, and Drop
        // forgets the one recording.
        .accessibilityAction { Task { await home.toggleHandsFreeTake() } }
        .accessibilityAction(named: Text(LocalizedStringResource("home.hold.drop", defaultValue: "Drop"))) {
            Task { await home.dropCurrentTake() }
        }
    }

    private var label: String {
        // The button works whatever the speech model is doing; takes wait for it instead.
        if home.draft.selectedIndex != nil {
            return String(localized: "talk.rerecord", defaultValue: "Hold to re-record")
        }
        return home.draft.isEmpty
            ? String(localized: "talk.hold", defaultValue: "Hold to talk")
            : String(localized: "talk.more", defaultValue: "Hold to keep talking")
    }

    /// "Recording, 3 seconds" while a take records: the clock is part of the button, spoken as a
    /// duration, not "0:03".
    private func accessibilityValue(at date: Date) -> String? {
        guard home.isRecording else { return nil }
        return Self.recordingValue(home.takeStartedAt.map { date.timeIntervalSince($0) } ?? 0)
    }

    static func recordingValue(_ elapsed: TimeInterval) -> String {
        let seconds = max(0, Int(elapsed.rounded(.down)))
        let time = Duration.seconds(seconds).formatted(.units(allowed: [.minutes, .seconds], width: .wide))
        return String(localized: "talk.a11y.recording", defaultValue: "Recording, \(time)")
    }
}
