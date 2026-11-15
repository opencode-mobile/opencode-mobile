import SwiftUI

private let segmentedControlHeight: CGFloat = 36
private let segmentedControlInset: CGFloat = 3
private let segmentedControlCornerRadius: CGFloat = 10

struct OpenCodeSegmentedControlOption<ID: Hashable>: Identifiable {
    let id: ID
    let title: OpenCodeText
    var accessibilityIdentifier: String?
}

/// A short, exclusive choice drawn as equal-width segments in one track, such as the
/// Appearance mode. The selected segment is raised; each segment is a button with the
/// selected trait for VoiceOver.
struct OpenCodeSegmentedControl<ID: Hashable>: View {
    let options: [OpenCodeSegmentedControlOption<ID>]
    let selection: ID
    let onSelect: (ID) -> Void

    @Namespace private var selectionNamespace

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options) { option in
                segment(option)
            }
        }
        .padding(segmentedControlInset)
        .frame(height: segmentedControlHeight)
        .background(
            OpenCodeColors.surfaceRaisedBase,
            in: RoundedRectangle(cornerRadius: segmentedControlCornerRadius, style: .continuous)
        )
        .animation(.easeOut(duration: 0.18), value: selection)
    }

    private func segment(_ option: OpenCodeSegmentedControlOption<ID>) -> some View {
        let selected = option.id == selection
        return Button {
            guard !selected else { return }
            onSelect(option.id)
        } label: {
            Text(openCode: option.title)
                .font(OpenCodeTypography.button)
                .foregroundStyle(selected ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background {
                    if selected {
                        RoundedRectangle(
                            cornerRadius: segmentedControlCornerRadius - segmentedControlInset,
                            style: .continuous
                        )
                        .fill(OpenCodeColors.backgroundBase)
                        .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
                        .matchedGeometryEffect(id: "selection", in: selectionNamespace)
                    }
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(option.accessibilityIdentifier ?? "")
        .openCodeKeyboardFocusable(
            .roundedRectangle(cornerRadius: segmentedControlCornerRadius - segmentedControlInset),
            activate: { onSelect(option.id) }
        )
    }
}

#Preview {
    struct Host: View {
        @State private var selection = "system"

        var body: some View {
            OpenCodeSegmentedControl(
                options: [
                    OpenCodeSegmentedControlOption(id: "system", title: .verbatim("System")),
                    OpenCodeSegmentedControlOption(id: "light", title: .verbatim("Light")),
                    OpenCodeSegmentedControlOption(id: "dark", title: .verbatim("Dark")),
                ],
                selection: selection,
                onSelect: { selection = $0 }
            )
            .padding()
        }
    }
    return Host().openCodeTheme()
}
