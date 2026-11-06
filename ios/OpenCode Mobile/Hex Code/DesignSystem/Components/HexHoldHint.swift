import SwiftUI

/// The quiet line above the talk button while it is held, where the finger can't cover it:
/// "‹ Drop" on the left and "Lock ›" on the right, `caption`, `text3` at 70 %. It fades in 250 ms
/// after the press (a quick take never shows it) and out on release. As the drag grows towards a
/// side, that side brightens (drop to `red`, lock to `text1`) and the other fades; once armed it
/// reads "Release to drop" or "Release to lock". It follows the same spring-smoothed drag as the
/// button, so the two move together and never flicker. Decorative: VoiceOver skips it.
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

/// The line itself, drawn from the smoothed offset (always shown; for the gallery too).
struct HexHoldHintLine: View, Animatable {
    var offset: CGFloat
    let arm: TalkDrag.Arm?

    var animatableData: CGFloat {
        get { offset }
        set { offset = newValue }
    }

    var body: some View {
        let progress = TalkDrag.progress(offset)
        let drop = max(0, -progress)
        let lock = max(0, progress)
        ZStack {
            HStack(spacing: 28) {
                side(icon: .chevL, text: LocalizedStringResource("home.hold.drop", defaultValue: "Drop"), bright: HexColor.red, lean: drop, away: lock, iconFirst: true)
                side(icon: .chevR, text: LocalizedStringResource("home.hold.lock", defaultValue: "Lock"), bright: HexColor.text1, lean: lock, away: drop, iconFirst: false)
            }
            .opacity(arm == nil ? 1 : 0)
            armed(.drop)
            armed(.lock)
        }
        .hexFont(.caption)
        .lineLimit(1)
    }

    /// One side: quiet `text3` at 70 %, brightening as the drag leans its way and fading as it
    /// leans the other way.
    private func side(icon: HexIcon, text: LocalizedStringResource, bright: Color, lean: CGFloat, away: CGFloat, iconFirst: Bool) -> some View {
        let label = HStack(spacing: 3) {
            if iconFirst { HexIconView(icon, size: HexIconSize.small) }
            Text(text)
            if !iconFirst { HexIconView(icon, size: HexIconSize.small) }
        }
        return ZStack {
            label.foregroundStyle(HexColor.text3).opacity(0.7 * (1 - lean))
            label.foregroundStyle(bright).opacity(lean)
        }
        .opacity(1 - away)
    }

    private func armed(_ which: TalkDrag.Arm) -> some View {
        HStack(spacing: 3) {
            if which == .drop { HexIconView(.chevL, size: HexIconSize.small) }
            Text(which == .drop
                ? LocalizedStringResource("home.hold.release_drop", defaultValue: "Release to drop")
                : LocalizedStringResource("home.hold.release_lock", defaultValue: "Release to lock"))
            if which == .lock { HexIconView(.chevR, size: HexIconSize.small) }
        }
        .foregroundStyle(which == .drop ? HexColor.red : HexColor.text1)
        .opacity(arm == which ? 1 : 0)
    }
}
