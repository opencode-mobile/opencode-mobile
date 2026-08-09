import SwiftUI

struct SessionChatToolDetailTarget: Equatable {
    let title: String
    let body: String
}

struct SessionChatToolDetailSheetModifier: ViewModifier {
    @Binding var target: SessionChatToolDetailTarget?

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: target?.title ?? "Tool",
                content: {
                    Text(target?.body ?? "")
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textStrong)
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

extension View {
    func sessionChatToolDetailSheet(
        target: Binding<SessionChatToolDetailTarget?>
    ) -> some View {
        modifier(SessionChatToolDetailSheetModifier(target: target))
    }
}
