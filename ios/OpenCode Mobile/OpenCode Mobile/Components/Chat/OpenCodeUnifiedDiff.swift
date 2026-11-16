import SwiftUI

enum UnifiedDiffRowKind: String, Sendable, Equatable {
    case hunk
    case metadata
    case context
    case addition
    case deletion
}

enum UnifiedDiffContextMode: Sendable, Equatable {
    case collapsed
    case expanded
}

struct UnifiedDiffRow: Identifiable, Sendable, Equatable {
    let id: Int
    let kind: UnifiedDiffRowKind
    let oldLine: Int?
    let newLine: Int?
    let text: String
}

struct UnifiedDiffHunk: Identifiable, Sendable, Equatable {
    let id: Int
    let header: String
    let oldStart: Int
    let oldCount: Int
    let newStart: Int
    let newCount: Int
    let rows: [UnifiedDiffRow]
}

struct UnifiedDiffScrollTarget: Hashable {
    let rowID: Int
}

struct UnifiedDiffDocument: Sendable, Equatable {
    static let defaultContextLines = 3

    let rows: [UnifiedDiffRow]
    let hunks: [UnifiedDiffHunk]
    /// Derived once per document, in linear time; not part of equality.
    private let collapsedRows: [UnifiedDiffRow]
    private let expandedLayout: UnifiedDiffLayout
    private let collapsedLayout: UnifiedDiffLayout

    init(rows: [UnifiedDiffRow], hunks: [UnifiedDiffHunk]) {
        self.rows = rows
        self.hunks = hunks
        let collapsed = UnifiedDiffVisibility.collapsedRows(rows, contextLines: Self.defaultContextLines)
        collapsedRows = collapsed
        expandedLayout = UnifiedDiffLayout(rows: rows)
        collapsedLayout = UnifiedDiffLayout(rows: collapsed)
    }

    static func == (lhs: UnifiedDiffDocument, rhs: UnifiedDiffDocument) -> Bool {
        lhs.rows == rhs.rows && lhs.hunks == rhs.hunks
    }

    /// Rows shown in `contextMode`: collapsed keeps `contextLines` context rows on each side of a
    /// change. Linear, and computed once for the default.
    func visibleRows(contextMode: UnifiedDiffContextMode, contextLines: Int = defaultContextLines) -> [UnifiedDiffRow] {
        guard contextMode == .collapsed else { return rows }
        guard contextLines != Self.defaultContextLines else { return collapsedRows }
        return UnifiedDiffVisibility.collapsedRows(rows, contextLines: contextLines)
    }

    /// True when collapsing hides some context, so expanding shows more.
    var canExpandContext: Bool { collapsedRows.count < rows.count }

    func layout(contextMode: UnifiedDiffContextMode) -> UnifiedDiffLayout {
        contextMode == .collapsed ? collapsedLayout : expandedLayout
    }
}

/// What the diff view needs beyond its rows, computed once per row list: the rows likely to be
/// widest (their laid-out width sets the scroll width) and where the hunk rows sit.
struct UnifiedDiffLayout: Sendable {
    let widestRows: [UnifiedDiffRow]
    /// Each hunk row's ID and index in the visible rows.
    let hunkRows: [UnifiedDiffHunkRow]

    init(rows: [UnifiedDiffRow]) {
        widestRows = UnifiedDiffVisibility.widestRows(rows)
        hunkRows = rows.enumerated().compactMap { index, row in
            row.kind == .hunk ? UnifiedDiffHunkRow(rowID: row.id, index: index) : nil
        }
    }
}

struct UnifiedDiffHunkRow: Sendable, Equatable, Identifiable {
    let rowID: Int
    let index: Int
    var id: Int { rowID }
}

enum UnifiedDiffVisibility {
    /// Rows laid out to find the scroll width; the rest are assumed no wider.
    static let widestRowCandidates = 8

