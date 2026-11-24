import SwiftUI

struct SegmentedControlGalleryView: View {
    @State private var mode: AppearanceMode

    init(mode: AppearanceMode = .system) {
        _mode = State(initialValue: mode)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            OpenCodeSegmentedControl(
                options: AppearanceMode.allCases.map { OpenCodeSegmentedControlOption(id: $0, title: $0.title) },
                selection: mode,
                onSelect: { mode = $0 }
            )
            Text(openCode: mode.note)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    SegmentedControlGalleryView()
        .padding()
        .openCodeTheme()
}
