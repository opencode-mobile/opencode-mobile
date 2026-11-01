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
        let attachEnabled: Bool
        let showStop: Bool
        let stopEnabled: Bool
        let followUpDelivery: FollowUpDelivery?
        let showDictationButton: Bool
        /// The draft starts with `!` and Send runs it as a shell command. The `!` is highlighted
        /// and a selected Shell badge replaces the model and agent chips; tapping it sends
        /// `.exitShellMode`.
        let shellMode: Bool
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
            attachEnabled: Bool = true,
            showStop: Bool,
            stopEnabled: Bool,
            followUpDelivery: FollowUpDelivery? = nil,
            showDictationButton: Bool = false,
            shellMode: Bool = false,
            sideQuestionMode: Bool = false
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
            self.attachEnabled = attachEnabled
            self.showStop = showStop
            self.stopEnabled = stopEnabled
            self.followUpDelivery = followUpDelivery
            self.showDictationButton = showDictationButton
            self.shellMode = shellMode
            self.sideQuestionMode = sideQuestionMode
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

/// UIKit text input owned by the composer prompt. Lets keyboard-shortcut focus
/// gating tell the composer apart from every other text field.
protocol OpenCodeComposerTextInput: AnyObject {}

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
    let onAction: (OpenCodeSessionComposerAction) -> Void
    private let status: AnyView?

    init(
        state: OpenCodeSessionComposerState,
        focusRequestToken: Int = 0,
        onInputFocusChange: ((Bool) -> Void)? = nil,
        dictation: OpenCodeSessionComposerDictation? = nil,
        attachmentDragActive: Bool = false,
        onAction: @escaping (OpenCodeSessionComposerAction) -> Void,
        status: AnyView? = nil
    ) {
        self.state = state
        self.focusRequestToken = focusRequestToken
        self.onInputFocusChange = onInputFocusChange
        self.dictation = dictation
        self.attachmentDragActive = attachmentDragActive
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
        .animation(.easeInOut(duration: 0.24), value: contentKind)
        .animation(.easeInOut(duration: 0.24), value: dictation != nil)
        .accessibilityIdentifier(composerAccessibilityIdentifier)
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
                placeholder: prompt.sideQuestionMode ? Self.sideQuestionPlaceholder : "Ask anything...",
                focusRequestToken: focusRequestToken,
                onFocusChange: onInputFocusChange,
                literalInput: prompt.shellMode,
                onDeleteBackwardWhenEmpty: prompt.sideQuestionMode ? { onAction(.exitSideQuestionMode) } : nil,
                onPasteAttachments: prompt.attachEnabled ? { onAction(.pasteAttachments($0)) } : nil,
                acceptsTextDrops: !attachmentDragActive,
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
                        variant: .iconCircle(.add, accessibilityLabel: "Add attachment"),
                        enabled: prompt.attachEnabled,
                        contrast: .low,
                        action: { onAction(.attach) }
                    )

                    OpenCodeButton(
                        variant: .iconCircle(.slash, accessibilityLabel: "Open commands"),
                        contrast: .low,
                        action: { onAction(.openCommands) }
                    )

                    if prompt.showDictationButton {
                        OpenCodeButton(
                            variant: .iconCircle(.mic, accessibilityLabel: "Dictate"),
                            contrast: .low,
                            action: { onAction(.beginDictation) }
                        )
                    }
                }

                Spacer(minLength: 0)

                HStack(spacing: 8) {
                    if prompt.showStop {
                        OpenCodeButton(
                            variant: .iconCircle(.stop, accessibilityLabel: "Abort active work"),
                            enabled: prompt.stopEnabled,
                            contrast: .low,
                            action: { onAction(.stop) }
                        )
                    }

                    OpenCodeButton(
                        variant: .iconCircle(
                            prompt.followUpDelivery == .steer && !prompt.sideQuestionMode ? .arrowWarmUp : .arrowUpward,
                            accessibilityLabel: Self.sendLabel(
                                followUpDelivery: prompt.followUpDelivery,
                                hasActiveWork: prompt.showStop,
                                shellMode: prompt.shellMode,
                                sideQuestionMode: prompt.sideQuestionMode
                            )
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
                Text("Reverting this message")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                Text("REVERT")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Reverting this message, REVERT")
            Button(action: { onAction(.cancelRevert) }) {
                OpenCodeIconImage(icon: .close, size: 20)
                    .foregroundStyle(OpenCodeColors.iconStrongBase)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Cancel revert")
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
                        label: "/btw",
                        selected: true,
                        variant: .glass,
                        action: { onAction(.exitSideQuestionMode) }
                    )
                    .accessibilityLabel("Side question mode")
                    .accessibilityHint("Leave side question mode")
                } else if prompt.shellMode {
                    // Like the TUI, shell mode shows "Shell" in place of the agent and model.
                    OpenCodeChoiceBadge(
                        label: "Shell",
                        selected: true,
                        variant: .glass,
                        action: { onAction(.exitShellMode) }
                    )
                    .accessibilityLabel("Shell mode")
                    .accessibilityHint("Leave shell mode")
                } else {
                    OpenCodeChoiceBadge(
                        label: prompt.selectedModelLabel,
                        selected: false,
                        variant: .glass,
                        action: { onAction(.selectModel) }
                    )
                    OpenCodeChoiceBadge(
                        label: prompt.selectedModeLabel,
                        selected: false,
                        variant: .glass,
                        action: { onAction(.selectMode) }
                    )
                    if prompt.showVariant {
                        OpenCodeChoiceBadge(
                            label: prompt.selectedVariantLabel,
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
        Text("Child sessions cannot be prompted.")
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textBase)
            .padding(.horizontal, 4)
        Button(action: { onAction(.backToMainSession) }) {
            Text("Back to main session")
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
        if lockout.showVariant {
            "Model \(lockout.selectedModelLabel), effort \(lockout.selectedVariantLabel)"
        } else {
            "Model \(lockout.selectedModelLabel)"
        }
    }

    @ViewBuilder
    private func permissionApprovalContent(_ permission: OpenCodeSessionComposerState.Permission) -> some View {
        let actionsEnabled = permission.actionsEnabled && !permission.submitting
        if let attribution = permission.attribution {
            requestAttributionRow(attribution)
        }
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Permission requested").font(OpenCodeTypography.heading)
                Spacer()
                requestPager(
                    position: permission.queuePosition,
                    count: permission.queueCount,
                    enabled: !permission.submitting,
                    noun: "permission request"
                )
                OpenCodeButton(
                    variant: .iconCircle(.keyboardArrowDown, accessibilityLabel: "Hide permission request"),
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
                Text("Always allows: \(permission.alwaysPatterns.joined(separator: ", "))")
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
                variant: .compactText(title: "Deny"),
                enabled: actionsEnabled,
                contrast: .low,
                action: {
                    onAction(.denyPermission(requestID: permission.requestID, sessionID: permission.sessionID))
                }
            )
            Spacer(minLength: 0)
            if permission.submitting {
                ProgressView().controlSize(.small)
            }
            if !permission.alwaysPatterns.isEmpty {
                OpenCodeButton(
                    variant: .compactText(title: "Always"),
                    enabled: actionsEnabled,
                    contrast: .low,
                    action: {
                        onAction(.alwaysAllowPermission(requestID: permission.requestID, sessionID: permission.sessionID))
                    }
                )
            }
            OpenCodeButton(
                variant: .compactText(title: "Allow once"),
                enabled: actionsEnabled,
                action: {
                    onAction(.allowPermissionOnce(requestID: permission.requestID, sessionID: permission.sessionID))
                }
            )
        }
        requestPageDots(position: permission.queuePosition, count: permission.queueCount)
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
                variant: .compactText(title: "Open"),
                contrast: .low,
                action: { onAction(.openRequestSession(sessionID: attribution.sessionID)) }
            )
            .accessibilityLabel("Open subagent session")
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
    private func requestPager(position: Int, count: Int, enabled: Bool, noun: String) -> some View {
        if count > 1 {
            HStack(spacing: 2) {
                OpenCodeButton(
                    variant: .iconCircle(.arrowBack, accessibilityLabel: "Previous \(noun)"),
                    enabled: enabled && position > 1,
                    contrast: .low,
                    action: { onAction(.pageRequest(delta: -1)) }
                )
                Text("\(position) of \(count)")
                    .font(OpenCodeTypography.caption)
                    .monospacedDigit()
                OpenCodeButton(
                    variant: .iconCircle(.arrowForward, accessibilityLabel: "Next \(noun)"),
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
                Text(question.header.isEmpty ? "Question" : question.header)
                    .font(OpenCodeTypography.heading)
                Spacer()
                if question.questionCount > 1 {
                    // With a request pager beside it, name this counter so the two read apart.
                    Text("\(question.requestCount > 1 ? "Step " : "")\(question.questionIndex + 1) of \(question.questionCount)")
                        .font(OpenCodeTypography.caption)
                }
                requestPager(
                    position: question.requestPosition,
                    count: question.requestCount,
                    enabled: !question.submitting,
                    noun: "question"
                )
                OpenCodeButton(
                    variant: .iconCircle(.keyboardArrowDown, accessibilityLabel: "Hide question"),
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
                ForEach(question.options) { option in
                    let optionSelected = question.selectedLabels.contains(option.label)
                    OpenCodeListItem(
                        title: option.label,
                        subtitle: option.description.isEmpty ? nil : option.description,
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
                }

                if question.customAllowed {
                    OpenCodeListItem(
                        title: "Type your own answer",
                        subtitle: question.customSelected || question.customText.isEmpty
                            ? nil
                            : question.customText,
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

                    if question.customSelected {
                        QuestionCustomTextField(
                            text: question.customText,
                            enabled: !question.submitting,
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
                variant: .compactText(title: "Dismiss"),
                enabled: !question.submitting,
                contrast: .low,
                action: {
                    onAction(.dismissQuestion(requestID: question.requestID, sessionID: question.sessionID))
                }
            )
            Spacer(minLength: 0)
            HStack(spacing: 8) {
                if question.submitting {
                    ProgressView().controlSize(.small)
                }
                if showBack {
                    OpenCodeButton(
                        variant: .compactText(title: "Back"),
                        enabled: question.backEnabled && !question.submitting,
                        contrast: .low,
                        action: {
                            onAction(.questionBack(requestID: question.requestID, sessionID: question.sessionID))
                        }
                    )
                }
                if showNext {
                    OpenCodeButton(
                        variant: .compactText(title: "Next"),
                        enabled: question.nextEnabled && !question.submitting,
                        action: {
                            onAction(.questionNext(requestID: question.requestID, sessionID: question.sessionID))
                        }
                    )
                } else {
                    OpenCodeButton(
                        variant: .compactText(title: "Submit"),
                        enabled: question.submitEnabled && !question.submitting,
                        action: {
                            onAction(.submitQuestion(requestID: question.requestID, sessionID: question.sessionID))
                        }
                    )
                }
            }
        }
        .accessibilityElement(children: .contain)
        requestPageDots(position: question.requestPosition, count: question.requestCount)
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

    /// Send button label. Steer/Queue only describe what Send does while work is running;
    /// an idle session just sends. A shell command always runs now.
    static let sideQuestionPlaceholder = "Side question, not added to chat"

    nonisolated static func sendLabel(
        followUpDelivery: FollowUpDelivery?,
        hasActiveWork: Bool,
        shellMode: Bool = false,
        sideQuestionMode: Bool = false
    ) -> String {
        if sideQuestionMode { return "Ask side question" }
        if shellMode { return "Run shell command" }
        guard hasActiveWork, let followUpDelivery else { return "Send" }
        switch followUpDelivery {
        case .steer: return "Send as Steer"
        case .queue: return "Send as Queue"
        }
    }

    /// Selection hint under a question; reflects a multiselect's `minItems` / `maxItems`.
    nonisolated static func questionSelectionHint(
        multiple: Bool,
        minSelections: Int? = nil,
        maxSelections: Int? = nil
    ) -> String {
        if !multiple || maxSelections == 1 { return "Select one" }
        let min = minSelections.flatMap { $0 > 1 ? $0 : nil }
        switch (min, maxSelections) {
        case let (min?, max?) where max > min: return "Select \(min) to \(max)"
        case let (min?, _?): return "Select \(min)"
        case let (min?, nil): return "Select at least \(min)"
        case let (nil, max?): return "Select up to \(max)"
        case (nil, nil): return "Select all that apply"
        }
    }

    private static func questionSelectionDecorator(selected: Bool) -> StartDecorator {
        .icon(
            selected ? .radioButtonChecked : .radioButtonUnchecked,
            accessibilityLabel: selected ? "Selected" : "Not selected"
        )
    }
}

private struct QuestionCustomTextField: View {
    let text: String
    let enabled: Bool
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
            hint: "Enter a custom answer",
            enabled: enabled,
            minLines: Self.minLines,
            maxLines: Self.maxLines
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
