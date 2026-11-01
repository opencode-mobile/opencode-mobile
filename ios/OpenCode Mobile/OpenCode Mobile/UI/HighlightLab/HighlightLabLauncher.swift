#if HIGHLIGHT_LAB
import Observation
import SwiftUI

/// Opens a File Viewer file in the Highlighting Lab as a custom data set (Lab builds only).
@MainActor
@Observable
final class HighlightLabLauncher {
    static let shared = HighlightLabLauncher()

    var pending: HighlightLabCustomText?

    func open(relativePath: String, document: FileTextDocument) {
        pending = HighlightLabCustomText(
            id: "file-\(UUID().uuidString.prefix(8))",
            displayName: ProjectPaths.name(of: relativePath),
            language: document.language?.id ?? "plaintext",
            source: document.text
        )
    }
}

private struct HighlightLabLauncherSheet: ViewModifier {
    @State private var launcher = HighlightLabLauncher.shared

    func body(content: Content) -> some View {
        content.sheet(item: $launcher.pending) { text in
            NavigationStack {
                HighlightLabViewerView(input: .custom(text), service: KMPHighlightLabService.shared, presentedModally: true)
            }
            .openCodeTheme()
        }
    }
}

extension View {
    /// Presents the Lab viewer when a File Viewer menu asks for it.
    func highlightLabLauncherSheet() -> some View {
        modifier(HighlightLabLauncherSheet())
    }
}
#endif
