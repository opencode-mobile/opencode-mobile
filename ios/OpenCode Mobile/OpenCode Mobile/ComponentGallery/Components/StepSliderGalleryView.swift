import SwiftUI

struct StepSliderGalleryView: View {
    @State private var size: AppTextSize
    /// Draws the tick for the Default step.
    private let marksDefault: Bool

    init(size: AppTextSize = .default, marksDefault: Bool = true) {
        _size = State(initialValue: size)
        self.marksDefault = marksDefault
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("appearance.text_size_label")
                    .font(OpenCodeTypography.body)
                Spacer()
                Text(openCode: size.title)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
            OpenCodeStepSlider(
                stepCount: AppTextSize.allCases.count,
                selectedIndex: size.stepIndex,
                defaultIndex: marksDefault ? AppTextSize.defaultSize.stepIndex : nil,
                accessibilityLabel: "appearance.text_size_label",
                accessibilityValue: { AppTextSize.fromStepIndex($0).title },
                onSelect: { size = AppTextSize.fromStepIndex($0) }
            )
            Text(verbatim: "The quick brown fox")
                .openCodeFont(.body)
                .openCodeTextScale(size)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    StepSliderGalleryView()
        .padding()
        .openCodeTheme()
}
