import SwiftUI

/// Hide parts page of the report sheet (#721): tap the screenshot to cover an
/// area with a black box.
struct FeedbackHidePartsContent: View {
    let model: FeedbackViewModel

    var body: some View {
        VStack(spacing: 12) {
            Text("feedback.hide_parts.instructions")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let preview = model.uiState.screenshotPreview {
                screenshot(preview)
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 8)
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
            .frame(maxHeight: 420)
            .frame(maxWidth: .infinity)
            .accessibilityLabel(Text(Self.imageLabel(hiddenAreaCount: model.uiState.hiddenAreaCount)))
            .accessibilityAddTraits(.allowsDirectInteraction)
            .accessibilityIdentifier("feedbackHidePartsImage")
    }

    private static func imageLabel(hiddenAreaCount: Int) -> LocalizedStringResource {
        LocalizedStringResource(
            "feedback.hide_parts.image_hidden",
            defaultValue: "Screenshot, \(hiddenAreaCount) areas hidden"
        )
    }
}

/// Undo and Done under the Hide parts page.
struct FeedbackHidePartsActions: View {
    let model: FeedbackViewModel

    var body: some View {
        HStack(spacing: 8) {
            OpenCodeButton(
                variant: .compactText(title: "feedback.undo"),
                enabled: model.uiState.hiddenAreaCount > 0,
                contrast: .low,
                action: { model.undoHiddenPart() }
            )
            Spacer(minLength: 0)
            OpenCodeButton(variant: .compactText(title: "feedback.done"), action: { model.closeHideParts() })
        }
    }
}
