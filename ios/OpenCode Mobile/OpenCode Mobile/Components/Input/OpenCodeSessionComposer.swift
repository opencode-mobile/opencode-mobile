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
            followUpDelivery: FollowUpDelivery? = nil
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
        }
    }

    struct Permission {
        let requestID: String
        let sessionID: String
        let permission: String
        let patterns: [String]
        let queuePosition: Int
        let queueCount: Int
        let submitting: Bool
        let actionsEnabled: Bool
        let errorMessage: String?

        init(
            requestID: String,
            sessionID: String,
            permission: String,
            patterns: [String],
            queuePosition: Int = 1,
            queueCount: Int = 1,
            submitting: Bool = false,
            actionsEnabled: Bool = true,
            errorMessage: String? = nil
        ) {
            self.requestID = requestID
            self.sessionID = sessionID
            self.permission = permission
            self.patterns = patterns
            self.queuePosition = queuePosition
            self.queueCount = queueCount
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
        let customAllowed: Bool
        let selectedLabels: [String]
        let customText: String
        let customSelected: Bool
        let backEnabled: Bool
        let nextEnabled: Bool
        let submitEnabled: Bool
        let submitting: Bool
        let errorMessage: String?

        init(
            requestID: String,
            sessionID: String,
            questionIndex: Int,
            questionCount: Int,
            header: String,
            question: String,
            options: [QuestionOption],
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
        ) {
            self.requestID = requestID
            self.sessionID = sessionID
            self.questionIndex = questionIndex
            self.questionCount = questionCount
            self.header = header
            self.question = question
            self.options = options
            self.multiple = multiple
            self.customAllowed = customAllowed
            self.selectedLabels = selectedLabels
            self.customText = customText
            self.customSelected = customSelected
            self.backEnabled = backEnabled
            self.nextEnabled = nextEnabled
            self.submitEnabled = submitEnabled
            self.submitting = submitting
            self.errorMessage = errorMessage
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

enum OpenCodeSessionComposerAction {
    case textChanged(text: String, selectionStart: Int, selectionEnd: Int)
    case selectModel
    case selectMode
    case selectVariant
    case attach
    case openCommands
    case removeAttachment(String)
    case cancelRevert
    case send
    case toggleFollowUp
    case stop
    case allowPermissionOnce(requestID: String, sessionID: String)
    case denyPermission(requestID: String, sessionID: String)
    case toggleQuestionOption(requestID: String, sessionID: String, label: String)
    case toggleQuestionCustom(requestID: String, sessionID: String)
    case questionCustomTextChanged(requestID: String, sessionID: String, text: String)
    case questionBack(requestID: String, sessionID: String)
    case questionNext(requestID: String, sessionID: String)
    case submitQuestion(requestID: String, sessionID: String)
    case dismissQuestion(requestID: String, sessionID: String)
    case backToMainSession
}

struct OpenCodeSessionComposer: View {
    let state: OpenCodeSessionComposerState
    let onAction: (OpenCodeSessionComposerAction) -> Void

    var body: some View {
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
        .clipShape(RoundedRectangle(cornerRadius: Self.shellCornerRadius, style: .continuous))
        .openCodeFloatingChrome(cornerRadius: Self.shellCornerRadius)
        .overlay(
            RoundedRectangle(cornerRadius: Self.shellCornerRadius, style: .continuous)
                .stroke(outlineColor, lineWidth: 1)
        )
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
        .animation(.easeInOut(duration: 0.24), value: contentKind)
    }

    @ViewBuilder
    private func promptEntryContent(_ prompt: OpenCodeSessionComposerState.Prompt) -> some View {
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
                mentionRanges: prompt.mentionRanges,
                selectionStart: prompt.selectionStart,
                selectionEnd: prompt.selectionEnd,
                selectionRevision: prompt.selectionRevision,
                placeholder: "Ask anything...",
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
                        variant: .iconCircleText("/", accessibilityLabel: "Open commands"),
                        contrast: .low,
                        action: { onAction(.openCommands) }
                    )
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
                            prompt.followUpDelivery == .steer ? .arrowWarmUp : .arrowUpward,
                            accessibilityLabel: {
                                switch prompt.followUpDelivery {
                                case .steer: "Send as Steer"
                                case .queue: "Send as Queue"
                                case nil: "Send prompt"
                                }
                            }()
                        ),
                        enabled: prompt.sendEnabled,
                        contrast: .low,
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
                .background(
                    OpenCodeColors.textStrong.opacity(0.14),
                    in: Capsule()
                )
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
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
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Permission requested").font(OpenCodeTypography.heading)
                Spacer()
                if permission.queueCount > 1 {
                    Text("\(permission.queuePosition) of \(permission.queueCount)")
                        .font(OpenCodeTypography.caption)
                }
            }
            Text(permission.permission.replacingOccurrences(of: "_", with: " "))
                .font(OpenCodeTypography.bodySecondary)
            ForEach(Array(permission.patterns.enumerated()), id: \.offset) { _, pattern in
                Text(pattern)
                    .font(OpenCodeTypography.code)
                    .textSelection(.enabled)
            }
            if let errorMessage = permission.errorMessage {
                Text(errorMessage)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 4)
        HStack {
            OpenCodeButton(
                variant: .compactText(title: "Deny"),
                enabled: permission.actionsEnabled && !permission.submitting,
                contrast: .low,
                action: {
                    onAction(.denyPermission(requestID: permission.requestID, sessionID: permission.sessionID))
                }
            )
            Spacer(minLength: 0)
            if permission.submitting {
                ProgressView().controlSize(.small)
            }
            OpenCodeButton(
                variant: .compactText(title: "Allow once"),
                enabled: permission.actionsEnabled && !permission.submitting,
                action: {
                    onAction(.allowPermissionOnce(requestID: permission.requestID, sessionID: permission.sessionID))
                }
            )
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func questionRequestContent(_ question: OpenCodeSessionComposerState.Question) -> some View {
        let showBack = question.questionIndex > 0
        let showNext = question.questionIndex < question.questionCount - 1
        let selectionHint = question.multiple ? "Select all that apply" : "Select one"

        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(question.header.isEmpty ? "Question" : question.header)
                    .font(OpenCodeTypography.heading)
                Spacer()
                if question.questionCount > 1 {
                    Text("\(question.questionIndex + 1) of \(question.questionCount)")
                        .font(OpenCodeTypography.caption)
                }
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
    }

    private var outlineColor: Color {
        switch state {
        case .prompt, .question, .childLockout:
            OpenCodeColors.borderWeakerBase
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
            enabled: enabled
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
                patterns: ["/Users/ivan/Projects/opencode-web/**"]
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
