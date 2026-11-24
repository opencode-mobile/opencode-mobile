import SwiftUI

/// The Session Composer gallery's sample states, shared by the interactive gallery and the
/// component capture registry.
enum SessionComposerGalleryScenario: String, CaseIterable, Identifiable {
    case empty
    case prompt
    case mentions
    case active
    case attachments
    case revert
    case shell
    case sideQuestion
    case limitNote
    case childLockout
    case permission
    case permissionEdit
    case permissionNote
    case queued
    case subagentPermission
    case submitting
    case error
    case question
    case questionWizard
    case questionWizardLast
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
        case .limitNote: "Limit"
        case .childLockout: "Child"
        case .permission: "Permission"
        case .permissionEdit: "Edit"
        case .permissionNote: "Deny note"
        case .queued: "Queued"
        case .subagentPermission: "Subagent"
        case .submitting: "Submitting"
        case .error: "Error"
        case .question: "Question"
        case .questionWizard: "Wizard"
        case .questionWizardLast: "Wizard last"
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
private let limitNoteGalleryText = "/goal " + String(repeating: "Keep the public API the same. ", count: 4)

private let sampleQuestionOptions: [OpenCodeSessionComposerState.QuestionOption] = [
    .init(label: "Minimal patch", description: "Touch only the failing path"),
    .init(label: "Broader refactor", description: "Clean up nearby callers too"),
    .init(label: "Investigate first", description: "Gather more evidence before changing code"),
]

/// The editable sample data behind each scenario. The gallery mutates it as you interact;
/// `SessionComposerGalleryFixture(scenario:)` gives the state a scenario opens with.
struct SessionComposerGalleryFixture {
    var draftText = "Summarize the current changes"
    var mentionText = mentionGalleryText
    var shellText = "!git status"
    var sideQuestionText = "What did we change so far?"
    var sideQuestionMode = true
    var mentionsCleared = false
    var selectedLabels = ["Minimal patch"]
    var customSelected = false
    var customText = ""
    var questionIndex = 0
    /// "Deny with a note" draft of the permission scenarios; nil shows the prompt.
    var permissionNote: String?
    /// Prompt states show the dictation (mic) button.
    var showsDictationButton = false

    init() {}

    /// The data a scenario opens with in the gallery.
    init(scenario: SessionComposerGalleryScenario) {
        applyDefaults(for: scenario)
    }

    mutating func applyDefaults(for option: SessionComposerGalleryScenario) {
        permissionNote = option == .permissionNote ? "Run the tests with --watch=false instead" : nil
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
        case .questionWizardLast:
            questionIndex = 1
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

    var questionAnswered: Bool {
        if customSelected {
            !customText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        } else {
            !selectedLabels.isEmpty
        }
    }

    func state(for scenario: SessionComposerGalleryScenario) -> OpenCodeSessionComposerState {
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
        case .limitNote:
            promptState(
                text: limitNoteGalleryText,
                sendEnabled: false,
                footnote: .init(text: "4,312 / 4,000 characters · shorten the objective to send", isError: true)
            )
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
        case .permissionNote:
            permissionState(queueCount: 2, otherPendingInSessionCount: 1)
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
        case .questionWizard, .questionWizardLast:
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
        sideQuestionMode: Bool = false,
        sendEnabled: Bool? = nil,
        footnote: OpenCodeSessionComposerFootnote? = nil
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
                sendEnabled: sendEnabled ?? (shellMode
                    ? ComposerShellCommand.command(text) != nil
                    : !text.isEmpty || !attachments.isEmpty),
                attachEnabled: !shellMode && !sideQuestionMode,
                showStop: showStop,
                stopEnabled: showStop,
                showDictationButton: showsDictationButton,
                shellMode: shellMode,
                sideQuestionMode: sideQuestionMode,
                footnote: footnote
            )
        )
    }

    private func permissionState(
        queuePosition: Int = 1,
        queueCount: Int = 1,
        attribution: OpenCodeSessionComposerState.RequestAttribution? = nil,
        submitting: Bool = false,
        errorMessage: String? = nil,
        edit: Bool = false,
        otherPendingInSessionCount: Int = 0
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
                errorMessage: errorMessage,
                note: permissionNote,
                otherPendingInSessionCount: otherPendingInSessionCount
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
}
