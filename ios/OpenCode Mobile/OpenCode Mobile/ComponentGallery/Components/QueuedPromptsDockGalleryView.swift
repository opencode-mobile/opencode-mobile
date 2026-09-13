import SwiftUI

private enum QueuedPromptsDockSample: String, CaseIterable {
    case single = "Single"
    case multiple = "Multiple"

    var count: Int {
        switch self {
        case .single: 1
        case .multiple: 3
        }
    }

    var preview: String? {
        switch self {
        case .single: "Also check the compact path."
        case .multiple: "First queued prompt\nwith a second line"
        }
    }
}

struct QueuedPromptsDockGalleryView: View {
    @State private var sample: QueuedPromptsDockSample = .single

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Switch samples to review a single queued prompt and a multi-item dock label.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                ForEach(QueuedPromptsDockSample.allCases, id: \.self) { option in
                    OpenCodeChoiceBadge(
                        label: option.rawValue,
                        selected: sample == option,
                        action: { sample = option }
                    )
                }
            }

            OpenCodeQueuedPromptsDock(
                count: sample.count,
                preview: sample.preview,
                onTap: {}
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    QueuedPromptsDockGalleryView()
        .padding()
        .openCodeTheme()
}
