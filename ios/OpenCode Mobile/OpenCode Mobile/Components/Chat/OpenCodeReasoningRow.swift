import SwiftUI

/// "Thinking" while a thought is streaming, or "Thought · Ns" once it settles.
struct OpenCodeReasoningRow: View {
    var state: OpenCodeChatActivityState
    var durationLabel: String?
    var action: (() -> Void)?

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            switch state {
            case .working:
                OpenCodePulsingDot()
            case .idle:
                OpenCodeWorkingIdleMark()
            case .collapsed, .expanded:
                EmptyView()
            }
            Text(label)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textWeak)
            Spacer(minLength: 0)
        }
        .padding(.leading, 4)
        .padding(.top, state == .collapsed ? 0 : 12)
        .padding(.bottom, state == .collapsed ? 0 : 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: state == .collapsed ? 0 : nil)
        .clipped()
        .contentShape(Rectangle())
        .onTapGesture {
            action?()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityHidden(state == .collapsed)
        .accessibilityAddTraits(action == nil ? [] : .isButton)
        .accessibilityLabel(label)
        .accessibilityHint(action == nil ? "" : "Shows the full thought")
        .openCodeKeyboardFocusable(
            .insetRoundedRectangle(cornerRadius: OpenCodeRadius.small),
            enabled: action != nil && state != .collapsed,
            activate: action
        )
    }

    private var label: String {
        switch state {
        case .working:
            "Thinking"
        case .idle, .collapsed:
            "Thought"
        case .expanded:
            if let durationLabel, !durationLabel.isEmpty {
                "Thought · \(durationLabel)"
            } else {
                "Thought"
            }
        }
    }
}

/// Check used when a reasoning row is idle. The working row has its own mark.
private struct OpenCodeWorkingIdleMark: View {
    var body: some View {
        OpenCodeIconImage(icon: .check, size: 12)
            .foregroundStyle(OpenCodeColors.textWeak)
            .frame(width: 12, height: 12)
            .accessibilityHidden(true)
    }
}

#Preview("Reasoning Row") {
    VStack(alignment: .leading) {
        OpenCodeReasoningRow(state: .working, action: nil)
        OpenCodeReasoningRow(state: .expanded, durationLabel: "1.2s", action: {})
    }
    .openCodeTheme()
}