    /// Collapsed rows in two passes: a context row is kept when a change sits within
    /// `contextLines` rows before or after it. `onStep` counts the work for tests.
    static func collapsedRows(
        _ rows: [UnifiedDiffRow],
        contextLines: Int,
        onStep: () -> Void = {}
    ) -> [UnifiedDiffRow] {
        var keep = [Bool](repeating: false, count: rows.count)
        var lastChanged: Int?
        for index in rows.indices {
            onStep()
            if rows[index].isChange { lastChanged = index }
            if let lastChanged, index - lastChanged <= contextLines { keep[index] = true }
        }
        var nextChanged: Int?
        for index in rows.indices.reversed() {
            onStep()
            if rows[index].isChange { nextChanged = index }
            if let nextChanged, nextChanged - index <= contextLines { keep[index] = true }
        }
        var visible: [UnifiedDiffRow] = []
        visible.reserveCapacity(rows.count)
        for index in rows.indices where rows[index].kind != .context || keep[index] {
            visible.append(rows[index])
        }
        return visible
    }

    /// The `limit` rows with the most display columns (wide characters count twice, tabs as a tab stop).
    static func widestRows(_ rows: [UnifiedDiffRow], limit: Int = widestRowCandidates) -> [UnifiedDiffRow] {
        guard rows.count > limit else { return rows }
        var best: [(columns: Int, row: UnifiedDiffRow)] = []
        best.reserveCapacity(limit + 1)
        for row in rows {
            let columns = displayColumns(row.text)
            if best.count == limit, columns <= best[limit - 1].columns { continue }
            let position = best.firstIndex { $0.columns < columns } ?? best.count
            best.insert((columns, row), at: position)
            if best.count > limit { best.removeLast() }
        }
        return best.map(\.row)
    }

    /// Digits in the largest line number of `rows`; the line-number columns are sized for it.
    static func lineNumberDigits(_ rows: [UnifiedDiffRow]) -> Int {
        var largest = 0
        for row in rows { largest = max(largest, row.oldLine ?? 0, row.newLine ?? 0) }
        return String(largest).count
    }

    static func displayColumns(_ text: String) -> Int {
        var columns = 0
        for scalar in text.unicodeScalars {
            let value = scalar.value
            if value == 0x09 {
                columns += 8
            } else if isWide(value) {
                columns += 2
            } else {
                columns += 1
            }
        }
        return columns
    }

    private static func isWide(_ value: UInt32) -> Bool {
        (0x1100...0x115F).contains(value)
            || (0x2E80...0xA4CF).contains(value)
            || (0xAC00...0xD7A3).contains(value)
            || (0xF900...0xFAFF).contains(value)
            || (0xFE30...0xFE4F).contains(value)
            || (0xFF00...0xFF60).contains(value)
            || (0xFFE0...0xFFE6).contains(value)
            || (0x1F300...0x1FAFF).contains(value)
            || (0x20000...0x3FFFD).contains(value)
    }
}

/// The rows to draw: what the scroll view shows plus `overscan` each way, in blocks so small
/// scrolls keep the same rows.
enum UnifiedDiffRowWindow {
    static let blockRows = 32

    static func rows(shownTop: CGFloat, shownBottom: CGFloat, rowHeight: CGFloat, overscan: CGFloat, rowCount: Int) -> Range<Int> {
        guard rowCount > 0, rowHeight > 0 else { return 0..<0 }
        let firstRow = max(0, Int(((shownTop - overscan) / rowHeight).rounded(.down)))
        let lastRow = max(0, Int(((max(shownTop, shownBottom) + overscan) / rowHeight).rounded(.down)))
        let first = (firstRow / blockRows) * blockRows
        let end = (lastRow / blockRows + 1) * blockRows
        return min(first, rowCount)..<min(end, rowCount)
    }
}

private extension UnifiedDiffRow {
    var isChange: Bool { kind == .addition || kind == .deletion }
}

/// Keeps the parsed document for the last patch it saw, so a view rebuilt with the same patch
/// parses it once. A reference type so a value holding it can be copied without parsing.
final class UnifiedDiffDocumentMemo {
    private let parse: (String, Bool) -> UnifiedDiffDocument
    private var patch: String?
    private var truncated = false
    private var cached: UnifiedDiffDocument?

    init(parse: @escaping (String, Bool) -> UnifiedDiffDocument = { UnifiedDiffParser.parse($0, truncated: $1) }) {
        self.parse = parse
    }

    func document(for patch: String, truncated: Bool = false) -> UnifiedDiffDocument {
        if let cached, self.truncated == truncated, self.patch == patch { return cached }
        let document = parse(patch, truncated)
        self.patch = patch
        self.truncated = truncated
        cached = document
        return document
    }
}

