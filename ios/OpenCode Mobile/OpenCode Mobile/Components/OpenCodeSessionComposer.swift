import SwiftUI

enum OpenCodeSessionComposerState {
    struct Prompt {
        let text: String
        let attachments: [OpenCodeAttachmentPresentation]
        let selectedModelLabel: String
        let selectedModeLabel: String
        let selectedVariantLabel: String
        let showVariant: Bool
        let sendEnabled: Bool
        let attachEnabled: Bool
        let showStop: Bool
        let stopEnabled: Bool

        init(
            text: String,
            attachments: [OpenCodeAttachmentPresentation] = [],
            selectedModelLabel: String,
            selectedModeLabel: String,
            selectedVariantLabel: String,
            showVariant: Bool,
            sendEnabled: Bool,
            attachEnabled: Bool = true,
            showStop: Bool,
            stopEnabled: Bool
        ) {
            self.text = text
            self.attachments = attachments
            self.selectedModelLabel = selectedModelLabel
            self.selectedModeLabel = selectedModeLabel
            self.selectedVariantLabel = selectedVariantLabel
            self.showVariant = showVariant
            self.sendEnabled = sendEnabled
            self.attachEnabled = attachEnabled
            self.showStop = showStop
            self.stopEnabled = stopEnabled
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
        let errorMessage: String?

        init(
            requestID: String,
            sessionID: String,
            permission: String,
            patterns: [String],
            queuePosition: Int = 1,
            queueCount: Int = 1,
            submitting: Bool = false,
            errorMessage: String? = nil
        ) {
            self.requestID = requestID
            self.sessionID = sessionID
            self.permission = permission
            self.patterns = patterns
            self.queuePosition = queuePosition
            self.queueCount = queueCount
            self.submitting = submitting
            self.errorMessage = errorMessage
        }
    }

    case prompt(Prompt)
    case permission(Permission)
}

enum OpenCodeSessionComposerAction {
    case textChanged(String)
    case selectModel
    case selectMode
    case selectVariant
    case attach
    case removeAttachment(String)
    case send
    case stop
    case allowPermissionOnce(requestID: String, sessionID: String)
    case denyPermission(requestID: String, sessionID: String)
}

struct OpenCodeSessionComposer: View {
    let state: OpenCodeSessionComposerState
    var isFocused: Binding<Bool>?
    let onAction: (OpenCodeSessionComposerAction) -> Void

    @FocusState private var promptFocused: Bool

    init(
        state: OpenCodeSessionComposerState,
        isFocused: Binding<Bool>? = nil,
        onAction: @escaping (OpenCodeSessionComposerAction) -> Void
    ) {
        self.state = state
        self.isFocused = isFocused
        self.onAction = onAction
    }

    private var textBinding: Binding<String> {
        Binding(
            get: {
                guard case let .prompt(prompt) = state else { return "" }
                return prompt.text
            },
            set: { onAction(.textChanged($0)) }
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch state {
            case let .permission(permission):
                permissionApprovalContent(permission)
            case let .prompt(prompt):
                promptEntryContent(prompt)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 26))
        .overlay(
            RoundedRectangle(cornerRadius: 26)
                .stroke(outlineColor, lineWidth: 1)
        )
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
        .animation(.easeInOut(duration: 0.24), value: isPermission)
        .onChange(of: promptFocused) { _, value in
            if isFocused?.wrappedValue != value {
                isFocused?.wrappedValue = value
            }
        }
        .onChange(of: isFocused?.wrappedValue, initial: true) { _, value in
            if let value, promptFocused != value {
                promptFocused = value
            }
        }
        .onChange(of: isPermission) { _, permissionActive in
            if permissionActive {
                clearPromptFocus()
            }
        }
    }

    @ViewBuilder
    private func promptEntryContent(_ prompt: OpenCodeSessionComposerState.Prompt) -> some View {
            if !prompt.attachments.isEmpty {
                VStack(spacing: 8) {
                    ForEach(prompt.attachments) { attachment in
                        OpenCodeAttachmentChip(attachment: attachment) { id in
                            onAction(.removeAttachment(id))
                        }
                    }
                }
            }

            TextField("Ask anything...", text: textBinding, axis: .vertical)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .focused($promptFocused)
                .padding(.horizontal, 4)
                .padding(.vertical, 4)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: prompt.selectedModelLabel, selected: false, action: {
                        endEditingThen(.selectModel)
                    })
                    OpenCodeChoiceBadge(label: prompt.selectedModeLabel, selected: false, action: {
                        endEditingThen(.selectMode)
                    })
                    if prompt.showVariant {
                        OpenCodeChoiceBadge(label: prompt.selectedVariantLabel, selected: false, action: {
                            endEditingThen(.selectVariant)
                        })
                    }
                }
            }
            .scrollDismissesKeyboard(.never)

            HStack {
                OpenCodeButton(
                    variant: .iconCircle(.add, accessibilityLabel: "Add attachment"),
                    enabled: prompt.attachEnabled,
                    contrast: .low,
                    action: { endEditingThen(.attach) }
                )

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
                        variant: .iconCircle(.arrowUpward, accessibilityLabel: "Send prompt"),
                        enabled: prompt.sendEnabled,
                        contrast: .low,
                        action: { endEditingThen(.send) }
                    )
                }
            }
    }

    private func endEditingThen(_ action: OpenCodeSessionComposerAction) {
        clearPromptFocus()
        onAction(action)
    }

    private func clearPromptFocus() {
        promptFocused = false
        if isFocused?.wrappedValue == true {
            isFocused?.wrappedValue = false
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
                enabled: !permission.submitting,
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
                enabled: !permission.submitting,
                action: {
                    onAction(.allowPermissionOnce(requestID: permission.requestID, sessionID: permission.sessionID))
                }
            )
        }
        .accessibilityElement(children: .contain)
    }

    private var outlineColor: Color {
        switch state {
        case .prompt:
            OpenCodeColors.borderWeakerBase
        case let .permission(permission):
            permission.errorMessage == nil ? OpenCodeColors.warning : OpenCodeColors.error
        }
    }

    private var isPermission: Bool {
        if case .permission = state { return true }
        return false
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
