import SwiftUI

struct SessionChatComposerViewProps {
    let text: String
    let selectedModelLabel: String
    let selectedMode: String
    let selectedVariantLabel: String
    let showVariant: Bool
    let sendEnabled: Bool
    let showStop: Bool
    let stopEnabled: Bool
    let permission: SessionChatComposerPermissionProps?
}

struct SessionChatComposerPermissionProps {
    let permission: String
    let patterns: [String]
    let queuePosition: Int
    let queueCount: Int
    let submitting: Bool
    let errorMessage: String?

    init(
        permission: String,
        patterns: [String],
        queuePosition: Int = 1,
        queueCount: Int = 1,
        submitting: Bool = false,
        errorMessage: String? = nil
    ) {
        self.permission = permission
        self.patterns = patterns
        self.queuePosition = queuePosition
        self.queueCount = queueCount
        self.submitting = submitting
        self.errorMessage = errorMessage
    }
}

struct SessionChatComposerView: View {
    let props: SessionChatComposerViewProps
    let onTextChange: (String) -> Void
    let onModelClick: () -> Void
    let onModeClick: () -> Void
    let onVariantClick: () -> Void
    let onAttachClick: () -> Void
    let onSendClick: () -> Void
    let onStopClick: () -> Void
    let onAllowPermissionOnce: () -> Void
    let onDenyPermission: () -> Void

    private var textBinding: Binding<String> {
        Binding(
            get: { props.text },
            set: { onTextChange($0) }
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let permission = props.permission {
                permissionApprovalContent(permission)
            } else {
                promptEntryContent
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
        .animation(.easeInOut(duration: 0.24), value: props.permission != nil)
    }

    @ViewBuilder
    private var promptEntryContent: some View {
            TextField("Ask anything...", text: textBinding, axis: .vertical)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .padding(.horizontal, 4)
                .padding(.vertical, 4)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: props.selectedModelLabel, selected: false, action: onModelClick)
                    OpenCodeChoiceBadge(label: props.selectedMode, selected: false, action: onModeClick)
                    if props.showVariant {
                        OpenCodeChoiceBadge(label: props.selectedVariantLabel, selected: false, action: onVariantClick)
                    }
                }
            }

            HStack {
                OpenCodeButton(
                    variant: .iconCircle(.add, accessibilityLabel: "Attach context"),
                    contrast: .low,
                    action: onAttachClick
                )

                Spacer(minLength: 0)

                HStack(spacing: 8) {
                    if props.showStop {
                        OpenCodeButton(
                            variant: .iconCircle(.stop, accessibilityLabel: "Abort active work"),
                            enabled: props.stopEnabled,
                            contrast: .low,
                            action: onStopClick
                        )
                    }

                    OpenCodeButton(
                        variant: .iconCircle(.arrowUpward, accessibilityLabel: "Send prompt"),
                        enabled: props.sendEnabled,
                        contrast: .low,
                        action: onSendClick
                    )
                }
            }
    }

    @ViewBuilder
    private func permissionApprovalContent(_ permission: SessionChatComposerPermissionProps) -> some View {
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
                action: onDenyPermission
            )
            Spacer(minLength: 0)
            if permission.submitting {
                ProgressView().controlSize(.small)
            }
            OpenCodeButton(
                variant: .compactText(title: "Allow once"),
                enabled: !permission.submitting,
                action: onAllowPermissionOnce
            )
        }
        .accessibilityElement(children: .contain)
    }

    private var outlineColor: Color {
        guard let permission = props.permission else {
            return OpenCodeColors.borderWeakerBase
        }
        return permission.errorMessage == nil ? OpenCodeColors.warning : OpenCodeColors.error
    }
}

#Preview("Permission composer") {
    SessionChatComposerView(
        props: SessionChatComposerViewProps(
            text: "",
            selectedModelLabel: "Claude Sonnet 4",
            selectedMode: "Build",
            selectedVariantLabel: "Default",
            showVariant: false,
            sendEnabled: false,
            showStop: false,
            stopEnabled: false,
            permission: SessionChatComposerPermissionProps(
                permission: "bash",
                patterns: ["git status", "git diff --stat"],
                queueCount: 2
            )
        ),
        onTextChange: { _ in },
        onModelClick: {},
        onModeClick: {},
        onVariantClick: {},
        onAttachClick: {},
        onSendClick: {},
        onStopClick: {},
        onAllowPermissionOnce: {},
        onDenyPermission: {}
    )
    .openCodeTheme()
}

#Preview("Permission composer dark") {
    SessionChatComposerView(
        props: SessionChatComposerViewProps(
            text: "",
            selectedModelLabel: "Claude Sonnet 4",
            selectedMode: "Build",
            selectedVariantLabel: "Default",
            showVariant: false,
            sendEnabled: false,
            showStop: false,
            stopEnabled: false,
            permission: SessionChatComposerPermissionProps(
                permission: "external_directory",
                patterns: ["/Users/ivan/Projects/opencode-web/**"]
            )
        ),
        onTextChange: { _ in },
        onModelClick: {},
        onModeClick: {},
        onVariantClick: {},
        onAttachClick: {},
        onSendClick: {},
        onStopClick: {},
        onAllowPermissionOnce: {},
        onDenyPermission: {}
    )
    .openCodeTheme()
    .environment(\.colorScheme, .dark)
}
