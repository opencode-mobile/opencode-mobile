import SwiftUI
import UIKit

struct SessionChatToolDetailTarget: Equatable {
    let title: String
    let body: String
    /// Render `body` as markdown (a compaction summary) instead of plain tool output.
    var bodyIsMarkdown: Bool = false
    /// Provider response: monospace, with a copy button.
    var monospace: Bool = false
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
                        isMarkdown: target?.bodyIsMarkdown ?? false,
                        monospace: target?.monospace ?? false
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
    var monospace: Bool = false

    @State private var document: NativeMarkdownDocument?

    var body: some View {
        Group {
            if isMarkdown, let document {
                OpenCodeRenderedMarkdown(document: document, variant: .standalone)
            } else if monospace {
                VStack(alignment: .leading, spacing: 8) {
                    Button("Copy") {
                        UIPasteboard.general.string = text
                    }
                    .buttonStyle(.plain)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    Text(text)
                        .font(.system(.body, design: .monospaced))
                        .foregroundStyle(OpenCodeColors.textStrong)
                }
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