enum UnifiedDiffParser {
    /// A `truncated` patch was cut at a line end, so its last hunk may hold fewer rows than its
    /// header says.
    static func parse(_ patch: String, truncated: Bool = false) -> UnifiedDiffDocument {
        guard !patch.isEmpty else { return UnifiedDiffDocument(rows: [], hunks: []) }
        var oldLine: Int?
        var newLine: Int?
        var insideHunk = false
        var malformed = false
        var headers: [HunkHeader] = []
        // Substrings of the patch; each row copies only its own text.
        var lines = patch.split(separator: "\n", omittingEmptySubsequences: false)
        if patch.hasSuffix("\n"), lines.last?.isEmpty == true { lines.removeLast() }
        let rows = lines.enumerated().map { index, line in
            if let header = parseHeader(line, rowID: index) {
                oldLine = header.oldStart
                newLine = header.newStart
                insideHunk = true
                headers.append(header)
                return UnifiedDiffRow(id: index, kind: .hunk, oldLine: nil, newLine: nil, text: String(line))
            }
            if insideHunk, line.hasPrefix("+") {
                headers[headers.count - 1].actualNewCount += 1
                defer { newLine = newLine.map { $0 + 1 } }
                return UnifiedDiffRow(id: index, kind: .addition, oldLine: nil, newLine: newLine, text: String(line.dropFirst()))
            }
            if insideHunk, line.hasPrefix("-") {
                headers[headers.count - 1].actualOldCount += 1
                defer { oldLine = oldLine.map { $0 + 1 } }
                return UnifiedDiffRow(id: index, kind: .deletion, oldLine: oldLine, newLine: nil, text: String(line.dropFirst()))
            }
            if line.hasPrefix("\\ No newline at end of file") {
                return UnifiedDiffRow(id: index, kind: .metadata, oldLine: nil, newLine: nil, text: String(line))
            }
            guard insideHunk else {
                if line.hasPrefix("@@") { malformed = true }
                return UnifiedDiffRow(id: index, kind: .metadata, oldLine: nil, newLine: nil, text: String(line))
            }
            guard line.hasPrefix(" ") else {
                malformed = true
                return UnifiedDiffRow(id: index, kind: .metadata, oldLine: nil, newLine: nil, text: String(line))
            }
            headers[headers.count - 1].actualOldCount += 1
            headers[headers.count - 1].actualNewCount += 1
            let row = UnifiedDiffRow(
                id: index,
                kind: .context,
                oldLine: oldLine,
                newLine: newLine,
                text: line.hasPrefix(" ") ? String(line.dropFirst()) : String(line)
            )
            oldLine = oldLine.map { $0 + 1 }
            newLine = newLine.map { $0 + 1 }
            return row
        }
        let lastHeaderIndex = headers.count - 1
        let headersValid = headers.indices.allSatisfy { index in
            truncated && index == lastHeaderIndex ? headers[index].fitsDeclaredCounts : headers[index].isValid
        }
        guard !malformed, headersValid else {
            return UnifiedDiffDocument(rows: rows, hunks: [])
        }
        let hunks = headers.enumerated().map { index, header in
            let end = headers.indices.contains(index + 1) ? headers[index + 1].rowID : rows.count
            return UnifiedDiffHunk(
                id: header.rowID,
                header: header.text,
                oldStart: header.oldStart,
                oldCount: header.oldCount,
                newStart: header.newStart,
                newCount: header.newCount,
                rows: Array(rows[(header.rowID + 1)..<end])
            )
        }
        return UnifiedDiffDocument(rows: rows, hunks: hunks)
    }

    private static func parseHeader(_ line: Substring, rowID: Int) -> HunkHeader? {
        // Only a line whose first word is `@@` can be a header; skip splitting every other row.
        guard line.drop(while: { $0 == " " }).hasPrefix("@@") else { return nil }
        let tokens = line.split(separator: " ", omittingEmptySubsequences: true)
        guard tokens.count >= 4, tokens[0] == "@@", tokens[3] == "@@",
              let oldRange = parseRange(tokens[1], prefix: "-"),
              let newRange = parseRange(tokens[2], prefix: "+")
        else { return nil }
        return HunkHeader(
            rowID: rowID,
            text: String(line),
            oldStart: oldRange.start,
            oldCount: oldRange.count,
            newStart: newRange.start,
            newCount: newRange.count
        )
    }

