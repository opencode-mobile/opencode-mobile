import SwiftUI
import UIKit

private enum OpenCodeCodeFileMetrics {
    static let horizontalPadding: CGFloat = 12
    static let verticalPadding: CGFloat = 8
    static let gutterSpacing: CGFloat = 12
    static let lineMinHeight: CGFloat = 20
    static let markOpacity: Double = 0.28

    /// Advance of one glyph in `OpenCodeTypography.code` (14 pt monospaced).
    static let characterWidth: CGFloat = {
        let font = UIFont.monospacedSystemFont(ofSize: 14, weight: .regular)
        return ("0" as NSString).size(withAttributes: [.font: font]).width
    }()
}

/// Read-only source file: line-number gutter, optionally highlighted lines, and wrap on/off.
/// Rows are lazy, so files with tens of thousands of lines stay responsive; `onLineAppear`
/// lets the owner highlight what is on screen first. `lineMarks` draws ranges on the marked
/// background (such as where two highlighters disagree); `topLine`, when given, sets the line
/// at the top and, on iOS 18 and later, follows scrolling, so two files can scroll together
/// (unwrapped lines only, which share one row height).
struct OpenCodeCodeFile: View {
    let lines: [String]
    let lineSpans: [Int: [NativeCodeSpan]]
    /// UTF-16 ranges within each line, by 0-based line.
    let lineMarks: [Int: [Range<Int>]]
    let wrapsLines: Bool
    let maxLineLength: Int
    let topLine: Binding<Int?>?
    let onLineAppear: ((Int) -> Void)?

    /// The top line this file last scrolled to, so a `topLine` it reported is not scrolled back.
    @State private var shownTopLine: Int?

    init(
        lines: [String],
        lineSpans: [Int: [NativeCodeSpan]] = [:],
        lineMarks: [Int: [Range<Int>]] = [:],
        wrapsLines: Bool = false,
        maxLineLength: Int? = nil,
        topLine: Binding<Int?>? = nil,
        onLineAppear: ((Int) -> Void)? = nil
    ) {
        self.lines = lines
        self.lineSpans = lineSpans
        self.lineMarks = lineMarks
        self.wrapsLines = wrapsLines
        self.maxLineLength = maxLineLength ?? lines.reduce(0) { max($0, $1.count) }
        self.topLine = topLine
        self.onLineAppear = onLineAppear
    }

    private var gutterWidth: CGFloat {
        CGFloat(max(2, String(lines.count).count)) * OpenCodeCodeFileMetrics.characterWidth
    }

    /// Unwrapped content width, so the horizontal extent does not change as lazy rows load.
    private var unwrappedWidth: CGFloat {
        OpenCodeCodeFileMetrics.horizontalPadding * 2
            + gutterWidth
            + OpenCodeCodeFileMetrics.gutterSpacing
            + CGFloat(maxLineLength + 1) * OpenCodeCodeFileMetrics.characterWidth
    }

    var body: some View {
        if let topLine, !wrapsLines {
            // `scrollPosition(id:)` does not report user scrolling in a two-axis scroll view, so
            // the top line comes from the content offset and moves through `scrollTo`.
            ScrollViewReader { proxy in
                scrollView
                    .openCodeOnVerticalOffsetChange { offset in
                        let line = Self.topLine(offset: offset, lineCount: lines.count)
                        guard line != shownTopLine else { return }
                        shownTopLine = line
                        if topLine.wrappedValue != line { topLine.wrappedValue = line }
                    }
                    .onChange(of: topLine.wrappedValue) { _, line in
                        guard let line, line != shownTopLine, lines.indices.contains(line) else { return }
                        shownTopLine = line
                        proxy.scrollTo(line, anchor: .topLeading)
                    }
            }
        } else {
            scrollView
        }
    }

    /// A syntax preset's editor background fills the file; otherwise the owner's background shows.
    private var scrollView: some View {
        scrollContent
            .background(OpenCodeColors.codeBackground(or: .clear))
    }

    @ViewBuilder
    private var scrollContent: some View {
        if wrapsLines {
            ScrollView(.vertical) {
                rows
            }
        } else {
            ScrollView([.vertical, .horizontal]) {
                rows
                    .frame(minWidth: unwrappedWidth, alignment: .leading)
            }
            // A two-axis scroll view centers content shorter than itself; keep short files at the top.
            .defaultScrollAnchor(.topLeading)
        }
    }

