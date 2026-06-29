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

    private var textBinding: Binding<String> {
        Binding(
            get: { props.text },
            set: { onTextChange($0) }
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
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
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 26))
        .overlay(
            RoundedRectangle(cornerRadius: 26)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
    }
}
