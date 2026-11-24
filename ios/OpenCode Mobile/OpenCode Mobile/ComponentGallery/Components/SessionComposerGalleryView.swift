import SwiftUI

struct SessionComposerGalleryView: View {
    @State private var scenario: SessionComposerGalleryScenario = .empty
    @State private var composerStyle: SessionComposerStyle = .outlineFade
    @State private var fixture = SessionComposerGalleryFixture()
    @State private var lastAction = "None"
    @State private var focusRequest = 0
    @State private var inputFocused = false

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
                                label: .verbatim(option.label),
                                selected: scenario == option,
                                action: {
                                    scenario = option
                                    fixture.applyDefaults(for: option)
                                }
                            )
                        }
                    }
                }
                HStack(spacing: 8) {
                    ForEach(SessionComposerStyle.allCases, id: \.self) { option in
                        OpenCodeChoiceBadge(
                            label: .verbatim(Self.styleLabel(option)),
                            selected: composerStyle == option,
                            action: { composerStyle = option }
                        )
                    }
                }
                Text("Last action: \(lastAction)")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(
                        label: "Request focus",
                        selected: false,
                        enabled: state.acceptsInputFocus,
                        action: { focusRequest += 1 }
                    )
                    Text(inputFocusCaption)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textBase)
                }
            }
            .padding(.horizontal, 16)

            Spacer(minLength: 0)

            OpenCodeSessionComposer(
                state: state,
                focusRequestToken: focusRequest,
                onInputFocusChange: { inputFocused = $0 },
                style: composerStyle,
                onAction: handle
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private static func styleLabel(_ style: SessionComposerStyle) -> String {
        switch style {
        case .outlineFade: "Outline"
        case .docked: "Docked"
        }
    }

    private var inputFocusCaption: String {
        if !state.acceptsInputFocus { return "Input: none in this state" }
        return inputFocused ? "Input: focused" : "Input: not focused"
    }

    private var state: OpenCodeSessionComposerState {
        fixture.state(for: scenario)
    }

    private func handle(_ action: OpenCodeSessionComposerAction) {
        switch action {
        case let .textChanged(text, _, _):
            switch scenario {
            case .mentions:
                fixture.mentionText = text
                fixture.mentionsCleared = true
            case .shell:
                fixture.shellText = text
            case .sideQuestion:
                fixture.sideQuestionText = text
            default:
                fixture.draftText = text
            }
        case .exitShellMode:
            fixture.shellText = ComposerShellCommand.exitShellMode(fixture.shellText)
        case .exitSideQuestionMode:
            fixture.sideQuestionMode = false
        case let .toggleQuestionOption(_, _, label):
            if scenario == .multiSelect || scenario == .multiSelectBounded {
                if fixture.selectedLabels.contains(label) {
                    fixture.selectedLabels.removeAll { $0 == label }
                } else {
                    fixture.selectedLabels.append(label)
                }
            } else {
                fixture.selectedLabels = [label]
            }
            fixture.customSelected = false
        case .toggleQuestionCustom:
            fixture.customSelected.toggle()
            if fixture.customSelected {
                fixture.selectedLabels = []
            }
        case let .questionCustomTextChanged(_, _, text):
            fixture.customText = text
        case .questionBack:
            fixture.questionIndex = max(fixture.questionIndex - 1, 0)
        case .questionNext:
            fixture.questionIndex = min(fixture.questionIndex + 1, 1)
        case .openPermissionNote:
            fixture.permissionNote = ""
        case let .permissionNoteChanged(_, text):
            fixture.permissionNote = text
        case .cancelPermissionNote, .sendPermissionNote:
            fixture.permissionNote = nil
        default:
            break
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
        case .openCommands: "Open commands"
        case .removeAttachment: "Remove attachment"
        case .pasteAttachments: "Paste attachment"
        case .cancelRevert: "Cancel revert"
        case .exitShellMode: "Exit shell mode"
        case .exitSideQuestionMode: "Exit side question mode"
        case .send: "Send"
        case .toggleFollowUp: "Toggle follow-up"
        case .stop: "Stop"
        case .allowPermissionOnce: "Allow once"
        case .denyPermission: "Deny"
        case .alwaysAllowPermission: "Always"
        case .openPermissionNote: "Deny with a note"
        case .permissionNoteChanged: "Note changed"
        case .cancelPermissionNote: "Cancel note"
        case .sendPermissionNote: "Send note"
        case .hidePermission: "Hide permission"
        case .hideQuestion: "Hide question"
        case let .pageRequest(delta): delta < 0 ? "Previous request" : "Next request"
        case .openRequestSession: "Open subagent session"
        case .toggleQuestionOption: "Toggle option"
        case .toggleQuestionCustom: "Toggle custom"
        case .questionCustomTextChanged: "Custom text changed"
        case .questionBack: "Back"
        case .questionNext: "Next"
        case .submitQuestion: "Submit"
        case .dismissQuestion: "Dismiss"
        case .backToMainSession: "Back to main session"
        case .beginDictation: "Dictate"
        case .dictationCancel: "Cancel dictation"
        case .dictationInsert: "Insert transcript"
        case .dictationOpenSettings: "Open Settings"
        case .dictationDownload: "Download"
        case .dictationRetry: "Retry"
        }
    }
}

#Preview {
    SessionComposerGalleryView()
        .openCodeTheme()
}
