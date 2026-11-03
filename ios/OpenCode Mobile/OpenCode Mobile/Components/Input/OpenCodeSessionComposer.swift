import SwiftUI

enum OpenCodeSessionComposerState {
    struct Prompt {
        let text: String
        let mentionRanges: [Range<Int>]
        let selectionStart: Int?
        let selectionEnd: Int?
        let selectionRevision: Int
        let attachments: [OpenCodeAttachmentPresentation]
        let showPendingRevert: Bool
        let selectedModelLabel: String
        let selectedModeLabel: String
        let selectedVariantLabel: String
        let showVariant: Bool
        let sendEnabled: Bool
        /// When hardware-keyboard Return sends, decided from the live draft text.
        /// Defaults to exactly `sendEnabled`.
        let returnKeyRule: OpenCodeReturnKeyRule
        let attachEnabled: Bool
        let showStop: Bool
        let stopEnabled: Bool
        let followUpDelivery: FollowUpDelivery?
        let showDictationButton: Bool
        /// The draft starts with `!` and Send runs it as a shell command. The `!` is highlighted
        /// and a selected Shell badge replaces the model and agent chips; tapping it sends
        /// `.exitShellMode`.
        let shellMode: Bool
        /// This session's sent prompts, oldest first, for ↑ / ↓ recall.
        let history: [String]
        /// Send asks a `/btw` side question instead of prompting. A selected `/btw` badge replaces
        /// the model and agent chips and the field shows its own placeholder; tapping the badge or
        /// deleting on an empty field sends `.exitSideQuestionMode`.
        let sideQuestionMode: Bool

        init(
            text: String,
            mentionRanges: [Range<Int>] = [],
            selectionStart: Int? = nil,
            selectionEnd: Int? = nil,
            selectionRevision: Int = 0,
            attachments: [OpenCodeAttachmentPresentation] = [],
            showPendingRevert: Bool = false,
            selectedModelLabel: String,
            selectedModeLabel: String,
            selectedVariantLabel: String,
            showVariant: Bool,
            sendEnabled: Bool,
            returnKeyRule: OpenCodeReturnKeyRule? = nil,
            attachEnabled: Bool = true,
            showStop: Bool,
            stopEnabled: Bool,
            followUpDelivery: FollowUpDelivery? = nil,
            showDictationButton: Bool = false,
            shellMode: Bool = false,
            sideQuestionMode: Bool = false,
            history: [String] = []
        ) {
            self.text = text
            self.mentionRanges = mentionRanges
            self.selectionStart = selectionStart
            self.selectionEnd = selectionEnd
            self.selectionRevision = selectionRevision
            self.attachments = attachments
            self.showPendingRevert = showPendingRevert
            self.selectedModelLabel = selectedModelLabel
            self.selectedModeLabel = selectedModeLabel
            self.selectedVariantLabel = selectedVariantLabel
            self.showVariant = showVariant
            self.sendEnabled = sendEnabled
            self.returnKeyRule = returnKeyRule ?? .fixed(sendEnabled)
            self.attachEnabled = attachEnabled
            self.showStop = showStop
            self.stopEnabled = stopEnabled
            self.followUpDelivery = followUpDelivery
            self.showDictationButton = showDictationButton
            self.shellMode = shellMode
            self.sideQuestionMode = sideQuestionMode
            self.history = history
        }
    }

    /// The descendant subagent session asking a permission or question; Open shows that session.
    struct RequestAttribution: Equatable {
        let sessionID: String
        let letter: String
        /// "general · subagent wants to run: npm test" or "explore · subagent asks".
        let summary: String
    }

    /// A file an edit/write permission would change, with its diff size.
    struct PermissionFile: Hashable {
        let path: String
        let additions: Int
        let deletions: Int
    }

    struct Permission {
        let requestID: String
        let sessionID: String
        let permission: String
        let patterns: [String]
        /// Edit/write targets; shown as `path +N −N` instead of `patterns` when present.
        let files: [PermissionFile]
        /// Patterns "Always" would save; the Always action shows only when non-empty.
        let alwaysPatterns: [String]
        let queuePosition: Int
        let queueCount: Int
        let attribution: RequestAttribution?
        let submitting: Bool
        let actionsEnabled: Bool
        let errorMessage: String?

        init(
            requestID: String,
            sessionID: String,
            permission: String,
            patterns: [String],
            files: [PermissionFile] = [],
            alwaysPatterns: [String] = [],
            queuePosition: Int = 1,
            queueCount: Int = 1,
            attribution: RequestAttribution? = nil,
            submitting: Bool = false,
            actionsEnabled: Bool = true,
            errorMessage: String? = nil
        ) {
            self.requestID = requestID
            self.sessionID = sessionID
            self.permission = permission
            self.patterns = patterns
            self.files = files
            self.alwaysPatterns = alwaysPatterns
            self.queuePosition = queuePosition
            self.queueCount = queueCount
            self.attribution = attribution
            self.submitting = submitting
            self.actionsEnabled = actionsEnabled
            self.errorMessage = errorMessage
        }
    }

    struct QuestionOption: Identifiable, Hashable {
        let label: String
        let description: String

        var id: String { label }
    }

    struct Question {
        let requestID: String
        let sessionID: String
        let questionIndex: Int
        let questionCount: Int
        let header: String
        let question: String
        let options: [QuestionOption]
        let multiple: Bool
        /// Multiselect bounds from the form field; nil means unbounded.
        let minSelections: Int?
        let maxSelections: Int?
        let customAllowed: Bool
        let selectedLabels: [String]
        let customText: String
        let customSelected: Bool
        let backEnabled: Bool
        let nextEnabled: Bool
        let submitEnabled: Bool
        let submitting: Bool
        let errorMessage: String?
        /// 1-based position of this request among the waiting question requests.
        let requestPosition: Int
        let requestCount: Int
        let attribution: RequestAttribution?

        init(
            requestID: String,
            sessionID: String,
            questionIndex: Int,
            questionCount: Int,
            header: String,
            question: String,
            options: [QuestionOption],
            multiple: Bool = false,
            minSelections: Int? = nil,
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
            attribution: RequestAttribution? = nil
        ) {
            self.requestID = requestID
            self.sessionID = sessionID
            self.questionIndex = questionIndex
            self.questionCount = questionCount
            self.header = header
            self.question = question
            self.options = options
            self.multiple = multiple
            self.minSelections = minSelections
            self.maxSelections = maxSelections
            self.customAllowed = customAllowed
            self.selectedLabels = selectedLabels
            self.customText = customText
            self.customSelected = customSelected
            self.backEnabled = backEnabled
            self.nextEnabled = nextEnabled
            self.submitEnabled = submitEnabled
            self.submitting = submitting
            self.errorMessage = errorMessage
            self.requestPosition = requestPosition
            self.requestCount = requestCount
            self.attribution = attribution
        }
    }

    struct ChildLockout {
        let selectedModelLabel: String
        let selectedVariantLabel: String
        let showVariant: Bool
    }

    case prompt(Prompt)
    case permission(Permission)
    case question(Question)
    case childLockout(ChildLockout)
}

extension OpenCodeSessionComposerState {
    /// The prompt chords' view of this state (`OpenCodePromptChordRule`).
    var promptChordState: OpenCodePromptChordState {
        switch self {
        case let .permission(permission):
            .permission(
                requestID: permission.requestID,
                actionsEnabled: permission.actionsEnabled && !permission.submitting,
                alwaysOffered: !permission.alwaysPatterns.isEmpty
            )
        case let .question(question):
            .question(
                requestID: question.requestID,
                isLastStep: question.questionIndex >= question.questionCount - 1,
                nextEnabled: question.nextEnabled && !question.submitting,
                submitEnabled: question.submitEnabled && !question.submitting,
                dismissEnabled: !question.submitting
            )
        case .prompt, .childLockout:
            .none
        }
    }

