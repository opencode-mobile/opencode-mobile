import SwiftUI

struct KeyboardHintGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            OpenCodeKeyboardHint(OpenCodePromptKeyboardHint.text)
            OpenCodeKeyboardHint(OpenCodeInterruptKeyboardHint.text, tone: .warning)
        }
    }
}

#Preview {
    KeyboardHintGalleryView()
        .padding()
        .openCodeTheme()
}
