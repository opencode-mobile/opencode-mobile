import SwiftUI

struct CodeSnippetGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            OpenCodeCodeSnippet("opencode serve")
            OpenCodeCodeSnippet("opencode2 pair", surface: .base)
            OpenCodeCodeSnippet("OPENCODE_SERVER_PASSWORD=secret opencode serve")
        }
    }
}

#Preview {
    CodeSnippetGalleryView()
        .padding()
        .background(OpenCodeColors.backgroundBase)
        .openCodeTheme()
}
