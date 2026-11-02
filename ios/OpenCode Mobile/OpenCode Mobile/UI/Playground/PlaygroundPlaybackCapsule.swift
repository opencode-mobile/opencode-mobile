import SwiftUI

/// Where the capsule was dragged to and whether it is minimised; outlives a scenario switch.
struct PlaygroundCapsulePlacement: Equatable {
    var offset: CGSize = .zero
    var minimized = false
}

/// Below the Session Chat top bar and the row of top pills, centred, so the capsule starts clear
/// of the composer and of every pill corner.
private let capsuleDefaultTop: CGFloat = 128
private let minimizedSize: CGFloat = 48
private let capsuleCornerRadius: CGFloat = 24

/// The playground's floating playback controls for a scripted scenario: what step it is on, and
/// play / pause, next step, restart, 1× / 4×, minimise. Drag it by its grip; minimised it is a
/// round button with a progress ring that brings the capsule back.
struct PlaygroundPlaybackCapsule: View {
    let code: String
    let player: PlaygroundPlayer
    let idleLabel: String
    @Binding var placement: PlaygroundCapsulePlacement
    let onRestart: () -> Void

    @State private var size: CGSize = .zero
    @State private var containerSize: CGSize = .zero
    /// The offset when the current drag began; nil while not dragging.
    @State private var dragStart: CGSize?

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                Group {
                    if placement.minimized {
                        minimized
                    } else {
                        expanded
                    }
                }
                .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
                .offset(placement.offset)
                .padding(.top, capsuleDefaultTop)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity)
            .onAppear { containerSize = geometry.size }
            .onChange(of: geometry.size) { _, newSize in containerSize = newSize }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("playgroundCapsule")
    }

    private var progress: PlaygroundPlayer.Progress { player.progress }

    private var expanded: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                OpenCodeIconImage(icon: .dragHandle, size: 16)
                    .foregroundStyle(OpenCodeColors.iconBase)
                    .accessibilityHidden(true)
                Text("\(code) · \(progress.label ?? idleLabel)")
                    .font(OpenCodeTypography.caption.weight(.medium))
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .accessibilityIdentifier("playgroundStepLabel")
            }
            .frame(maxWidth: 320, alignment: .leading)
            .padding(.horizontal, 4)
            .padding(.vertical, 2)
            .contentShape(Rectangle())
            .gesture(drag)
            .accessibilityHint("Drag to move the playback controls")

            HStack(spacing: 4) {
                OpenCodeButton(
                    variant: .iconCircle(
                        progress.isPlaying ? .pause : .play,
                        accessibilityLabel: progress.isPlaying ? "Pause" : "Play"
                    ),
                    enabled: !progress.isFinished,
                    action: { progress.isPlaying ? player.pause() : player.play() }
                )
                .accessibilityIdentifier("playgroundPlayPause")
                Text("\(progress.shownSteps)/\(progress.stepCount)")
                    .font(OpenCodeTypography.caption.weight(.semibold).monospacedDigit())
                    .foregroundStyle(OpenCodeColors.textBase)
                    .padding(.horizontal, 4)
                    .accessibilityIdentifier("playgroundStepCount")
                OpenCodeButton(
                    variant: .iconCircle(.skipNext, accessibilityLabel: "Next step"),
                    enabled: !progress.isFinished,
                    contrast: .low,
                    action: { player.next() }
                )
                .accessibilityIdentifier("playgroundNextStep")
                OpenCodeButton(
                    variant: .iconCircle(.restart, accessibilityLabel: "Restart scenario"),
                    contrast: .low,
                    action: onRestart
                )
                .accessibilityIdentifier("playgroundRestart")
                OpenCodeButton(
                    variant: .iconCircleText(
                        progress.isFast ? "4×" : "1×",
                        accessibilityLabel: progress.isFast ? "Speed 4 times, switch to normal" : "Normal speed, switch to 4 times"
                    ),
                    contrast: .low,
                    action: { player.setFast(!progress.isFast) }
                )
                .accessibilityIdentifier("playgroundSpeed")
                OpenCodeButton(
                    variant: .iconCircle(.minimize, accessibilityLabel: "Minimise playback controls"),
                    contrast: .low,
                    action: { placement.minimized = true }
                )
                .accessibilityIdentifier("playgroundMinimize")
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .openCodeFloatingChrome(cornerRadius: capsuleCornerRadius)
    }

    private var minimized: some View {
        ZStack {
            Circle()
                .inset(by: 1.5)
                .stroke(OpenCodeColors.borderWeakBase, lineWidth: 3)
            Circle()
                .inset(by: 1.5)
                .trim(from: 0, to: progress.fraction)
                .stroke(OpenCodeColors.interactive, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text(code)
                .font(OpenCodeTypography.caption.weight(.semibold))
                .foregroundStyle(OpenCodeColors.textStrong)
        }
        .frame(width: minimizedSize - 8, height: minimizedSize - 8)
        .frame(width: minimizedSize, height: minimizedSize)
        .openCodeFloatingChrome(cornerRadius: minimizedSize / 2)
        .contentShape(Circle())
        .onTapGesture { placement.minimized = false }
        .gesture(drag)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Playback \(code), step \(progress.shownSteps) of \(progress.stepCount)")
        .accessibilityHint("Shows the playback controls")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { placement.minimized = false }
        .accessibilityIdentifier("playgroundCapsuleDot")
    }

    /// Moves the capsule, kept inside the area below the top safe area.
    private var drag: some Gesture {
        // Global space: the capsule moves with the drag, so a local translation would feed back into itself.
        DragGesture(minimumDistance: 4, coordinateSpace: .global)
            .onChanged { value in
                let start = dragStart ?? placement.offset
                if dragStart == nil { dragStart = start }
                placement.offset = clamped(CGSize(
                    width: start.width + value.translation.width,
                    height: start.height + value.translation.height
                ))
            }
            .onEnded { _ in dragStart = nil }
    }

    private func clamped(_ offset: CGSize) -> CGSize {
        let halfSlack = max(0, (containerSize.width - size.width) / 2)
        let lowest = max(-capsuleDefaultTop, containerSize.height - capsuleDefaultTop - size.height)
        return CGSize(
            width: min(max(offset.width, -halfSlack), halfSlack),
            height: min(max(offset.height, -capsuleDefaultTop), lowest)
        )
    }
}