    /// The button action a prompt chord presses, for this state's request.
    func action(for outcome: OpenCodePromptChordOutcome) -> OpenCodeSessionComposerAction? {
        switch (self, outcome) {
        case let (.permission(permission), .allowOnce):
            .allowPermissionOnce(requestID: permission.requestID, sessionID: permission.sessionID)
        case let (.permission(permission), .alwaysAllow):
            .alwaysAllowPermission(requestID: permission.requestID, sessionID: permission.sessionID)
        case let (.permission(permission), .deny):
            .denyPermission(requestID: permission.requestID, sessionID: permission.sessionID)
        case let (.question(question), .next):
            .questionNext(requestID: question.requestID, sessionID: question.sessionID)
        case let (.question(question), .submit):
            .submitQuestion(requestID: question.requestID, sessionID: question.sessionID)
        case let (.question(question), .dismiss):
            .dismissQuestion(requestID: question.requestID, sessionID: question.sessionID)
        default:
            nil
        }
    }

    /// Whether the composer has a prompt input that a focus request can focus.
    var acceptsInputFocus: Bool {
        if case .prompt = self { return true }
        return false
    }
}

enum OpenCodeSessionComposerAction {
    case textChanged(text: String, selectionStart: Int, selectionEnd: Int)
    case selectModel
    case selectMode
    case selectVariant
    case attach
    case openCommands
    case removeAttachment(String)
    /// An image or file was pasted into the prompt; only sent while the prompt's `attachEnabled`.
    case pasteAttachments([NSItemProvider])
    case cancelRevert
    /// Drop the leading `!` so the draft is a prompt again.
    case exitShellMode
    /// Leave `/btw` side-question mode; the draft stays.
    case exitSideQuestionMode
    case send
    case toggleFollowUp
    case stop
    case allowPermissionOnce(requestID: String, sessionID: String)
    case denyPermission(requestID: String, sessionID: String)
    case alwaysAllowPermission(requestID: String, sessionID: String)
    /// Collapse the permission dock without answering it.
    case hidePermission(requestID: String)
    /// Collapse the question panel without answering or dismissing it.
    case hideQuestion(requestID: String)
    /// Page the permission or question panel to another waiting request; `delta` is +1 or -1.
    case pageRequest(delta: Int)
    /// Open the subagent session a request comes from.
    case openRequestSession(sessionID: String)
    case toggleQuestionOption(requestID: String, sessionID: String, label: String)
    case toggleQuestionCustom(requestID: String, sessionID: String)
    case questionCustomTextChanged(requestID: String, sessionID: String, text: String)
    case questionBack(requestID: String, sessionID: String)
    case questionNext(requestID: String, sessionID: String)
    case submitQuestion(requestID: String, sessionID: String)
    case dismissQuestion(requestID: String, sessionID: String)
    case backToMainSession
    case beginDictation
    case dictationCancel
    case dictationInsert
    case dictationOpenSettings
    case dictationDownload
    case dictationRetry
}

struct OpenCodeSessionComposerDictation: Equatable {
    var variant: OpenCodeDictationCardVariant
    var committed: String
    var volatileText: String
    var level: Double
    var status: String
    var startedAt: Date
    var languageLabel: String = ""
}

/// Named keyboard focus targets in the prompt composer.
enum OpenCodeComposerFocusTarget: Hashable {
    /// The first chip after the prompt field, where Tab out of the field goes.
    case afterPrompt
}

/// A text input that states what it is for keyboard-shortcut gating: the
/// composer prompt, a question's "Type your own answer", or another field.
protocol OpenCodeComposerTextInput: AnyObject {
    var textInputRole: OpenCodeTextInputFocus { get }
}

/// Where the caret is when ↑ / ↓ reaches the prompt (`OpenCodePromptHistoryBrowser`).
struct OpenCodePromptCaret {
    let atStart: Bool
    let onFirstLine: Bool
    let onLastLine: Bool
}

struct OpenCodeSessionComposer: View {
    let state: OpenCodeSessionComposerState
    /// Change the value to move keyboard focus (caret) into the prompt input. Only
    /// the `.prompt` state takes input; a change while showing a permission,
    /// question, or child-session lockout is ignored, and the value present when
    /// the prompt appears never focuses it.
    let focusRequestToken: Int
    /// Called with true when the prompt input gains keyboard focus, false when it loses it.
    let onInputFocusChange: ((Bool) -> Void)?
    let dictation: OpenCodeSessionComposerDictation?
    /// True while an image or file is dragged over the screen that hosts the composer: the prompt
    /// leaves the drop to that screen instead of taking it as text.
    let attachmentDragActive: Bool
    /// "Esc again to interrupt": the first Esc armed an interrupt of the running turn.
    let interruptHintVisible: Bool
    let onAction: (OpenCodeSessionComposerAction) -> Void
    private let status: AnyView?

    /// Keyboard focus inside permission and question prompts.
    @State private var focusRequester = OpenCodeFocusRequester()
    @State private var promptEntryOwner = UUID()
    @State private var customAnswerFocused = false
    /// `OpenCodeKeyboardFocusState.navigationKeyCount` when the current request arrived.
    @State private var navigationKeysAtArrival = 0
    /// A control of the current request had keyboard focus.
    @State private var focusWasInPrompt = false
    /// ↑ / ↓ prompt recall.
    @State private var historyBrowser = OpenCodePromptHistoryBrowser()

