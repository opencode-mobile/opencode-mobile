import SwiftUI

struct OpenCodeAssistantMessage: View {
    let text: String
    var renderedMarkdown: NativeMarkdownDocument? = nil
    var isStreaming: Bool = false

    var body: some View {
        Group {
            if let renderedMarkdown {
                OpenCodeRenderedMarkdown(
                    document: renderedMarkdown,
                    variant: .assistantMessage,
                    isStreaming: isStreaming
                )
            } else {
                Text(Self.displayText(text))
                    .font(OpenCodeTypography.body)
                    .lineSpacing(4)
                    .foregroundStyle(OpenCodeColors.textStrong)
            }
        }
        .padding(.horizontal, Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: Self.cornerRadius))
        .frame(maxWidth: OpenCodeLayout.assistantMessageMaxWidth, alignment: .leading)
    }

    static let emptyPlaceholder = "…"

    static func displayText(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? emptyPlaceholder : text
    }

    private static let cornerRadius: CGFloat = 8
    private static let paddingHorizontal: CGFloat = 12
    private static let paddingVertical: CGFloat = 8
}

#if DEBUG
private enum AssistantMessagePreviewCase: String, CaseIterable, Identifiable {
    case short
    case long
    case empty

    var id: String { rawValue }

    var text: String {
        switch self {
        case .short:
            "The Alleskoenner is the right class for that parcel."
        case .long:
            "Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options and keep the recommendation practical."
        case .empty:
            ""
        }
    }
}

#Preview("Assistant Message") {
    ScrollView {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(AssistantMessagePreviewCase.allCases) { preview in
                OpenCodeAssistantMessage(text: preview.text)
            }
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
