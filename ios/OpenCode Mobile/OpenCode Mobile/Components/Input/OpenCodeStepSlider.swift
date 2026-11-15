import SwiftUI

private let stepSliderTrackHeight: CGFloat = 4
private let stepSliderTickSize: CGFloat = 6
private let stepSliderDefaultTickSize: CGFloat = 10
private let stepSliderKnobSize: CGFloat = 24
private let stepSliderSmallGlyphSize: CGFloat = 12
private let stepSliderLargeGlyphSize: CGFloat = 19

/// A slider that snaps to a few named steps, with a tick at each step. The text-size
/// variant draws a small "A" before the track and a large one after it, and marks the
/// default step with a larger tick. Drag or tap to pick a step; VoiceOver adjusts it by
/// one step and reads the step's name.
struct OpenCodeStepSlider: View {
    enum Variant {
        case textSize
    }

    let stepCount: Int
    let selectedIndex: Int
    /// Index drawn with the larger tick; nil marks none.
    var defaultIndex: Int?
    var variant: Variant = .textSize
    let accessibilityLabel: OpenCodeText
    /// Spoken value for a step, such as "Large".
    let accessibilityValue: (Int) -> OpenCodeText
    let onSelect: (Int) -> Void

    @State private var dragIndex: Int?

    private var shownIndex: Int { dragIndex ?? selectedIndex }

    var body: some View {
        HStack(spacing: 12) {
            glyph(size: stepSliderSmallGlyphSize)
            track
            glyph(size: stepSliderLargeGlyphSize)
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(openCode: accessibilityLabel)
        .accessibilityValue(Text(openCode: accessibilityValue(selectedIndex)))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: select(selectedIndex + 1)
            case .decrement: select(selectedIndex - 1)
            @unknown default: break
            }
        }
    }

    private func glyph(size: CGFloat) -> some View {
        Text(verbatim: "A")
            .font(.system(size: size, weight: .medium))
            .foregroundStyle(OpenCodeColors.textBase)
            .accessibilityHidden(true)
    }

    private var track: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let usable = max(1, width - stepSliderKnobSize)
            let x = { (index: Int) -> CGFloat in
                stepSliderKnobSize / 2 + usable * CGFloat(index) / CGFloat(max(1, stepCount - 1))
            }
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(OpenCodeColors.borderWeakerBase)
                    .frame(height: stepSliderTrackHeight)
                    .padding(.horizontal, stepSliderKnobSize / 2)
                Capsule()
                    .fill(OpenCodeColors.interactive)
                    .frame(width: max(0, x(shownIndex) - stepSliderKnobSize / 2), height: stepSliderTrackHeight)
                    .offset(x: stepSliderKnobSize / 2)
                ForEach(0 ..< stepCount, id: \.self) { index in
                    let size = index == defaultIndex ? stepSliderDefaultTickSize : stepSliderTickSize
                    Circle()
                        .fill(index <= shownIndex ? OpenCodeColors.interactive : OpenCodeColors.borderWeakBase)
                        .frame(width: size, height: size)
                        .position(x: x(index), y: geometry.size.height / 2)
                }
                Circle()
                    .fill(OpenCodeColors.backgroundBase)
                    .overlay(Circle().stroke(OpenCodeColors.borderWeakBase, lineWidth: 1))
                    .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
                    .frame(width: stepSliderKnobSize, height: stepSliderKnobSize)
                    .position(x: x(shownIndex), y: geometry.size.height / 2)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let index = Self.index(at: value.location.x, width: width, stepCount: stepCount)
                        if dragIndex != index {
                            dragIndex = index
                            if index != selectedIndex { onSelect(index) }
                        }
                    }
                    .onEnded { _ in dragIndex = nil }
            )
            .animation(.easeOut(duration: 0.12), value: shownIndex)
        }
        .frame(height: 44)
    }

    private func select(_ index: Int) {
        let clamped = min(max(index, 0), stepCount - 1)
        guard clamped != selectedIndex else { return }
        onSelect(clamped)
    }

    /// The step nearest to `x` on a track `width` points wide.
    static func index(at x: CGFloat, width: CGFloat, stepCount: Int) -> Int {
        guard stepCount > 1 else { return 0 }
        let usable = max(1, width - stepSliderKnobSize)
        let fraction = (x - stepSliderKnobSize / 2) / usable
        let index = Int((fraction * CGFloat(stepCount - 1)).rounded())
        return min(max(index, 0), stepCount - 1)
    }
}

#Preview {
    struct Host: View {
        @State private var index = 2

        var body: some View {
            OpenCodeStepSlider(
                stepCount: 5,
                selectedIndex: index,
                defaultIndex: 2,
                accessibilityLabel: .verbatim("Size"),
                accessibilityValue: { .verbatim("Step \($0 + 1)") },
                onSelect: { index = $0 }
            )
            .padding()
        }
    }
    return Host().openCodeTheme()
}
