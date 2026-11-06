import SwiftUI

/// The quiet label above the talk button while it is held, where the finger can't cover it:
/// "Slide to lock", `caption`, `text3` at 70 %. It fades in 250 ms after the press (a quick take
/// never shows it) and out on release. It stays subtle all the way, so the eye goes to the button:
/// leaning right keeps it as it is, leaning left fades it out. Once armed it reads "Release to
/// lock" (the same `text3` at 70 %) or "Release to drop" (`red` at 55 %). It follows the same
/// spring-smoothed drag as the button, so the two move together and never flicker. Decorative:
/// VoiceOver skips it.
struct HexHoldHint: View {
    /// The finger is down on the talk button (not locked).
    let holding: Bool
    /// The finger's sideways distance from the touch, in points (right positive).
    let offset: CGFloat
    let arm: TalkDrag.Arm?

    /// The wait before it shows.
    static let delay: Duration = .milliseconds(250)

    @State private var shown = false

    var body: some View {
        HexHoldHintLine(offset: holding ? offset : 0, arm: holding ? arm : nil)
            .animation(HexTalkButton.follow, value: holding ? offset : 0)
            .animation(HexMotion.ease(HexMotion.fade), value: arm)
            .opacity(shown && holding ? 1 : 0)
            .animation(HexMotion.ease(HexMotion.fade), value: shown && holding)
            .task(id: holding) {
                guard holding else {
                    shown = false
                    return
                }
                try? await Task.sleep(for: Self.delay)
                if !Task.isCancelled { shown = true }
            }
            .accessibilityHidden(true)
            .allowsHitTesting(false)
    }
}

/// The label itself, drawn from the smoothed offset (always shown; for the gallery too).
struct HexHoldHintLine: View, Animatable {
    var offset: CGFloat
    let arm: TalkDrag.Arm?

    var animatableData: CGFloat {
        get { offset }
        set { offset = newValue }
    }

    var body: some View {
        let drop = max(0, -TalkDrag.progress(offset))
        ZStack {
            Text(LocalizedStringResource("home.hold.slide_lock", defaultValue: "Slide to lock"))
                .foregroundStyle(HexColor.text3)
                .opacity(0.7 * (1 - drop) * (arm == nil ? 1 : 0))
            Text(LocalizedStringResource("home.hold.release_lock", defaultValue: "Release to lock"))
                .foregroundStyle(HexColor.text3)
                .opacity(arm == .lock ? 0.7 : 0)
            Text(LocalizedStringResource("home.hold.release_drop", defaultValue: "Release to drop"))
                .foregroundStyle(HexColor.red)
                .opacity(arm == .drop ? 0.55 : 0)
        }
        .hexFont(.caption)
        .lineLimit(1)
    }
}
