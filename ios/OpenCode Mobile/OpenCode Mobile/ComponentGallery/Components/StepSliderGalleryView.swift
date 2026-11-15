import SwiftUI

struct StepSliderGalleryView: View {
    @State private var size = AppTextSize.default

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
                defaultIndex: AppTextSize.defaultSize.stepIndex,
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