    private static func parseRange(_ token: Substring, prefix: Character) -> (start: Int, count: Int)? {
        guard token.first == prefix else { return nil }
        let values = token.dropFirst().split(separator: ",", omittingEmptySubsequences: false)
        guard let start = Int(values[0]) else { return nil }
        let count = values.count > 1 ? Int(values[1]) : 1
        guard let count else { return nil }
        return (start, count)
    }

    private struct HunkHeader {
        let rowID: Int
        let text: String
        let oldStart: Int
        let oldCount: Int
        let newStart: Int
        let newCount: Int
        var actualOldCount = 0
        var actualNewCount = 0
        var isValid: Bool { actualOldCount == oldCount && actualNewCount == newCount }
        /// A hunk cut short by truncation: no more rows than declared.
        var fitsDeclaredCounts: Bool { actualOldCount <= oldCount && actualNewCount <= newCount }
    }
}

struct OpenCodeUnifiedDiff: View {
    let file: FileDiff

    /// "Modified file src/a.swift, 3 additions, 1 deletions".
    private var fileSummary: LocalizedStringResource {
        let path = file.path
        let additions = file.additions
        let deletions = file.deletions
        return switch file.status {
        case .added:
            LocalizedStringResource(
                "component.unified_diff.summary_added",
                defaultValue: "Added file \(path), \(additions) additions, \(deletions) deletions"
            )
        case .deleted:
            LocalizedStringResource(
                "component.unified_diff.summary_deleted",
                defaultValue: "Deleted file \(path), \(additions) additions, \(deletions) deletions"
            )
        case .modified:
            LocalizedStringResource(
                "component.unified_diff.summary_modified",
                defaultValue: "Modified file \(path), \(additions) additions, \(deletions) deletions"
            )
        }
    }
    let document: UnifiedDiffDocument
    var contextMode: UnifiedDiffContextMode = .collapsed
    var selectedHunkID: Int?
    /// Code text scale (#1044): type, gutters and row height follow it.
    @Environment(\.openCodeTextScale) private var textScale

    /// The tallest a row's text measured, when it is more than the nominal height.
    @State private var measuredRowHeight: CGFloat = 0
    /// Rows drawn; nil until the nearest vertical scroll view reports what it shows.
    @State private var drawnRows: Range<Int>?

    private var rowHeight: CGFloat { max(20 * textScale, measuredRowHeight) }