    private var rows: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(lines.indices, id: \.self) { index in
                row(index)
            }
        }
        .padding(.vertical, OpenCodeCodeFileMetrics.verticalPadding)
        .accessibilityElement(children: .contain)
    }

    /// The line at the top of an unwrapped file whose content is scrolled down by `offset` points.
    static func topLine(offset: CGFloat, lineCount: Int) -> Int {
        guard lineCount > 0 else { return 0 }
        let line = Int(((offset - OpenCodeCodeFileMetrics.verticalPadding) / OpenCodeCodeFileMetrics.lineMinHeight).rounded())
        return min(max(line, 0), lineCount - 1)
    }

    private func row(_ index: Int) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: OpenCodeCodeFileMetrics.gutterSpacing) {
            Text(String(index + 1))
                .font(OpenCodeTypography.code)
                .foregroundStyle(OpenCodeColors.textWeaker)
                .frame(width: gutterWidth, alignment: .trailing)
                .accessibilityHidden(true)
            lineText(index)
                .lineLimit(wrapsLines ? nil : 1)
                .fixedSize(horizontal: !wrapsLines, vertical: true)
                .frame(maxWidth: wrapsLines ? .infinity : nil, alignment: .leading)
        }
        .padding(.horizontal, OpenCodeCodeFileMetrics.horizontalPadding)
        .frame(minHeight: OpenCodeCodeFileMetrics.lineMinHeight, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Line \(index + 1), \(lines[index])")
        .onAppear { onLineAppear?(index) }
    }

    @ViewBuilder
    private func lineText(_ index: Int) -> some View {
        let line = lines[index]
        if let marks = lineMarks[index], !marks.isEmpty {
            Text(Self.marked(line, spans: lineSpans[index] ?? [], marks: marks))
                .font(OpenCodeTypography.code)
        } else if let spans = lineSpans[index], !spans.isEmpty {
            // Colored text carries its own foreground; do not stack `.foregroundStyle`.
            Text(NativeMarkdownAttributedString.code(line, spans: spans))
                .font(OpenCodeTypography.code)
        } else {
            Text(line.isEmpty ? " " : line)
                .font(OpenCodeTypography.code)
                .foregroundStyle(OpenCodeColors.codeText)
        }
    }

    private static func marked(_ line: String, spans: [NativeCodeSpan], marks: [Range<Int>]) -> AttributedString {
        var text: AttributedString
        if spans.isEmpty {
            text = AttributedString(line.isEmpty ? " " : line)
            text.foregroundColor = OpenCodeColors.codeText
        } else {
            text = NativeMarkdownAttributedString.code(line, spans: spans)
        }
        let length = line.utf16.count
        for mark in marks {
            let start = min(max(mark.lowerBound, 0), length)
            let end = min(max(mark.upperBound, start), length)
            guard start < end else { continue }
            let lower = String.Index(utf16Offset: start, in: line)
            let upper = String.Index(utf16Offset: end, in: line)
            guard let range = Range(lower..<upper, in: text) else { continue }
            text[range].backgroundColor = OpenCodeColors.warning.opacity(OpenCodeCodeFileMetrics.markOpacity)
        }
        return text
    }
}

private extension View {
    /// Reports how far the content is scrolled down; iOS 17 has no scroll geometry API.
    @ViewBuilder
    func openCodeOnVerticalOffsetChange(_ action: @escaping (CGFloat) -> Void) -> some View {
        if #available(iOS 18.0, *) {
            onScrollGeometryChange(for: CGFloat.self, of: { $0.contentOffset.y + $0.contentInsets.top }) { _, offset in
                action(offset)
            }
        } else {
            self
        }
    }
}

#Preview("Code File") {
    let lines = [
        "import SwiftUI",
        "",
        "struct ContentView: View {",
        "    var body: some View {",
        "        Text(\"A long line that keeps going so the horizontal scroll is easy to see in the preview\")",
        "    }",
        "}",
    ]
    return VStack(spacing: 16) {
        OpenCodeCodeFile(
            lines: lines,
            lineSpans: [0: [NativeCodeSpan(start: 0, end: 6, role: .keyword)]]
        )
        .frame(height: 200)
        OpenCodeCodeFile(lines: lines, lineMarks: [2: [0..<6], 4: [8..<12]], wrapsLines: true)
            .frame(height: 220)
    }
    .openCodeTheme()
}
