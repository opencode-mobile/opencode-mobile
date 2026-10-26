import SwiftUI
import UIKit

/// A `/btw` answer: the question, the rendered markdown answer, and Copy / Done. Closing the sheet
/// forgets the answer.
private struct SessionChatSideQuestionSheetModifier: ViewModifier {
    let answer: SessionChatSideQuestionAnswer?
    let onCopy: () -> Void
    let onClose: () -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: presentedBinding,
                title: "/btw",
                content: {
                    SessionChatSideQuestionSheetBody(answer: answer)
                },
                pinnedBottom: {
                    HStack(spacing: 8) {
                        OpenCodeButton(variant: .fullWidthText(title: "Copy"), contrast: .low) {
                            guard let answer else { return }
                            UIPasteboard.general.string = answer.answer
                            onCopy()
                        }
                        OpenCodeButton(variant: .fullWidthText(title: "Done")) {
                            onClose()
                        }
                    }
                }
            )
    }

    private var presentedBinding: Binding<Bool> {
        Binding(
            get: { answer != nil },
            set: { presented in
                if !presented {
                    onClose()
                }
            }
        )
    }
}

private struct SessionChatSideQuestionSheetBody: View {
    let answer: SessionChatSideQuestionAnswer?

    @State private var document: NativeMarkdownDocument?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let answer {
                Text(answer.question)
                    .font(OpenCodeTypography.heading)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .accessibilityAddTraits(.isHeader)
                if let document {
                    OpenCodeRenderedMarkdown(document: document, variant: .standalone)
                } else {
                    Text(answer.answer)
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textStrong)
                }
                Text("Not added to the conversation")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
            }
        }
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .openCodeBottomSheetContentInset()
        .task(id: answer?.id) {
            document = nil
            guard let source = answer?.answer, !source.isEmpty else { return }
            document = await Task.detached(priority: .userInitiated) {
                OpenCodeRenderedMarkdownParser.parse(source, variant: .standalone)
            }.value
        }
    }
}

extension View {
    func sessionChatSideQuestionAnswerSheet(
        answer: SessionChatSideQuestionAnswer?,
        onCopy: @escaping () -> Void,
        onClose: @escaping () -> Void
    ) -> some View {
        modifier(SessionChatSideQuestionSheetModifier(answer: answer, onCopy: onCopy, onClose: onClose))
    }
}
