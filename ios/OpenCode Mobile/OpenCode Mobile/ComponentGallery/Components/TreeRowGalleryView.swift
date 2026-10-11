import SwiftUI

struct TreeRowGalleryView: View {
    @State private var expanded: Set<String> = ["Sources"]
    @State private var selected = "Sources/App.swift"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive").font(OpenCodeTypography.heading)
            VStack(spacing: 0) {
                folder("Sources", depth: 0)
                if expanded.contains("Sources") {
                    file("Sources/App.swift", depth: 1)
                    file("Sources/FilesView.swift", depth: 1)
                }
                folder("build", depth: 0, ignored: true)
                if expanded.contains("build") {
                    Text("Empty folder")
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .padding(.leading, OpenCodeTreeRow.contentInset(depth: 1))
                        .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
                }
                file("README.md", depth: 0)
            }

            Text("States").font(OpenCodeTypography.heading)
            VStack(spacing: 0) {
                OpenCodeTreeRow(title: "Normal", kind: .file) {}
                OpenCodeTreeRow(title: "Selected", kind: .file, state: .selected) {}
                OpenCodeTreeRow(title: "Loading", kind: .folder(isExpanded: true), state: .loading) {}
                OpenCodeTreeRow(title: "node_modules", kind: .folder(isExpanded: false), isDimmed: true, tag: "ignored") {}
                OpenCodeTreeRow(title: "FilesView.swift", subtitle: "ios/OpenCode Mobile/UI/Files", kind: .file) {}
            }
        }
    }

    private func folder(_ path: String, depth: Int, ignored: Bool = false) -> some View {
        OpenCodeTreeRow(
            title: path,
            kind: .folder(isExpanded: expanded.contains(path)),
            depth: depth,
            isDimmed: ignored,
            tag: ignored ? "ignored" : nil
        ) {
            if expanded.contains(path) { expanded.remove(path) } else { expanded.insert(path) }
        }
    }

    private func file(_ path: String, depth: Int) -> some View {
        OpenCodeTreeRow(
            title: String(path.split(separator: "/").last ?? ""),
            kind: .file,
            depth: depth,
            state: selected == path ? .selected : .normal
        ) {
            selected = path
        }
    }
}

#Preview {
    ScrollView {
        TreeRowGalleryView().padding()
    }
    .openCodeTheme()
}
