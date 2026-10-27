import SwiftUI

struct CodeFileGalleryView: View {
    @State private var wraps = false
    @State private var highlighted = true
    @State private var marked = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive").font(OpenCodeTypography.heading)
            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Wrap lines", selected: wraps) { wraps.toggle() }
                OpenCodeChoiceBadge(label: "Highlighted", selected: highlighted) { highlighted.toggle() }
                OpenCodeChoiceBadge(label: "Marked ranges", selected: marked) { marked.toggle() }
            }
            OpenCodeCodeFile(
                lines: Self.lines,
                lineSpans: highlighted ? Self.spans : [:],
                lineMarks: marked ? Self.marks : [:],
                wrapsLines: wraps
            )
            .frame(height: 260)
            .background(OpenCodeColors.surfaceBase)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(OpenCodeColors.borderWeakerBase))

            Text("Plain text, many lines").font(OpenCodeTypography.heading)
            OpenCodeCodeFile(lines: (1...400).map { "Line \($0) of a long plain text file" })
                .frame(height: 160)
                .background(OpenCodeColors.surfaceBase)
                .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    private static let lines = [
        "import SwiftUI",
        "",
        "// Lists one project folder.",
        "struct FilesView: View {",
        "    let worktree: String",
        "    var body: some View {",
        "        Text(\"A long line that keeps going so horizontal scrolling and wrapping are easy to compare\")",
        "    }",
        "}",
    ]

    /// Every other line's first word, the way the Highlighting Lab marks engine disagreements.
    private static let marks: [Int: [Range<Int>]] = Dictionary(
        uniqueKeysWithValues: lines.indices.compactMap { index -> (Int, [Range<Int>])? in
            let line = lines[index]
            guard index % 2 == 1,
                  let first = line.firstIndex(where: { $0 != " " }),
                  let space = line[first...].firstIndex(of: " ")
            else { return nil }
            return (index, [0..<line.utf16.distance(from: line.startIndex, to: space)])
        }
    )

    private static let spans: [Int: [NativeCodeSpan]] = [
        0: [NativeCodeSpan(start: 0, end: 6, role: .keyword), NativeCodeSpan(start: 7, end: 14, role: .type)],
        2: [NativeCodeSpan(start: 0, end: 29, role: .comment)],
        3: [NativeCodeSpan(start: 0, end: 6, role: .keyword), NativeCodeSpan(start: 7, end: 16, role: .type)],
        4: [NativeCodeSpan(start: 4, end: 7, role: .keyword), NativeCodeSpan(start: 18, end: 24, role: .type)],
        5: [NativeCodeSpan(start: 4, end: 7, role: .keyword), NativeCodeSpan(start: 19, end: 23, role: .keyword)],
        6: [NativeCodeSpan(start: 8, end: 12, role: .function), NativeCodeSpan(start: 13, end: 100, role: .string)],
    ]
}

#Preview {
    ScrollView {
        CodeFileGalleryView().padding()
    }
    .openCodeTheme()
}