    init(
        state: OpenCodeSessionComposerState,
        focusRequestToken: Int = 0,
        onInputFocusChange: ((Bool) -> Void)? = nil,
        dictation: OpenCodeSessionComposerDictation? = nil,
        attachmentDragActive: Bool = false,
        interruptHintVisible: Bool = false,
        onAction: @escaping (OpenCodeSessionComposerAction) -> Void,
        status: AnyView? = nil
    ) {
        self.state = state
        self.focusRequestToken = focusRequestToken
        self.onInputFocusChange = onInputFocusChange
        self.dictation = dictation
        self.attachmentDragActive = attachmentDragActive
        self.interruptHintVisible = interruptHintVisible
        self.onAction = onAction
        self.status = status
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let status {
                status
                Rectangle()
                    .fill(OpenCodeColors.borderWeakBase)
                    .frame(height: 1)
            }
            VStack(alignment: .leading, spacing: 10) {
                if interruptHintVisible {
                    OpenCodeKeyboardHint(OpenCodeInterruptKeyboardHint.text, tone: .warning)
                        .accessibilityIdentifier("sessionChatInterruptHint")
                }
                // The shell is pinned to the bottom of the screen, so the content is too: both
                // modes stay still and crossfade while the shell resizes around them, which
                // reads as one surface morphing instead of the old content sliding down.
                ComposerModeStack {
                    // A new identity per request: nothing (focus, the custom answer draft)
                    // carries over from one queued request to the next.
                    VStack(alignment: .leading, spacing: 10) {
                        switch state {
                        case let .permission(permission):
                            permissionApprovalContent(permission)
                        case let .question(question):
                            questionRequestContent(question)
                        case let .childLockout(lockout):
                            childLockoutContent(lockout)
                        case let .prompt(prompt):
                            promptEntryContent(prompt)
                        }
                    }
                    .id(promptRequestID ?? contentKind)
                    // Bare 1–9 picks a question option, only while focus is inside the
                    // prompt (key presses reach here only from a focused descendant).
                    .onKeyPress(characters: .decimalDigits, phases: .down) { press in
                        pickOption(with: press.characters, modifiers: press.modifiers)
                    }
                    .transition(.opacity)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
            .simultaneousGesture(requestPageSwipe, including: pagesRequests ? .all : .subviews)
        }
        .clipShape(RoundedRectangle(cornerRadius: Self.shellCornerRadius, style: .continuous))
        .openCodeFloatingChrome(cornerRadius: Self.shellCornerRadius)
        .overlay {
            if let outlineColor {
                RoundedRectangle(cornerRadius: Self.shellCornerRadius, style: .continuous)
                    .stroke(outlineColor, lineWidth: 1)
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
        .animation(Self.morphAnimation, value: contentKind)
        .animation(Self.morphAnimation, value: dictation != nil)
        .accessibilityIdentifier(composerAccessibilityIdentifier)
        .openCodeFocusRequester(focusRequester)
        .onChange(of: promptInitialTarget, initial: true) { _, target in
            publishPromptEntry(target)
        }
        .onChange(of: focusRequester.focusedKey) { _, key in
            if key != nil, promptRequestID != nil { focusWasInPrompt = true }
        }
        .onChange(of: promptRequestID) { previous, requestID in
            let leftWithFocus = OpenCodePromptFocus.clearsFocusOnLeave(
                previousRequestID: previous,
                requestID: requestID,
                focusWasInPrompt: focusWasInPrompt
            )
            focusWasInPrompt = false
            if leftWithFocus, requestID == nil {
                OpenCodeKeyboardAnchor.clearAutomaticFocus()
            }
            guard OpenCodePromptFocus.clearsFocusOnRequestChange(
                previousRequestID: previous,
                requestID: requestID
            ) else { return }
            navigationKeysAtArrival = OpenCodeKeyboardFocusState.shared.navigationKeyCount
            // A Tab target handed over before the prompt came (Tab out of the
            // composer, a closed sheet's opener) would win over the prompt's entry.
            OpenCodeKeyboardFocusState.shared.clearNextTabTarget()
            clearFocusForArrivingPrompt()
        }
        .onChange(of: customAnswerSelected) { _, selected in
            // Picking "Type your own answer" with the keyboard moves into its field,
            // which iPadOS cannot reach with Tab.
            if selected,
               OpenCodeKeyboardFocusState.shared.hardwareKeyboardConnected,
               focusRequester.focusedKey == AnyHashable(OpenCodePromptFocusTarget.customOption) {
                customAnswerFocused = true
            }
        }
        .onDisappear {
            OpenCodeKeyboardFocusState.shared.setPromptEntry(promptEntryOwner, key: OpenCodePromptFocusTarget?.none)
        }
    }

    /// iPadOS cannot move focus in code out of the UIKit answer field: stop editing
    /// and make `target` the next Tab stop.
    private func leaveCustomAnswer(toward target: OpenCodePromptFocusTarget) {
        OpenCodeKeyboardFocusState.shared.setNextTabTarget(target)
        customAnswerFocused = false
        OpenCodeKeyboardAnchor.takeFocus()
    }

    private func pickOption(with characters: String, modifiers: EventModifiers) -> KeyPress.Result {
        guard case let .question(question) = state, characters.count == 1, let character = characters.first,
              let index = OpenCodeQuestionDigitPick.optionIndex(
                  for: character,
                  modifiers: modifiers,
                  optionCount: question.options.count,
                  focusInsidePrompt: focusRequester.focusedKey != nil,
                  textFieldFocused: customAnswerFocused,
                  optionsEnabled: !question.submitting
              )
        else { return .ignored }
        onAction(.toggleQuestionOption(
            requestID: question.requestID,
            sessionID: question.sessionID,
            label: question.options[index].label
        ))
        return .handled
    }

    /// The permission or question request on screen; nil for the prompt and lockout.
    private var promptRequestID: String? {
        switch state {
        case let .permission(permission): permission.requestID
        case let .question(question): question.requestID
        case .prompt, .childLockout: nil
        }
    }

    private var customAnswerSelected: Bool {
        if case let .question(question) = state { return question.customSelected }
        return false
    }

    /// While a prompt waits and nothing has keyboard focus, the first Tab moves
    /// focus to this target. Nothing in the prompt is focused before that, so a
    /// stray Return or Space cannot answer it.
    private var promptInitialTarget: OpenCodePromptFocusTarget? {
        switch state {
        case let .permission(permission):
            let enabled = permission.actionsEnabled && !permission.submitting
            return OpenCodePromptFocus.initialTarget(
                optionCount: 0,
                customAllowed: false,
                optionsEnabled: false,
                actionsEnabled: [enabled]
            )
        case let .question(question):
            return OpenCodePromptFocus.initialTarget(
                optionCount: question.options.count,
                customAllowed: question.customAllowed,
                optionsEnabled: !question.submitting,
                actionsEnabled: [!question.submitting]
            )
        case .prompt, .childLockout:
            return nil
        }
    }

    private func publishPromptEntry(_ target: OpenCodePromptFocusTarget?) {
        OpenCodeKeyboardFocusState.shared.setPromptEntry(promptEntryOwner, key: target)
    }

    /// iPadOS refocuses on its own when the focused composer control goes away;
    /// check again once that has happened. Focus counts as the user's only if a Tab
    /// or arrow key moved it after the request arrived.
    private func clearFocusForArrivingPrompt() {
        for delay in [0.0, 0.3] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                let focusState = OpenCodeKeyboardFocusState.shared
                guard OpenCodePromptFocus.clearsFocusOnArrival(
                    hardwareKeyboardConnected: focusState.hardwareKeyboardConnected,
                    focusInsidePrompt: OpenCodePromptFocus.focusEnteredByKeyboard(
                        focusInPrompt: focusRequester.focusedKey != nil || customAnswerFocused,
                        navigationKeysSinceArrival: focusState.navigationKeyCount > navigationKeysAtArrival
                    ),
                    otherTextFieldFocused: OpenCodeTextInputFocusMonitor.shared.focus == .otherTextField
                ) else { return }
                OpenCodeKeyboardAnchor.takeFocus()
            }
        }
    }

    /// "Press Tab to answer with the keyboard", until focus is inside the prompt.
    @ViewBuilder
    private var promptKeyboardHint: some View {
        if OpenCodePromptKeyboardHint.isVisible(
            hardwareKeyboardConnected: OpenCodeKeyboardFocusState.shared.hardwareKeyboardConnected,
            focusInsidePrompt: focusRequester.focusedKey != nil || customAnswerFocused
        ) {
            OpenCodeKeyboardHint(OpenCodePromptKeyboardHint.text)
                .accessibilityIdentifier("sessionChatPromptKeyboardHint")
        }
    }

    private var composerAccessibilityIdentifier: String {
        switch state {
        case .permission:
            "sessionChatPermission"
        default:
            "sessionChatComposer"
        }
    }

    @ViewBuilder
    private func promptEntryContent(_ prompt: OpenCodeSessionComposerState.Prompt) -> some View {
        if let dictation {
            dictationContent(dictation)
        } else {
            promptFieldContent(prompt)
        }
    }

    @ViewBuilder
    private func promptFieldContent(_ prompt: OpenCodeSessionComposerState.Prompt) -> some View {
            if prompt.showPendingRevert || !prompt.attachments.isEmpty {
                VStack(spacing: 8) {
                    if prompt.showPendingRevert {
                        pendingRevertChip
                    }
                    ForEach(prompt.attachments) { attachment in
                        OpenCodeAttachmentChip(attachment: attachment) { id in
                            onAction(.removeAttachment(id))
                        }
                    }
                }
            }

            OpenCodeMentionTextView(
                text: prompt.text,
                // The shell prefix shares the mention highlight so the mode reads in the text itself.
                mentionRanges: prompt.shellMode ? [0 ..< 1] + prompt.mentionRanges : prompt.mentionRanges,
                selectionStart: prompt.selectionStart,
                selectionEnd: prompt.selectionEnd,
                selectionRevision: prompt.selectionRevision,
                placeholder: prompt.sideQuestionMode ? Self.sideQuestionPlaceholder : Self.promptPlaceholder,
                focusRequestToken: focusRequestToken,
                onFocusChange: onInputFocusChange,
                literalInput: prompt.shellMode,
                onDeleteBackwardWhenEmpty: prompt.sideQuestionMode ? { onAction(.exitSideQuestionMode) } : nil,
                onPasteAttachments: prompt.attachEnabled ? { onAction(.pasteAttachments($0)) } : nil,
                acceptsTextDrops: !attachmentDragActive,
                returnKey: OpenCodeHardwareReturnKey(rule: prompt.returnKeyRule) { onAction(.send) },
                onHistoryKey: { direction, text, caret in
                    var browser = historyBrowser
                    let recalled = browser.step(
                        direction,
                        history: prompt.history,
                        text: text,
                        hasAttachments: !prompt.attachments.isEmpty,
                        caretAtStart: caret.atStart,
                        caretOnFirstLine: caret.onFirstLine,
                        caretOnLastLine: caret.onLastLine
                    )
                    historyBrowser = browser
                    guard let recalled else { return false }
                    onAction(.textChanged(text: recalled, selectionStart: recalled.count, selectionEnd: recalled.count))
                    return true
                },
                onTabOut: { backward in
                    // iPadOS cannot move focus in code out of the UIKit prompt field:
                    // stop editing and make the first chip after it the next Tab stop.
                    if !backward {
                        OpenCodeKeyboardFocusState.shared.setNextTabTarget(OpenCodeComposerFocusTarget.afterPrompt)
                    }
                    OpenCodeKeyboardAnchor.takeFocus()
                },
                onChange: { text, selectionStart, selectionEnd in
                    onAction(.textChanged(text: text, selectionStart: selectionStart, selectionEnd: selectionEnd))
                }
            )
            .padding(.horizontal, 4)
            .padding(.vertical, 4)

            pickerChipsRow(prompt)

            HStack {
                HStack(spacing: 8) {
                    OpenCodeButton(
                        variant: .iconCircle(.add, accessibilityLabel: "component.composer.add_attachment"),
                        enabled: prompt.attachEnabled,
                        contrast: .low,
                        action: { onAction(.attach) }
                    )

                    OpenCodeButton(
                        variant: .iconCircle(.slash, accessibilityLabel: "component.composer.open_commands"),
                        contrast: .low,
                        action: { onAction(.openCommands) }
                    )

                    if prompt.showDictationButton {
                        OpenCodeButton(
                            variant: .iconCircle(.mic, accessibilityLabel: "component.composer.dictate"),
                            contrast: .low,
                            action: { onAction(.beginDictation) }
                        )
                    }
                }

                Spacer(minLength: 0)

                HStack(spacing: 8) {
                    if prompt.showStop {
                        OpenCodeButton(
                            variant: .iconCircle(.stop, accessibilityLabel: "component.composer.abort"),
                            enabled: prompt.stopEnabled,
                            contrast: .low,
                            action: { onAction(.stop) }
                        )
                    }

                    OpenCodeButton(
                        variant: .iconCircle(
                            prompt.followUpDelivery == .steer && !prompt.sideQuestionMode ? .arrowWarmUp : .arrowUpward,
                            accessibilityLabel: .localized(Self.sendLabel(
                                followUpDelivery: prompt.followUpDelivery,
                                hasActiveWork: prompt.showStop,
                                shellMode: prompt.shellMode,
                                sideQuestionMode: prompt.sideQuestionMode
                            ))
                        ),
                        enabled: prompt.sendEnabled,
                        // A side question is not a prompt, so its Send does not look like one.
                        contrast: prompt.sideQuestionMode ? .high : .accent,
                        longPress: prompt.followUpDelivery == nil ? nil : { onAction(.toggleFollowUp) },
                        action: {
                            // OPE-138: send leaves editing intent. Mention UITextView is
                            // outside SwiftUI @FocusState, so resign first responder.
                            dismissComposerKeyboard()
                            onAction(.send)
                        }
                    )
                }
            }
    }

    private func dictationContent(_ dictation: OpenCodeSessionComposerDictation) -> some View {
        let showsClock = dictation.variant == .listening || dictation.variant == .finishing
        return Group {
            if showsClock {
                TimelineView(.periodic(from: dictation.startedAt, by: 1)) { context in
                    dictationCard(
                        dictation,
                        elapsed: Self.dictationElapsedLabel(from: dictation.startedAt, to: context.date)
                    )
                }
            } else {
                dictationCard(
                    dictation,
                    elapsed: Self.dictationElapsedLabel(from: dictation.startedAt, to: Date())
                )
            }
        }
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func dictationCard(_ dictation: OpenCodeSessionComposerDictation, elapsed: String) -> some View {
        OpenCodeDictationCard(
            variant: dictation.variant,
            committed: dictation.committed,
            volatileText: dictation.volatileText,
            level: dictation.level,
            status: dictation.status,
            elapsed: elapsed,
            languageLabel: dictation.languageLabel,
            onCancel: { onAction(.dictationCancel) },
            onInsert: { onAction(.dictationInsert) },
            onUnavailableAction: { onAction(dictationUnavailableAction(dictation.variant)) }
        )
    }

    private func dictationUnavailableAction(
        _ variant: OpenCodeDictationCardVariant
    ) -> OpenCodeSessionComposerAction {
        switch variant {
        case .unavailable(.permissionDenied):
            .dictationOpenSettings
        case .unavailable(.modelNotInstalled), .unavailable(.modelDownloading), .unavailable(.modelDownloadFailed):
            .dictationDownload
        case .unavailable(.recognizerError):
            .dictationRetry
        case .listening, .finishing:
            .dictationInsert
        }
    }

    private static func dictationElapsedLabel(from start: Date, to now: Date) -> String {
        let elapsed = max(0, Int(now.timeIntervalSince(start)))
        return String(format: "%d:%02d", elapsed / 60, elapsed % 60)
    }

    private var pendingRevertChip: some View {
        HStack(spacing: 8) {
            OpenCodeIconImage(icon: .undo, size: 28)
                .foregroundStyle(OpenCodeColors.iconBase)
            VStack(alignment: .leading, spacing: 2) {
                Text("component.composer.reverting_title")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                Text("component.composer.revert_tag").textCase(.uppercase)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(Self.revertingAccessibilityLabel))
            Button(action: { onAction(.cancelRevert) }) {
                OpenCodeIconImage(icon: .close, size: 20)
                    .foregroundStyle(OpenCodeColors.iconStrongBase)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("component.composer.cancel_revert"))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1))
        .accessibilityElement(children: .contain)
    }

    /// Mention prompt uses `UITextView`; resign is the reliable dismiss path
    /// (same escape hatch as timeline drag dismiss in Session Chat).
    private func dismissComposerKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }

