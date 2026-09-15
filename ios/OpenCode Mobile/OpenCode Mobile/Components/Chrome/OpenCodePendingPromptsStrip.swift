import SwiftUI

func formatPendingPromptsStripLabel(prompts: [PendingPrompt]) -> (primary: String, secondary: String?) {
    let primary = collapsedPendingPromptText(prompts.first?.text)
    let secondary = prompts.count > 1 ? "+\(prompts.count - 1) queued" : nil
    return (primary, secondary)
}

func formatPendingPromptsStripAccessibilityLabel(_ prompts: [PendingPrompt]) -> String {
    let count = prompts.count
    let noun = count == 1 ? "pending prompt" : "pending prompts"
    return "\(count) \(noun), next: \(collapsedPendingPromptText(prompts.first?.text))"
}

private func collapsedPendingPromptText(_ text: String?) -> String {
    text?
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
}

struct OpenCodePendingPromptsStrip: View {
    let prompts: [PendingPrompt]
    let onTap: () -> Void

    var body: some View {
        if prompts.isEmpty {
            EmptyView()
        } else {
            let labels = formatPendingPromptsStripLabel(prompts: prompts)
            Button(action: onTap) {
                HStack(spacing: 8) {
                    leadingIcon
                    Text(labels.primary)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let secondary = labels.secondary {
                        Text(secondary)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .fixedSize()
                    }
                    OpenCodeIconImage(icon: .keyboardArrowDown, size: 14)
                        .foregroundStyle(OpenCodeColors.iconWeakBase)
                }
                .padding(.leading, 12)
                .padding(.trailing, 8)
                .frame(minHeight: 32)
                .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
                .overlay(Capsule().strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1))
                .clipShape(Capsule())
                .shadow(color: .black.opacity(0.12), radius: 4, y: 2)
            }
            .buttonStyle(.plain)
            .contentShape(Capsule())
            .accessibilityLabel(formatPendingPromptsStripAccessibilityLabel(prompts))
            .accessibilityAddTraits(.isButton)
        }
    }

    @ViewBuilder
    private var leadingIcon: some View {
        switch prompts[0].delivery {
        case .steer:
            OpenCodeIconImage(icon: .arrowWarmUp, size: 14)
                .foregroundStyle(OpenCodeColors.interactive)
        case .queue:
            OpenCodeIconImage(icon: .arrowUpward, size: 14)
                .foregroundStyle(OpenCodeColors.warning)
        }
    }
}

#Preview("Single steer") {
    OpenCodePendingPromptsStrip(
        prompts: [
            PendingPrompt(id: "1", text: "Also check the compact path.", delivery: .steer),
        ],
        onTap: {}
    )
    .padding()
    .openCodeTheme()
}

#Preview("Single queued") {
    OpenCodePendingPromptsStrip(
        prompts: [
            PendingPrompt(id: "1", text: "Also check the compact path.", delivery: .queue),
        ],
        onTap: {}
    )
    .padding()
    .openCodeTheme()
}

#Preview("Steer + 2 queued") {
    OpenCodePendingPromptsStrip(
        prompts: [
            PendingPrompt(id: "1", text: "Steer this next.", delivery: .steer),
            PendingPrompt(id: "2", text: "Then this.", delivery: .queue),
            PendingPrompt(id: "3", text: "And this after.", delivery: .queue),
        ],
        onTap: {}
    )
    .padding()
    .openCodeTheme()
}

#Preview("Long text") {
    OpenCodePendingPromptsStrip(
        prompts: [
            PendingPrompt(
                id: "1",
                text: "First queued prompt\nwith a second line that should collapse and truncate.",
                delivery: .queue
            ),
            PendingPrompt(id: "2", text: "Another.", delivery: .queue),
        ],
        onTap: {}
    )
    .padding()
    .openCodeTheme()
    .preferredColorScheme(.dark)
}