    var body: some View {
        let rows = document.visibleRows(contextMode: contextMode)
        let layout = document.layout(contextMode: contextMode)
        let rowHeight = rowHeight
        // Two digits fit the base width; each further digit widens the column, so a number is
        // never cut to fit the one shared row height.
        let gutterWidth = (24 + 9 * CGFloat(max(0, UnifiedDiffVisibility.lineNumberDigits(rows) - 2))) * textScale
        ScrollView(.horizontal) {
            ZStack(alignment: .topLeading) {
                // The widest rows, laid out but never shown, give the scroll its full width
                // without laying out every row.
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(layout.widestRows) { row in rowView(row, gutterWidth: gutterWidth) }
                }
                .fixedSize()
                .frame(height: 0, alignment: .top)
                .hidden()
                .accessibilityHidden(true)
                rowView(UnifiedDiffRow(id: -1, kind: .context, oldLine: 1, newLine: 1, text: ""), gutterWidth: gutterWidth)
                    .fixedSize()
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
                        if abs(height - measuredRowHeight) > 0.5 { measuredRowHeight = height }
                    }
                    .frame(height: 0, alignment: .top)
                    .hidden()
                    .accessibilityHidden(true)
                drawnRowsView(rows, rowHeight: rowHeight, gutterWidth: gutterWidth)
            }
            .fixedSize(horizontal: true, vertical: false)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceBase)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(OpenCodeColors.borderWeakerBase))
        .overlay(alignment: .topLeading) {
            // Scroll targets for hunk rows only, at each hunk's row offset.
            ZStack(alignment: .topLeading) {
                ForEach(layout.hunkRows) { hunk in
                    Color.clear
                        .frame(width: 1, height: rowHeight)
                        .id(UnifiedDiffScrollTarget(rowID: hunk.rowID))
                        .padding(.top, CGFloat(hunk.index) * rowHeight)
                }
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(fileSummary))
    }

    /// All rows' height, with only the rows near what the enclosing vertical scroll view shows
    /// drawn, so a long diff stays lazy inside a scrolling screen or sheet. Without a vertical
    /// scroll view every row is drawn.
    private func drawnRowsView(_ rows: [UnifiedDiffRow], rowHeight: CGFloat, gutterWidth: CGFloat) -> some View {
        let range = drawnRange(rowCount: rows.count, rowHeight: rowHeight)
        return VStack(alignment: .leading, spacing: 0) {
            ForEach(rows[range]) { row in
                rowView(row, gutterWidth: gutterWidth)
                    .frame(height: rowHeight, alignment: .leading)
                    .clipped()
            }
        }
        .fixedSize()
        .padding(.top, CGFloat(range.lowerBound) * rowHeight)
        .frame(height: CGFloat(rows.count) * rowHeight, alignment: .topLeading)
        .onGeometryChange(for: CGRect?.self) { proxy in
            proxy.bounds(of: .scrollView(axis: .vertical))
        } action: { shown in
            let next: Range<Int>
            if let shown {
                next = UnifiedDiffRowWindow.rows(
                    shownTop: shown.minY,
                    shownBottom: shown.maxY,
                    rowHeight: rowHeight,
                    overscan: max(shown.height, 400),
                    rowCount: rows.count
                )
            } else {
                next = 0..<rows.count
            }
            if next != drawnRows { drawnRows = next }
        }
    }

    private func drawnRange(rowCount: Int, rowHeight: CGFloat) -> Range<Int> {
        let range = drawnRows ?? UnifiedDiffRowWindow.rows(
            shownTop: 0,
            shownBottom: 0,
            rowHeight: rowHeight,
            overscan: 1_000,
            rowCount: rowCount
        )
        return min(range.lowerBound, rowCount)..<min(range.upperBound, rowCount)
    }

    private func rowView(_ row: UnifiedDiffRow, gutterWidth: CGFloat) -> some View {
        HStack(spacing: 0) {
            Text(row.oldLine.map(String.init) ?? "")
                .lineLimit(1)
                .frame(width: gutterWidth, alignment: .trailing)
            Text(row.newLine.map(String.init) ?? "")
                .lineLimit(1)
                .frame(width: gutterWidth, alignment: .trailing)
            Text(marker(row.kind)).frame(width: 20 * textScale)
            Text(row.text)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 8)
        }
        .openCodeFont(.code)
        .foregroundStyle(foreground(row.kind))
        .frame(minHeight: 20 * textScale)
        .background(selectedHunkID == row.id && row.kind == .hunk ? OpenCodeColors.surfaceInteractiveWeak : background(row.kind))
        .accessibilityLabel(accessibilityLabel(row))
    }

    private func marker(_ kind: UnifiedDiffRowKind) -> String {
        switch kind { case .addition: "+"; case .deletion: "−"; default: "" }
    }

    private func foreground(_ kind: UnifiedDiffRowKind) -> Color {
        switch kind {
        case .addition: OpenCodeColors.diffAdd
        case .deletion: OpenCodeColors.diffDelete
        case .hunk, .metadata: OpenCodeColors.syntaxDiffUnknown
        case .context: OpenCodeColors.textStrong
        }
    }

    private func background(_ kind: UnifiedDiffRowKind) -> Color {
        switch kind {
        case .addition: OpenCodeColors.surfaceSuccessBase
        case .deletion: OpenCodeColors.surfaceCriticalBase
        case .hunk: OpenCodeColors.surfaceRaisedBase
        case .metadata: .clear
        case .context: .clear
        }
    }

    private func accessibilityLabel(_ row: UnifiedDiffRow) -> String {
        let location = [row.oldLine.map { "old line \($0)" }, row.newLine.map { "new line \($0)" }].compactMap { $0 }.joined(separator: ", ")
        return "\(row.kind.rawValue), \(location), \(row.text)"
    }
}
