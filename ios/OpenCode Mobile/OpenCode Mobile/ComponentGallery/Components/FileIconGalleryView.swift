import SwiftUI

struct FileIconGalleryView: View {
    @State private var mono = false

    private static let files = [
        "Package.swift", "App.swift", "build.gradle", "Main.kt", "package.json", "index.ts",
        "App.tsx", "foo.test.ts", "README.md", "Dockerfile", ".gitignore", "notes.zzz",
    ]
    private static let folders = ["src", "tests", ".github", "docs", "node_modules", "assets"]
    private let columns = [GridItem(.adaptive(minimum: 96), spacing: 12)]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Picked from the file name or extension, like opencode's FileIcon.")
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Colour", selected: !mono, action: { mono = false })
                OpenCodeChoiceBadge(label: "Mono", selected: mono, action: { mono = true })
            }

            section("Files") {
                ForEach(Self.files, id: \.self) { path in
                    cell(label: path) {
                        OpenCodeFileIcon(path: path, mono: mono, size: 24)
                    }
                }
            }

            section("Folders, collapsed and expanded") {
                ForEach(Self.folders, id: \.self) { path in
                    cell(label: path) {
                        HStack(spacing: 6) {
                            OpenCodeFileIcon(path: path, kind: .directory, mono: mono, size: 24)
                            OpenCodeFileIcon(path: path, kind: .directory, expanded: true, mono: mono, size: 24)
                        }
                    }
                }
            }
        }
    }

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)
            LazyVGrid(columns: columns, spacing: 16, content: content)
        }
    }

    private func cell(label: String, @ViewBuilder icon: () -> some View) -> some View {
        VStack(spacing: 6) {
            icon()
                .foregroundStyle(OpenCodeColors.iconStrongBase)
                .frame(height: 32)
            Text(label)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
    }
}

#Preview {
    ScrollView {
        FileIconGalleryView()
            .padding(16)
    }
    .openCodeTheme()
}
