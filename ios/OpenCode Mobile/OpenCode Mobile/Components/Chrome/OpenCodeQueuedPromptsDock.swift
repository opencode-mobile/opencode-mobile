import SwiftUI

func formatQueuedPromptsDockLabel(count: Int, preview: String?) -> String {
    let collapsed = preview?
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if collapsed.isEmpty {
        return "\(count) queued"
    }
    return "\(count) queued · \(collapsed)"
}

struct OpenCodeQueuedPromptsDock: View {
    let count: Int
    let preview: String?
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Text(formatQueuedPromptsDockLabel(count: count, preview: preview))
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(count) queued prompts")
        .accessibilityAddTraits(.isButton)
    }
}

#Preview("Single") {
    OpenCodeQueuedPromptsDock(
        count: 1,
        preview: "Also check the compact path.",
        onTap: {}
    )
    .padding()
    .openCodeTheme()
}

#Preview("Multiple") {
    OpenCodeQueuedPromptsDock(
        count: 3,
        preview: "First queued prompt\nwith a second line",
        onTap: {}
    )
    .padding()
    .openCodeTheme()
    .preferredColorScheme(.dark)
}
