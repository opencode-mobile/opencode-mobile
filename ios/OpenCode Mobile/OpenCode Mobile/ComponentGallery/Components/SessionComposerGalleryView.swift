import SwiftUI

private enum SessionComposerGalleryScenario: String, CaseIterable, Identifiable {
    case empty
    case prompt
    case active
    case permission
    case queued
    case submitting
    case error

    var id: String { rawValue }
    var label: String { rawValue.capitalized }
}

struct SessionComposerGalleryView: View {
    @State private var scenario: SessionComposerGalleryScenario = .empty
    @State private var draftText = "Summarize the current changes"
    @State private var lastAction = "None"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("State")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(SessionComposerGalleryScenario.allCases) { option in
                            OpenCodeChoiceBadge(
                                label: option.label,
                                selected: scenario == option,
                                action: { scenario = option }
                            )
                        }
                    }
                }
                Text("Last action: \(lastAction)")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
            .padding(.horizontal, 16)

            Spacer(minLength: 0)

            OpenCodeSessionComposer(state: state, onAction: handle)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var state: OpenCodeSessionComposerState {
        switch scenario {
        case .empty:
            promptState(text: "")
        case .prompt:
            promptState(text: draftText)
        case .active:
            promptState(text: draftText, showStop: true)
        case .permission:
            permissionState()
        case .queued:
            permissionState(queueCount: 3)
        case .submitting:
            permissionState(submitting: true)
        case .error:
            permissionState(errorMessage: "The permission response failed. Try again.")
        }
    }

    private func promptState(text: String, showStop: Bool = false) -> OpenCodeSessionComposerState {
        .prompt(
            .init(
                text: text,
                selectedModelLabel: "Claude Sonnet 4",
                selectedModeLabel: "Build",
                selectedVariantLabel: "Default",
                showVariant: true,
                sendEnabled: !text.isEmpty,
                showStop: showStop,
                stopEnabled: showStop
            )
        )
    }

    private func permissionState(
        queueCount: Int = 1,
        submitting: Bool = false,
        errorMessage: String? = nil
    ) -> OpenCodeSessionComposerState {
        .permission(
            .init(
                permission: "bash",
                patterns: ["git status", "git diff --stat"],
                queueCount: queueCount,
                submitting: submitting,
                errorMessage: errorMessage
            )
        )
    }

    private func handle(_ action: OpenCodeSessionComposerAction) {
        if case let .textChanged(text) = action {
            draftText = text
        }
        lastAction = action.label
    }
}

private extension OpenCodeSessionComposerAction {
    var label: String {
        switch self {
        case .textChanged: "Text changed"
        case .selectModel: "Select model"
        case .selectMode: "Select mode"
        case .selectVariant: "Select variant"
        case .attach: "Attach"
        case .send: "Send"
        case .stop: "Stop"
        case .allowPermissionOnce: "Allow once"
        case .denyPermission: "Deny"
        }
    }
}

#Preview {
    SessionComposerGalleryView()
        .openCodeTheme()
}
