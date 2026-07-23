import SwiftUI

private enum SessionComposerGalleryScenario: String, CaseIterable, Identifiable {
    case empty
    case prompt
    case mentions
    case active
    case attachments
    case permission
    case queued
    case submitting
    case error
    case question
    case questionWizard
    case multiSelect
    case custom
    case questionSubmitting
    case questionError

    var id: String { rawValue }

    var label: String {
        switch self {
        case .empty: "Empty"
        case .prompt: "Prompt"
        case .mentions: "Mentions"
        case .active: "Active"
        case .attachments: "Attachments"
        case .permission: "Permission"
        case .queued: "Queued"
        case .submitting: "Submitting"
        case .error: "Error"
        case .question: "Question"
        case .questionWizard: "Wizard"
        case .multiSelect: "Multi-select"
        case .custom: "Custom"
        case .questionSubmitting: "Q Submit"
        case .questionError: "Q Error"
        }
    }
}

private let mentionGalleryText = "Ask @explore about this"
private let mentionGalleryRanges: [Range<Int>] = [4 ..< 12]

private let sampleQuestionOptions: [OpenCodeSessionComposerState.QuestionOption] = [
    .init(label: "Minimal patch", description: "Touch only the failing path"),
    .init(label: "Broader refactor", description: "Clean up nearby callers too"),
    .init(label: "Investigate first", description: "Gather more evidence before changing code"),
]

struct SessionComposerGalleryView: View {
    @State private var scenario: SessionComposerGalleryScenario = .empty
    @State private var draftText = "Summarize the current changes"
    @State private var mentionText = mentionGalleryText
    @State private var mentionsCleared = false
    @State private var lastAction = "None"
    @State private var selectedLabels = ["Minimal patch"]
    @State private var customSelected = false
    @State private var customText = ""
    @State private var questionIndex = 0

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
                                action: {
                                    scenario = option
                                    applyScenarioDefaults(option)
                                }
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

    private var questionAnswered: Bool {
        if customSelected {
            !customText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        } else {
            !selectedLabels.isEmpty
        }
    }

    private var state: OpenCodeSessionComposerState {
        switch scenario {
        case .empty:
            promptState(text: "")
        case .prompt:
            promptState(text: draftText)
        case .mentions:
            promptState(
                text: mentionText,
                mentionRanges: mentionsCleared ? [] : mentionGalleryRanges
            )
        case .active:
            promptState(text: draftText, showStop: true)
        case .attachments:
            promptState(text: "", attachments: [
                .init(id: "pdf", filename: "requirements.pdf", variant: .pdf),
                .init(id: "text", filename: "notes.md", variant: .text),
            ])
        case .permission:
            permissionState()
        case .queued:
            permissionState(queueCount: 3)
        case .submitting:
            permissionState(submitting: true)
        case .error:
            permissionState(errorMessage: "The permission response failed. Try again.")
        case .question:
            questionState(
                questionIndex: 0,
                questionCount: 1,
                selectedLabels: selectedLabels,
                customText: customText,
                customSelected: customSelected,
                submitEnabled: questionAnswered
            )
        case .questionWizard:
            questionState(
                questionIndex: questionIndex,
                questionCount: 2,
                header: questionIndex == 0 ? "Approach" : "Tests",
                question: questionIndex == 0
                    ? "How should we implement this change?"
                    : "Which tests should we prioritize?",
                selectedLabels: selectedLabels,
                customText: customText,
                customSelected: customSelected,
                backEnabled: questionIndex > 0,
                nextEnabled: questionAnswered && questionIndex < 1,
                submitEnabled: questionAnswered && questionIndex == 1
            )
        case .multiSelect:
            questionState(
                questionIndex: 0,
                questionCount: 1,
                multiple: true,
                customAllowed: false,
                selectedLabels: selectedLabels,
                submitEnabled: !selectedLabels.isEmpty
            )
        case .custom:
            questionState(
                questionIndex: 0,
                questionCount: 1,
                selectedLabels: selectedLabels,
                customText: customText.isEmpty ? "Ship a focused fix tonight" : customText,
                customSelected: true,
                submitEnabled: true
            )
        case .questionSubmitting:
            questionState(
                questionIndex: 0,
                questionCount: 1,
                selectedLabels: ["Minimal patch"],
                submitEnabled: true,
                submitting: true
            )
        case .questionError:
            questionState(
                questionIndex: 0,
                questionCount: 1,
                selectedLabels: ["Minimal patch"],
                submitEnabled: true,
                errorMessage: "The question response failed. Try again."
            )
        }
    }

