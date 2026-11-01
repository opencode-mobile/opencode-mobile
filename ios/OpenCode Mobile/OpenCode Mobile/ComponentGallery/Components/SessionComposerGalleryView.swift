import SwiftUI

private enum SessionComposerGalleryScenario: String, CaseIterable, Identifiable {
    case empty
    case prompt
    case mentions
    case active
    case attachments
    case revert
    case shell
    case sideQuestion
    case childLockout
    case permission
    case permissionEdit
    case queued
    case subagentPermission
    case submitting
    case error
    case question
    case questionWizard
    case multiSelect
    case multiSelectBounded
    case custom
    case questionSubmitting
    case questionError
    case subagentQuestion

    var id: String { rawValue }

    var label: String {
        switch self {
        case .empty: "Empty"
        case .prompt: "Prompt"
        case .mentions: "Mentions"
        case .active: "Active"
        case .attachments: "Attachments"
        case .revert: "Revert"
        case .shell: "Shell"
        case .sideQuestion: "/btw"
        case .childLockout: "Child"
        case .permission: "Permission"
        case .permissionEdit: "Edit"
        case .queued: "Queued"
        case .subagentPermission: "Subagent"
        case .submitting: "Submitting"
        case .error: "Error"
        case .question: "Question"
        case .questionWizard: "Wizard"
        case .multiSelect: "Multi-select"
        case .multiSelectBounded: "Up to 2"
        case .custom: "Custom"
        case .questionSubmitting: "Q Submit"
        case .questionError: "Q Error"
        case .subagentQuestion: "Q Subagent"
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
    @State private var shellText = "!git status"
    @State private var sideQuestionText = "What did we change so far?"
    @State private var sideQuestionMode = true
    @State private var mentionsCleared = false
    @State private var lastAction = "None"
    @State private var selectedLabels = ["Minimal patch"]
    @State private var customSelected = false
    @State private var customText = ""
    @State private var questionIndex = 0
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
                onAction: handle
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var inputFocusCaption: String {
        if !state.acceptsInputFocus { return "Input: none in this state" }
        return inputFocused ? "Input: focused" : "Input: not focused"
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
        case .revert:
            promptState(text: "Add a dark mode toggle to Settings", showPendingRevert: true)
        case .shell:
            promptState(text: shellText, shellMode: ComposerShellCommand.isShellMode(shellText))
        case .sideQuestion:
            promptState(text: sideQuestionText, showStop: true, sideQuestionMode: sideQuestionMode)
        case .childLockout:
            .childLockout(
                .init(
                    selectedModelLabel: "Claude Sonnet 4",
                    selectedVariantLabel: "High",
                    showVariant: true
                )
            )
        case .permission:
            permissionState()
        case .permissionEdit:
            permissionState(edit: true)
        case .queued:
            permissionState(queueCount: 3)
        case .subagentPermission:
            permissionState(
                queuePosition: 2,
                queueCount: 3,
                attribution: .init(
                    sessionID: "session-gallery-child",
                    letter: "G",
                    summary: "general · subagent wants to run: npm test"
                )
            )
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
        case .multiSelectBounded:
            questionState(
                questionIndex: 0,
                questionCount: 1,
                multiple: true,
                maxSelections: 2,
                customAllowed: false,
                selectedLabels: Array(selectedLabels.prefix(2)),
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
        case .subagentQuestion:
            questionState(
                questionIndex: 0,
                questionCount: 1,
                selectedLabels: selectedLabels,
                submitEnabled: questionAnswered,
                requestPosition: 1,
                requestCount: 2,
                attribution: .init(sessionID: "session-gallery-child", letter: "E", summary: "explore · subagent asks")
            )
        }
    }

    private func promptState(
        text: String,
        mentionRanges: [Range<Int>] = [],
        attachments: [OpenCodeAttachmentPresentation] = [],
        showPendingRevert: Bool = false,
        showStop: Bool = false,
        shellMode: Bool = false,
        sideQuestionMode: Bool = false
    ) -> OpenCodeSessionComposerState {
        .prompt(
            .init(
                text: text,
                mentionRanges: mentionRanges,
                attachments: attachments,
                showPendingRevert: showPendingRevert,
                selectedModelLabel: "Claude Sonnet 4",
                selectedModeLabel: "Build",
                selectedVariantLabel: "Default",
                showVariant: true,
                sendEnabled: shellMode
                    ? ComposerShellCommand.command(text) != nil
                    : !text.isEmpty || !attachments.isEmpty,
                attachEnabled: !shellMode && !sideQuestionMode,
                showStop: showStop,
                stopEnabled: showStop,
                shellMode: shellMode,
                sideQuestionMode: sideQuestionMode
            )
        )
    }

    private func permissionState(
        queuePosition: Int = 1,
        queueCount: Int = 1,
        attribution: OpenCodeSessionComposerState.RequestAttribution? = nil,
        submitting: Bool = false,
        errorMessage: String? = nil,
        edit: Bool = false
    ) -> OpenCodeSessionComposerState {
        .permission(
            .init(
                requestID: "permission-gallery",
                sessionID: "session-gallery",
                permission: edit ? "edit" : "bash",
                patterns: edit ? ["README.md", "notes/todo.md"] : ["git status --short", "git diff --stat"],
                files: edit
                    ? [
                        .init(path: "README.md", additions: 1, deletions: 1),
                        .init(path: "notes/todo.md", additions: 4, deletions: 0),
                    ]
                    : [],
                alwaysPatterns: edit ? ["*"] : ["git status *", "git diff *"],
                queuePosition: queuePosition,
                queueCount: queueCount,
                attribution: attribution,
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
        maxSelections: Int? = nil,
        customAllowed: Bool = true,
        selectedLabels: [String] = [],
        customText: String = "",
        customSelected: Bool = false,
        backEnabled: Bool = false,
        nextEnabled: Bool = false,
        submitEnabled: Bool = false,
        submitting: Bool = false,
        errorMessage: String? = nil,
        requestPosition: Int = 1,
        requestCount: Int = 1,
        attribution: OpenCodeSessionComposerState.RequestAttribution? = nil
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
                maxSelections: maxSelections,
                customAllowed: customAllowed,
                selectedLabels: selectedLabels,
                customText: customText,
                customSelected: customSelected,
                backEnabled: backEnabled,
                nextEnabled: nextEnabled,
                submitEnabled: submitEnabled,
                submitting: submitting,
                errorMessage: errorMessage,
                requestPosition: requestPosition,
                requestCount: requestCount,
                attribution: attribution
            )
        )
    }

    private func applyScenarioDefaults(_ option: SessionComposerGalleryScenario) {
        switch option {
        case .multiSelect, .multiSelectBounded:
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
            case .shell:
                shellText = text
            case .sideQuestion:
                sideQuestionText = text
            default:
                draftText = text
            }
        case .exitShellMode:
            shellText = ComposerShellCommand.exitShellMode(shellText)
        case .exitSideQuestionMode:
            sideQuestionMode = false
        case let .toggleQuestionOption(_, _, label):
            if scenario == .multiSelect || scenario == .multiSelectBounded {
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