    /// Horizontal model / mode / variant chips. Scrolls only when the row is
    /// wider than the composer — bounce is size-based so a fitting row does not
    /// rubber-band.
    @ViewBuilder
    private func pickerChipsRow(_ prompt: OpenCodeSessionComposerState.Prompt) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            let chips = HStack(spacing: 8) {
                if prompt.sideQuestionMode {
                    OpenCodeChoiceBadge(
                        label: .verbatim(Self.sideQuestionCommand),
                        selected: true,
                        variant: .glass,
                        action: { onAction(.exitSideQuestionMode) }
                    )
                    .openCodeFocusKey(OpenCodeComposerFocusTarget.afterPrompt)
                    .accessibilityLabel(Text("component.composer.side_question_mode"))
                    .accessibilityHint(Text("component.composer.leave_side_question_mode"))
                } else if prompt.shellMode {
                    // Like the TUI, shell mode shows "Shell" in place of the agent and model.
                    OpenCodeChoiceBadge(
                        label: "component.composer.shell",
                        selected: true,
                        variant: .glass,
                        action: { onAction(.exitShellMode) }
                    )
                    .openCodeFocusKey(OpenCodeComposerFocusTarget.afterPrompt)
                    .accessibilityLabel(Text("component.composer.shell_mode"))
                    .accessibilityHint(Text("component.composer.leave_shell_mode"))
                } else {
                    OpenCodeChoiceBadge(
                        label: .verbatim(prompt.selectedModelLabel),
                        selected: false,
                        variant: .glass,
                        action: { onAction(.selectModel) }
                    )
                    .openCodeFocusKey(OpenCodeComposerFocusTarget.afterPrompt)
                    OpenCodeChoiceBadge(
                        label: .verbatim(prompt.selectedModeLabel),
                        selected: false,
                        variant: .glass,
                        action: { onAction(.selectMode) }
                    )
                    if prompt.showVariant {
                        OpenCodeChoiceBadge(
                            label: .unlocalized(prompt.selectedVariantLabel),
                            selected: false,
                            variant: .glass,
                            action: { onAction(.selectVariant) }
                        )
                    }
                }
            }
            if #available(iOS 26, *) {
                GlassEffectContainer(spacing: 8) {
                    chips
                }
            } else {
                chips
            }
        }
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
    }

    @ViewBuilder
    private func childLockoutContent(_ lockout: OpenCodeSessionComposerState.ChildLockout) -> some View {
        HStack(spacing: 8) {
            lockoutChip(lockout.selectedModelLabel)
            if lockout.showVariant {
                lockoutChip(lockout.selectedVariantLabel)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(lockoutAccessibilityLabel(lockout))
        Text("component.composer.child_lockout")
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textBase)
            .padding(.horizontal, 4)
        Button(action: { onAction(.backToMainSession) }) {
            Text("component.composer.back_to_main_session")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .openCodeSubtleControlChrome(shape: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(OpenCodeSubtleControlPressStyle(shape: Capsule()))
    }

    private func lockoutChip(_ label: String) -> some View {
        Text(label)
            .font(OpenCodeTypography.button)
            .foregroundStyle(OpenCodeColors.textStrong)
            .lineLimit(1)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(minHeight: 36)
            .background(OpenCodeColors.textStrong.opacity(0.14), in: Capsule())
    }

    private func lockoutAccessibilityLabel(_ lockout: OpenCodeSessionComposerState.ChildLockout) -> String {
        let model = lockout.selectedModelLabel
        if lockout.showVariant {
            let effort = lockout.selectedVariantLabel
            return String(localized: LocalizedStringResource(
                "component.composer.model_effort",
                defaultValue: "Model \(model), effort \(effort)"
            ))
        }
        return String(localized: LocalizedStringResource("component.composer.model", defaultValue: "Model \(model)"))
    }

    @ViewBuilder
    private func permissionApprovalContent(_ permission: OpenCodeSessionComposerState.Permission) -> some View {
        let actionsEnabled = permission.actionsEnabled && !permission.submitting
        if let attribution = permission.attribution {
            requestAttributionRow(attribution)
        }
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("component.composer.permission_requested").font(OpenCodeTypography.heading)
                Spacer()
                requestPager(
                    position: permission.queuePosition,
                    count: permission.queueCount,
                    enabled: !permission.submitting,
                    kind: .permission
                )
                OpenCodeButton(
                    variant: .iconCircle(.keyboardArrowDown, accessibilityLabel: "component.composer.hide_permission"),
                    contrast: .low,
                    action: { onAction(.hidePermission(requestID: permission.requestID)) }
                )
            }
            Text(permission.permission.replacingOccurrences(of: "_", with: " "))
                .font(OpenCodeTypography.bodySecondary)
            if permission.files.isEmpty {
                ForEach(Array(permission.patterns.enumerated()), id: \.offset) { _, pattern in
                    Text(pattern)
                        .font(OpenCodeTypography.code)
                        .textSelection(.enabled)
                }
            } else {
                ForEach(permission.files, id: \.self) { file in
                    permissionFileLine(file)
                }
            }
            if !permission.alwaysPatterns.isEmpty {
                Text(LocalizedStringResource(
                    "component.composer.always_allows",
                    defaultValue: "Always allows: \(permission.alwaysPatterns.joined(separator: ", "))"
                ))
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
            }
            if let errorMessage = permission.errorMessage {
                Text(errorMessage)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 4)
        HStack(spacing: 8) {
            OpenCodeButton(
                variant: .compactText(title: "component.composer.deny"),
                enabled: actionsEnabled,
                contrast: .low,
                action: {
                    onAction(.denyPermission(requestID: permission.requestID, sessionID: permission.sessionID))
                }
            )
            .openCodeFocusKey(OpenCodePromptFocusTarget.action(0))
            Spacer(minLength: 0)
            if permission.submitting {
                ProgressView().controlSize(.small)
            }
            if !permission.alwaysPatterns.isEmpty {
                OpenCodeButton(
                    variant: .compactText(title: "component.composer.always"),
                    enabled: actionsEnabled,
                    contrast: .low,
                    action: {
                        onAction(.alwaysAllowPermission(requestID: permission.requestID, sessionID: permission.sessionID))
                    }
                )
                .openCodeFocusKey(OpenCodePromptFocusTarget.action(1))
            }
            OpenCodeButton(
                variant: .compactText(title: "component.composer.allow_once"),
                enabled: actionsEnabled,
                action: {
                    onAction(.allowPermissionOnce(requestID: permission.requestID, sessionID: permission.sessionID))
                }
            )
            .openCodeFocusKey(OpenCodePromptFocusTarget.primaryAction)
        }
        .openCodeFocusGroup(.horizontal)
        requestPageDots(position: permission.queuePosition, count: permission.queueCount)
        promptKeyboardHint
    }

    /// Light amber row naming the subagent that asks, with Open to show its session.
    private func requestAttributionRow(_ attribution: OpenCodeSessionComposerState.RequestAttribution) -> some View {
        HStack(spacing: 8) {
            Text(attribution.letter)
                .font(OpenCodeTypography.caption.weight(.semibold))
                .foregroundStyle(OpenCodeColors.textStrong)
                .frame(width: 22, height: 22)
                .background(Circle().fill(OpenCodeColors.warning.opacity(0.28)))
                .accessibilityHidden(true)
            Text(attribution.summary)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textStrong)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 4)
            OpenCodeButton(
                variant: .compactText(title: "common.open"),
                contrast: .low,
                action: { onAction(.openRequestSession(sessionID: attribution.sessionID)) }
            )
            .accessibilityLabel(Text("component.composer.open_subagent_session"))
        }
        .padding(.leading, 8)
        .padding(.trailing, 4)
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(OpenCodeColors.warning.opacity(0.12))
        )
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("sessionChatRequestAttribution")
    }

    /// "‹ 1 of 2 ›" beside the panel title while more than one request waits.
    @ViewBuilder
    private func requestPager(position: Int, count: Int, enabled: Bool, kind: RequestPagerKind) -> some View {
        if count > 1 {
            HStack(spacing: 2) {
                OpenCodeButton(
                    variant: .iconCircle(.arrowBack, accessibilityLabel: kind.previous),
                    enabled: enabled && position > 1,
                    contrast: .low,
                    action: { onAction(.pageRequest(delta: -1)) }
                )
                Text(LocalizedStringResource("component.composer.request_position", defaultValue: "\(position) of \(count)"))
                    .font(OpenCodeTypography.caption)
                    .monospacedDigit()
                OpenCodeButton(
                    variant: .iconCircle(.arrowForward, accessibilityLabel: kind.next),
                    enabled: enabled && position < count,
                    contrast: .low,
                    action: { onAction(.pageRequest(delta: 1)) }
                )
            }
        }
    }

    @ViewBuilder
    private func requestPageDots(position: Int, count: Int) -> some View {
        if count > 1 {
            OpenCodePageIndicator(pageCount: count, currentPage: min(max(position - 1, 0), count - 1))
                .frame(maxWidth: .infinity)
        }
    }

    /// Horizontal swipe on a request panel pages between waiting requests.
    private var requestPageSwipe: some Gesture {
        DragGesture(minimumDistance: 24)
            .onEnded { value in
                let dx = value.translation.width
                guard abs(dx) > 60, abs(dx) > abs(value.translation.height) * 1.5 else { return }
                onAction(.pageRequest(delta: dx < 0 ? 1 : -1))
            }
    }

    private func permissionFileLine(_ file: OpenCodeSessionComposerState.PermissionFile) -> some View {
        var line = Text(file.path)
        if file.additions > 0 || file.deletions > 0 {
            line = line
                + Text("  ")
                + Text("+\(file.additions)").foregroundColor(OpenCodeColors.diffAdd)
                + Text(" ")
                + Text("\u{2212}\(file.deletions)").foregroundColor(OpenCodeColors.diffDelete)
        }
        return line
            .font(OpenCodeTypography.code)
            .textSelection(.enabled)
    }

    private static let questionCustomAnswerID = "questionCustomAnswer"

    private func revealQuestionCustomAnswer(
        _ question: OpenCodeSessionComposerState.Question,
        proxy: ScrollViewProxy
    ) {
        guard question.customSelected else { return }
        withAnimation(.easeOut(duration: 0.2)) {
            proxy.scrollTo(Self.questionCustomAnswerID, anchor: .bottom)
        }
    }

    private func questionBody(
        _ question: OpenCodeSessionComposerState.Question,
        selectionHint: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(question.header.isEmpty ? String(localized: "component.composer.question", defaultValue: "Question") : question.header)
                    .font(OpenCodeTypography.heading)
                Spacer()
                if question.questionCount > 1 {
                    // With a request pager beside it, name this counter so the two read apart.
                    Text(Self.questionPosition(question))
                        .font(OpenCodeTypography.caption)
                }
                requestPager(
                    position: question.requestPosition,
                    count: question.requestCount,
                    enabled: !question.submitting,
                    kind: .question
                )
                OpenCodeButton(
                    variant: .iconCircle(.keyboardArrowDown, accessibilityLabel: "component.composer.hide_question"),
                    contrast: .low,
                    action: { onAction(.hideQuestion(requestID: question.requestID)) }
                )
            }
            Text(question.question)
                .font(OpenCodeTypography.bodySecondary)
            Text(selectionHint)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)

            VStack(spacing: 8) {
                ForEach(Array(question.options.enumerated()), id: \.element.id) { index, option in
                    let optionSelected = question.selectedLabels.contains(option.label)
                    OpenCodeListItem(
                        title: .verbatim(option.label),
                        subtitle: .verbatim(ifPresent: option.description.isEmpty ? nil : option.description),
                        selected: optionSelected,
                        enabled: !question.submitting,
                        style: .card,
                        startDecorator: Self.questionSelectionDecorator(selected: optionSelected),
                        action: {
                            onAction(
                                .toggleQuestionOption(
                                    requestID: question.requestID,
                                    sessionID: question.sessionID,
                                    label: option.label
                                )
                            )
                        }
                    )
                    .openCodeFocusKey(OpenCodePromptFocusTarget.option(index))
                }

                if question.customAllowed {
                    OpenCodeListItem(
                        title: "component.composer.custom_answer_title",
                        subtitle: .verbatim(ifPresent: question.customSelected || question.customText.isEmpty
                            ? nil
                            : question.customText),
                        selected: question.customSelected,
                        enabled: !question.submitting,
                        style: .card,
                        startDecorator: Self.questionSelectionDecorator(selected: question.customSelected),
                        action: {
                            onAction(
                                .toggleQuestionCustom(
                                    requestID: question.requestID,
                                    sessionID: question.sessionID
                                )
                            )
                        }
                    )
                    .openCodeFocusKey(OpenCodePromptFocusTarget.customOption)

                    if question.customSelected {
                        QuestionCustomTextField(
                            text: question.customText,
                            enabled: !question.submitting,
                            isFocused: $customAnswerFocused,
                            onReturn: {
                                // Return saves (the text is already saved as typed) and moves on.
                                let primaryEnabled = (question.questionIndex < question.questionCount - 1
                                    ? question.nextEnabled
                                    : question.submitEnabled) && !question.submitting
                                guard let target = OpenCodePromptFocus.targetAfterCustomAnswerReturn(
                                    primaryActionEnabled: primaryEnabled
                                ) else { return }
                                leaveCustomAnswer(toward: target)
                            },
                            onEscape: {
                                // Leave the field without answering; Tab goes back to its option.
                                leaveCustomAnswer(toward: .customOption)
                            },
                            onTabOut: { backward in
                                // The field sits between its option and the action buttons.
                                leaveCustomAnswer(toward: backward ? .customOption : .primaryAction)
                            },
                            onChange: { text in
                                onAction(
                                    .questionCustomTextChanged(
                                        requestID: question.requestID,
                                        sessionID: question.sessionID,
                                        text: text
                                    )
                                )
                            }
                        )
                        .id(Self.questionCustomAnswerID)
                    }
                }
            }
            .openCodeFocusGroup(.vertical)

            if let errorMessage = question.errorMessage {
                Text(errorMessage)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func questionRequestContent(_ question: OpenCodeSessionComposerState.Question) -> some View {
        let showBack = question.questionIndex > 0
        let showNext = question.questionIndex < question.questionCount - 1
        let selectionHint = Self.questionSelectionHint(
            multiple: question.multiple,
            minSelections: question.minSelections,
            maxSelections: question.maxSelections
        )

        if let attribution = question.attribution {
            requestAttributionRow(attribution)
        }
        // A long question scrolls inside its card so the header and the action row stay on screen.
        QuestionBodyHeightLayout {
            ScrollViewReader { proxy in
                ScrollView(.vertical) {
                    questionBody(question, selectionHint: selectionHint)
                }
                .scrollBounceBehavior(.basedOnSize)
                // Keep the custom answer in view while it is chosen, the keyboard shrinks the
                // body, or the answer grows a line.
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { _ in
                    revealQuestionCustomAnswer(question, proxy: proxy)
                }
                .onChange(of: question.customSelected) { _, _ in
                    revealQuestionCustomAnswer(question, proxy: proxy)
                }
                .onChange(of: question.customText) { _, _ in
                    revealQuestionCustomAnswer(question, proxy: proxy)
                }
            }
            // Each question and request opens at its top, not where the previous one was left.
            .id("\(question.requestID)#\(question.questionIndex)")
        }

        HStack {
            OpenCodeButton(
                variant: .compactText(title: "component.composer.dismiss"),
                enabled: !question.submitting,
                contrast: .low,
                action: {
                    onAction(.dismissQuestion(requestID: question.requestID, sessionID: question.sessionID))
                }
            )
            .openCodeFocusKey(OpenCodePromptFocusTarget.action(0))
            Spacer(minLength: 0)
            HStack(spacing: 8) {
                if question.submitting {
                    ProgressView().controlSize(.small)
                }
                if showBack {
                    OpenCodeButton(
                        variant: .compactText(title: "top_bar.back"),
                        enabled: question.backEnabled && !question.submitting,
                        contrast: .low,
                        action: {
                            onAction(.questionBack(requestID: question.requestID, sessionID: question.sessionID))
                        }
                    )
                    .openCodeFocusKey(OpenCodePromptFocusTarget.action(1))
                }
                if showNext {
                    OpenCodeButton(
                        variant: .compactText(title: "component.composer.next"),
                        enabled: question.nextEnabled && !question.submitting,
                        action: {
                            onAction(.questionNext(requestID: question.requestID, sessionID: question.sessionID))
                        }
                    )
                    .openCodeFocusKey(OpenCodePromptFocusTarget.primaryAction)
                } else {
                    OpenCodeButton(
                        variant: .compactText(title: "component.composer.submit"),
                        enabled: question.submitEnabled && !question.submitting,
                        action: {
                            onAction(.submitQuestion(requestID: question.requestID, sessionID: question.sessionID))
                        }
                    )
                    .openCodeFocusKey(OpenCodePromptFocusTarget.primaryAction)
                }
            }
        }
        .openCodeFocusGroup(.horizontal)
        .accessibilityElement(children: .contain)
        requestPageDots(position: question.requestPosition, count: question.requestCount)
        promptKeyboardHint
    }

    private var pagesRequests: Bool {
        switch state {
        case let .permission(permission): permission.queueCount > 1
        case let .question(question): question.requestCount > 1
        case .prompt, .childLockout: false
        }
    }

    private var outlineColor: Color? {
        switch state {
        case .prompt, .question, .childLockout:
            nil
        case let .permission(permission):
            permission.errorMessage == nil ? OpenCodeColors.warning : OpenCodeColors.error
        }
    }

    private var contentKind: String {
        switch state {
        case .prompt: "prompt"
        case .permission: "permission"
        case .question: "question"
        case .childLockout: "childLockout"
        }
    }

    private static let shellCornerRadius: CGFloat = 26

    /// How the shell resizes between modes. Chrome that rides on the composer's top
    /// edge (corner pills, the timeline end) moves on the same curve.
    static let morphAnimation = Animation.easeInOut(duration: 0.24)

    /// Send button label. Steer/Queue only describe what Send does while work is running;
    /// an idle session just sends. A shell command always runs now.
    static var sideQuestionPlaceholder: String {
        String(localized: "component.composer.side_question_placeholder", defaultValue: "Side question, not added to chat")
    }

    static var promptPlaceholder: String {
        String(localized: "component.composer.placeholder", defaultValue: "Ask anything...")
    }

    /// The side-question command; typed by the user, so never translated.
    static let sideQuestionCommand = "/btw"

    private static var revertingAccessibilityLabel: String {
        let title = String(localized: "component.composer.reverting_title", defaultValue: "Reverting this message")
        let tag = String(localized: "component.composer.revert_tag", defaultValue: "Revert").uppercased(with: .current)
        return "\(title), \(tag)"
    }

    /// "2 of 3", or "Step 2 of 3" when a request pager sits beside it.
    private static func questionPosition(_ question: OpenCodeSessionComposerState.Question) -> LocalizedStringResource {
        let index = question.questionIndex + 1
        let count = question.questionCount
        if question.requestCount > 1 {
            return LocalizedStringResource("component.composer.question_step", defaultValue: "Step \(index) of \(count)")
        }
        return LocalizedStringResource("component.composer.question_position", defaultValue: "\(index) of \(count)")
    }

    nonisolated static func sendLabel(
        followUpDelivery: FollowUpDelivery?,
        hasActiveWork: Bool,
        shellMode: Bool = false,
        sideQuestionMode: Bool = false
    ) -> String {
        if sideQuestionMode { return String(localized: "component.composer.send_side_question", defaultValue: "Ask side question") }
        if shellMode { return String(localized: "component.composer.run_shell_command", defaultValue: "Run shell command") }
        guard hasActiveWork, let followUpDelivery else { return String(localized: "component.composer.send", defaultValue: "Send") }
        switch followUpDelivery {
        case .steer: return String(localized: "component.composer.send_steer", defaultValue: "Send as Steer")
        case .queue: return String(localized: "component.composer.send_queue", defaultValue: "Send as Queue")
        }
    }

    /// Selection hint under a question; reflects a multiselect's `minItems` / `maxItems`.
    nonisolated static func questionSelectionHint(
        multiple: Bool,
        minSelections: Int? = nil,
        maxSelections: Int? = nil
    ) -> String {
        if !multiple || maxSelections == 1 {
            return String(localized: "component.composer.select_one", defaultValue: "Select one")
        }
        let min = minSelections.flatMap { $0 > 1 ? $0 : nil }
        let hint: LocalizedStringResource = switch (min, maxSelections) {
        case let (min?, max?) where max > min:
            LocalizedStringResource("component.composer.select_range", defaultValue: "Select \(min) to \(max)")
        case let (min?, _?):
            LocalizedStringResource("component.composer.select_exactly", defaultValue: "Select \(min)")
        case let (min?, nil):
            LocalizedStringResource("component.composer.select_at_least", defaultValue: "Select at least \(min)")
        case let (nil, max?):
            LocalizedStringResource("component.composer.select_up_to", defaultValue: "Select up to \(max)")
        case (nil, nil):
            LocalizedStringResource("component.composer.select_all_that_apply", defaultValue: "Select all that apply")
        }
        return String(localized: hint)
    }

    private static func questionSelectionDecorator(selected: Bool) -> StartDecorator {
        .icon(
            selected ? .radioButtonChecked : .radioButtonUnchecked,
            accessibilityLabel: selected ? "common.selected" : "component.composer.not_selected"
        )
    }
}