    private func promptState(
        text: String,
        mentionRanges: [Range<Int>] = [],
        attachments: [OpenCodeAttachmentPresentation] = [],
        showStop: Bool = false
    ) -> OpenCodeSessionComposerState {
        .prompt(
            .init(
                text: text,
                mentionRanges: mentionRanges,
                attachments: attachments,
                selectedModelLabel: "Claude Sonnet 4",
                selectedModeLabel: "Build",
                selectedVariantLabel: "Default",
                showVariant: true,
                sendEnabled: !text.isEmpty || !attachments.isEmpty,
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
                requestID: "permission-gallery",
                sessionID: "session-gallery",
                permission: "bash",
                patterns: ["git status", "git diff --stat"],
                queueCount: queueCount,
                submitting: submitting,
                errorMessage: errorMessage
            )
        )
    }

    private func questionState(
        questionIndex: Int,
        questionCount: Int,
        header: String = "Approach",
        question: String = "How should we implement this change?",
        multiple: Bool = false,
        customAllowed: Bool = true,
        selectedLabels: [String] = [],
        customText: String = "",
        customSelected: Bool = false,
        backEnabled: Bool = false,
        nextEnabled: Bool = false,
        submitEnabled: Bool = false,
        submitting: Bool = false,
        errorMessage: String? = nil
    ) -> OpenCodeSessionComposerState {
        .question(
            .init(
                requestID: "question-gallery",
                sessionID: "session-gallery",
                questionIndex: questionIndex,
                questionCount: questionCount,
                header: header,
                question: question,
                options: sampleQuestionOptions,
                multiple: multiple,
                customAllowed: customAllowed,
                selectedLabels: selectedLabels,
                customText: customText,
                customSelected: customSelected,
                backEnabled: backEnabled,
                nextEnabled: nextEnabled,
                submitEnabled: submitEnabled,
                submitting: submitting,
                errorMessage: errorMessage
            )
        )
    }

    private func applyScenarioDefaults(_ option: SessionComposerGalleryScenario) {
        switch option {
        case .multiSelect:
            selectedLabels = ["Minimal patch", "Investigate first"]
            customSelected = false
            customText = ""
        case .custom:
            selectedLabels = []
            customSelected = true
            customText = "Ship a focused fix tonight"
        case .questionWizard:
            questionIndex = 0
            selectedLabels = ["Minimal patch"]
            customSelected = false
            customText = ""
        case .question:
            selectedLabels = ["Minimal patch"]
            customSelected = false
            customText = ""
        default:
            break
        }
    }

    private func handle(_ action: OpenCodeSessionComposerAction) {
        switch action {
        case let .textChanged(text, _, _):
            switch scenario {
            case .mentions:
                mentionText = text
                mentionsCleared = true
            default:
                draftText = text
            }
        case let .toggleQuestionOption(_, _, label):
            if scenario == .multiSelect {
                if selectedLabels.contains(label) {
                    selectedLabels.removeAll { $0 == label }
                } else {
                    selectedLabels.append(label)
                }
            } else {
                selectedLabels = [label]
            }
            customSelected = false
        case .toggleQuestionCustom:
            customSelected.toggle()
            if customSelected {
                selectedLabels = []
            }
        case let .questionCustomTextChanged(_, _, text):
            customText = text
        case .questionBack:
            questionIndex = max(questionIndex - 1, 0)
        case .questionNext:
            questionIndex = min(questionIndex + 1, 1)
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
        case .send: "Send"
        case .stop: "Stop"
        case .allowPermissionOnce: "Allow once"
        case .denyPermission: "Deny"
        case .toggleQuestionOption: "Toggle option"
        case .toggleQuestionCustom: "Toggle custom"
        case .questionCustomTextChanged: "Custom text changed"
        case .questionBack: "Back"
        case .questionNext: "Next"
        case .submitQuestion: "Submit"
        case .dismissQuestion: "Dismiss"
        }
    }
}

#Preview {
    SessionComposerGalleryView()
        .openCodeTheme()
}
