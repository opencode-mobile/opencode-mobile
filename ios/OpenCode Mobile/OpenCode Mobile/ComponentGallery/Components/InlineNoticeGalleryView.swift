import SwiftUI

struct InlineNoticeGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            OpenCodeInlineNotice(message: "Selected model is unavailable.")
            OpenCodeInlineNotice(
                message: "Couldn't prepare diagnostic log for sharing.",
                tone: .error
            )
            OpenCodeInlineNotice(
                message: "Sessions stay in Recent while Running or Ready.",
                variant: .banner
            )
            OpenCodeInlineNotice(
                message: "12 entries dropped under writer pressure.",
                tone: .error,
                variant: .banner
            )
            OpenCodeInlineNotice(
                message: "Refresh failed: offline",
                action: OpenCodeInlineNoticeAction(title: "Retry", action: {})
            )
            OpenCodeInlineNotice(
                message: "Storage error: disk full.",
                tone: .error,
                variant: .banner,
                action: OpenCodeInlineNoticeAction(title: "Retry", action: {})
            )
        }
    }
}

#Preview {
    InlineNoticeGalleryView()
        .padding()
        .openCodeTheme()
}