/// What the request pager steps through; names its Previous and Next buttons.
enum RequestPagerKind {
    case permission
    case question

    var previous: OpenCodeText {
        switch self {
        case .permission: "component.composer.previous_permission"
        case .question: "component.composer.previous_question"
        }
    }

    var next: OpenCodeText {
        switch self {
        case .permission: "component.composer.next_permission"
        case .question: "component.composer.next_question"
        }
    }
}

private struct QuestionCustomTextField: View {
    let text: String
    let enabled: Bool
    @Binding var isFocused: Bool
    let onReturn: () -> Void
    let onEscape: () -> Void
    let onTabOut: (Bool) -> Void
    let onChange: (String) -> Void

    /// Custom answer grows from 3 lines up to 5, then scrolls.
    private static let minLines = 3
    private static let maxLines = 5

    @State private var draft: String = ""

    var body: some View {
        OpenCodeTextInput(
            text: Binding(
                get: { draft },
                set: { newValue in
                    draft = newValue
                    onChange(newValue)
                }
            ),
            hint: "component.composer.custom_answer_hint",
            enabled: enabled,
            isFocused: $isFocused,
            minLines: Self.minLines,
            maxLines: Self.maxLines,
            onHardwareReturn: onReturn,
            onEscape: onEscape,
            onTabOut: onTabOut,
            inputRole: .promptAnswer
        )
        .onAppear { draft = text }
        .onChange(of: text) { _, newValue in
            if draft != newValue {
                draft = newValue
            }
        }
    }
}

