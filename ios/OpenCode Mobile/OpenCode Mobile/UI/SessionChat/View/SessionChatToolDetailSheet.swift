import SwiftUI

struct SessionChatToolDetailTarget: Equatable {
    let title: String
    let body: String
    /// Render `body` as markdown (a compaction summary) instead of plain tool output.
    var bodyIsMarkdown: Bool = false
}

struct SessionChatToolDetailSheetModifier: ViewModifier {
    @Binding var target: SessionChatToolDetailTarget?

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: target?.title ?? "Tool",
                content: {
                    SessionChatToolDetailBody(
                        text: target?.body ?? "",
                        isMarkdown: target?.bodyIsMarkdown ?? false
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                }
            )
    }

    private var sheetPresentedBinding: Binding<Bool> {
        Binding(
            get: { target != nil },
            set: { presented in
                if !presented {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionToolDetail,
                        actionId: ProductAnalytics.Action.toolDetailDismiss
                    )
                    target = nil
                }
            }
        )
    }
}

/// Plain tool output, or rendered markdown once a markdown body is parsed
/// off the main actor.
private struct SessionChatToolDetailBody: View {
    let text: String
    let isMarkdown: Bool

    @State private var document: NativeMarkdownDocument?

    var body: some View {
        Group {
            if isMarkdown, let document {
                OpenCodeRenderedMarkdown(document: document, variant: .standalone)
            } else {
                Text(text)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
            }
        }
        .task(id: isMarkdown ? text : nil) {
            guard isMarkdown, !text.isEmpty else {
                document = nil
                return
            }
            let source = text
            document = await Task.detached(priority: .userInitiated) {
                OpenCodeRenderedMarkdownParser.parse(source, variant: .standalone)
            }.value
        }
    }
}

extension View {
    func sessionChatToolDetailSheet(
        target: Binding<SessionChatToolDetailTarget?>
    ) -> some View {
        modifier(SessionChatToolDetailSheetModifier(target: target))
    }
}
