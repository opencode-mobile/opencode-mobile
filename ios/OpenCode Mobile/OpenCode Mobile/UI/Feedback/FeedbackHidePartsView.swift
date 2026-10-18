import SwiftUI

/// Hide parts (#721): tap the screenshot to cover an area with a black box.
struct FeedbackHidePartsView: View {
    let model: FeedbackViewModel

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Hide parts",
                leading: .none,
                actions: [.label(title: "Done", action: { model.closeHideParts() })],
                showDivider: true
            )
            VStack(spacing: 16) {
                Text("Tap an area to cover it.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .frame(maxWidth: .infinity)
                if let preview = model.uiState.screenshotPreview {
                    screenshot(preview)
                }
                OpenCodeButton(
                    variant: .compactText(title: "Undo"),
                    enabled: model.uiState.hiddenAreaCount > 0,
                    contrast: .low,
                    action: { model.undoHiddenPart() }
                )
            }
            .padding(16)
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .background(OpenCodeColors.backgroundBase)
    }

    private func screenshot(_ image: UIImage) -> some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(OpenCodeColors.borderWeakBase, lineWidth: 1)
            )
            .overlay {
                GeometryReader { geometry in
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture { location in
                            guard geometry.size.width > 0, geometry.size.height > 0 else { return }
                            model.hidePart(
                                at: CGPoint(
                                    x: location.x / geometry.size.width,
                                    y: location.y / geometry.size.height
                                )
                            )
                        }
                }
            }
            .frame(maxHeight: .infinity)
            .accessibilityLabel("Screenshot, \(model.uiState.hiddenAreaCount) areas hidden")
            .accessibilityAddTraits(.allowsDirectInteraction)
            .accessibilityIdentifier("feedbackHidePartsImage")
    }
}