#Preview("Permission composer") {
    OpenCodeSessionComposer(
        state: .permission(
            .init(
                requestID: "permission-preview",
                sessionID: "session-preview",
                permission: "bash",
                patterns: ["git status", "git diff --stat"],
                queueCount: 2
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
}

#Preview("Permission composer dark") {
    OpenCodeSessionComposer(
        state: .permission(
            .init(
                requestID: "permission-preview",
                sessionID: "session-preview",
                permission: "external_directory",
                patterns: ["/Users/example/Projects/opencode-web/**"]
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
    .environment(\.colorScheme, .dark)
}

#Preview("Pending revert composer") {
    OpenCodeSessionComposer(
        state: .prompt(
            .init(
                text: "Add a dark mode toggle to Settings",
                showPendingRevert: true,
                selectedModelLabel: "Claude Sonnet 4",
                selectedModeLabel: "Build",
                selectedVariantLabel: "Default",
                showVariant: true,
                sendEnabled: true,
                showStop: false,
                stopEnabled: false
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
}

#Preview("Pending revert composer dark") {
    OpenCodeSessionComposer(
        state: .prompt(
            .init(
                text: "Add a dark mode toggle to Settings",
                showPendingRevert: true,
                selectedModelLabel: "Claude Sonnet 4",
                selectedModeLabel: "Build",
                selectedVariantLabel: "Default",
                showVariant: true,
                sendEnabled: true,
                showStop: false,
                stopEnabled: false
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
    .environment(\.colorScheme, .dark)
}

#Preview("Shell mode composer") {
    OpenCodeSessionComposer(
        state: .prompt(
            .init(
                text: "!git status",
                selectedModelLabel: "Claude Sonnet 4",
                selectedModeLabel: "Build",
                selectedVariantLabel: "Default",
                showVariant: true,
                sendEnabled: true,
                attachEnabled: false,
                showStop: false,
                stopEnabled: false,
                shellMode: true
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
}

#Preview("Shell mode composer dark") {
    OpenCodeSessionComposer(
        state: .prompt(
            .init(
                text: "!git status",
                selectedModelLabel: "Claude Sonnet 4",
                selectedModeLabel: "Build",
                selectedVariantLabel: "Default",
                showVariant: true,
                sendEnabled: true,
                attachEnabled: false,
                showStop: false,
                stopEnabled: false,
                shellMode: true
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
    .environment(\.colorScheme, .dark)
}

#Preview("Mention composer") {
    OpenCodeSessionComposer(
        state: .prompt(
            .init(
                text: "Ask @explore about this",
                mentionRanges: [4 ..< 12],
                selectedModelLabel: "Claude Sonnet 4",
                selectedModeLabel: "Build",
                selectedVariantLabel: "Default",
                showVariant: true,
                sendEnabled: true,
                showStop: false,
                stopEnabled: false
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
}

#Preview("Mention composer dark") {
    OpenCodeSessionComposer(
        state: .prompt(
            .init(
                text: "Ask @explore about this",
                mentionRanges: [4 ..< 12],
                selectedModelLabel: "Claude Sonnet 4",
                selectedModeLabel: "Build",
                selectedVariantLabel: "Default",
                showVariant: true,
                sendEnabled: true,
                showStop: false,
                stopEnabled: false
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
    .environment(\.colorScheme, .dark)
}

#Preview("Question composer") {
    OpenCodeSessionComposer(
        state: .question(
            .init(
                requestID: "question-preview",
                sessionID: "session-preview",
                questionIndex: 0,
                questionCount: 2,
                header: "Approach",
                question: "How should we implement this change?",
                options: [
                    .init(label: "Minimal patch", description: "Touch only the failing path"),
                    .init(label: "Broader refactor", description: "Clean up nearby callers too"),
                ],
                selectedLabels: ["Minimal patch"],
                nextEnabled: true
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
}

#Preview("Question composer dark") {
    OpenCodeSessionComposer(
        state: .question(
            .init(
                requestID: "question-preview",
                sessionID: "session-preview",
                questionIndex: 0,
                questionCount: 2,
                header: "Approach",
                question: "How should we implement this change?",
                options: [
                    .init(label: "Minimal patch", description: "Touch only the failing path"),
                    .init(label: "Broader refactor", description: "Clean up nearby callers too"),
                ],
                selectedLabels: ["Minimal patch"],
                nextEnabled: true
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
    .environment(\.colorScheme, .dark)
}

#Preview("Child lockout composer") {
    OpenCodeSessionComposer(
        state: .childLockout(
            .init(
                selectedModelLabel: "Claude Sonnet 4",
                selectedVariantLabel: "High",
                showVariant: true
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
}

#Preview("Child lockout composer dark") {
    OpenCodeSessionComposer(
        state: .childLockout(
            .init(
                selectedModelLabel: "Claude Sonnet 4",
                selectedVariantLabel: "High",
                showVariant: true
            )
        ),
        onAction: { _ in }
    )
    .openCodeTheme()
    .environment(\.colorScheme, .dark)
}

/// Overlays the outgoing and incoming composer modes on the bottom edge while they crossfade.
/// Unlike a `ZStack`, it places each mode with the proposal it measured it with: re-proposing
/// the measured size makes `QuestionBodyHeightLayout` halve its cap a second time.
private struct ComposerModeStack: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        subviews.reduce(CGSize.zero) { size, subview in
            let fitted = subview.sizeThatFits(proposal)
            return CGSize(width: max(size.width, fitted.width), height: max(size.height, fitted.height))
        }
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for subview in subviews {
            subview.place(at: CGPoint(x: bounds.minX, y: bounds.maxY), anchor: .bottomLeading, proposal: proposal)
        }
    }
}

/// Sizes the question body to its content, up to half the height the composer is offered
/// (the space above the keyboard), so long option lists scroll instead of leaving the screen.
/// No minimum: with little room a short scrolling body beats pushing Submit off screen.
private struct QuestionBodyHeightLayout: Layout {
    private static let maxHeightFraction: CGFloat = 0.5
    private static let fallbackMaxHeight: CGFloat = 360

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let body = subviews.first else { return .zero }
        let width = proposal.width
        let contentHeight = body.sizeThatFits(ProposedViewSize(width: width, height: nil)).height
        let cap: CGFloat
        if let offered = proposal.height, offered.isFinite {
            cap = offered * Self.maxHeightFraction
        } else {
            cap = Self.fallbackMaxHeight
        }
        let height = min(contentHeight, cap)
        let resolvedWidth = width ?? body.sizeThatFits(ProposedViewSize(width: nil, height: height)).width
        return CGSize(width: resolvedWidth, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(
            at: bounds.origin,
            anchor: .topLeading,
            proposal: ProposedViewSize(width: bounds.width, height: bounds.height)
        )
    }
}
