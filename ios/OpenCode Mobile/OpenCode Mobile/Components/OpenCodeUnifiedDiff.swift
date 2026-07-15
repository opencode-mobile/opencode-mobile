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

struct UnifiedDiffDocument: Sendable, Equatable {
    let rows: [UnifiedDiffRow]
    let hunks: [UnifiedDiffHunk]

    func visibleRows(contextMode: UnifiedDiffContextMode, contextLines: Int = 3) -> [UnifiedDiffRow] {
        guard contextMode == .collapsed else { return rows }
        let changedIndices = rows.indices.filter { rows[$0].kind == .addition || rows[$0].kind == .deletion }
        return rows.enumerated().compactMap { index, row in
            guard row.kind == .context else { return row }
            return changedIndices.contains { abs(index - $0) <= contextLines } ? row : nil
        }
    }
}

enum UnifiedDiffParser {
    static func parse(_ patch: String) -> UnifiedDiffDocument {
        guard !patch.isEmpty else { return UnifiedDiffDocument(rows: [], hunks: []) }
        var oldLine: Int?
        var newLine: Int?
        var insideHunk = false
        var malformed = false
        var headers: [HunkHeader] = []
        var lines = patch.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        if patch.hasSuffix("\n"), lines.last?.isEmpty == true { lines.removeLast() }
        let rows = lines.enumerated().map { index, line in
            if let header = parseHeader(line, rowID: index) {
                oldLine = header.oldStart
                newLine = header.newStart
                insideHunk = true
                headers.append(header)
                return UnifiedDiffRow(id: index, kind: .hunk, oldLine: nil, newLine: nil, text: line)
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
                return UnifiedDiffRow(id: index, kind: .metadata, oldLine: nil, newLine: nil, text: line)
            }
            guard insideHunk else {
                if line.hasPrefix("@@") { malformed = true }
                return UnifiedDiffRow(id: index, kind: .metadata, oldLine: nil, newLine: nil, text: line)
            }
            guard line.hasPrefix(" ") else {
                malformed = true
                return UnifiedDiffRow(id: index, kind: .metadata, oldLine: nil, newLine: nil, text: line)
            }
            headers[headers.count - 1].actualOldCount += 1
            headers[headers.count - 1].actualNewCount += 1
            let row = UnifiedDiffRow(
                id: index,
                kind: .context,
                oldLine: oldLine,
                newLine: newLine,
                text: line.hasPrefix(" ") ? String(line.dropFirst()) : line
            )
            oldLine = oldLine.map { $0 + 1 }
            newLine = newLine.map { $0 + 1 }
            return row
        }
        guard !malformed, headers.allSatisfy(\.isValid) else {
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

    private static func parseHeader(_ line: String, rowID: Int) -> HunkHeader? {
        let tokens = line.split(separator: " ", omittingEmptySubsequences: true)
        guard tokens.count >= 4, tokens[0] == "@@", tokens[3] == "@@",
              let oldRange = parseRange(tokens[1], prefix: "-"),
              let newRange = parseRange(tokens[2], prefix: "+")
        else { return nil }
        return HunkHeader(
            rowID: rowID,
            text: line,
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
    }
}

struct OpenCodeUnifiedDiff: View {
    let file: FileDiff
    let document: UnifiedDiffDocument
    var contextMode: UnifiedDiffContextMode = .collapsed
    var selectedHunkID: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView(.horizontal) {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(rows) { row in
                        rowView(row)
                            .id(row.id)
                    }
                }
            }
        }
        .background(OpenCodeColors.surfaceBase)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(OpenCodeColors.borderWeakerBase))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(file.status.rawValue.capitalized) file \(file.path), \(file.additions) additions, \(file.deletions) deletions")
    }

    private var rows: [UnifiedDiffRow] {
        document.visibleRows(contextMode: contextMode)
    }

    private func rowView(_ row: UnifiedDiffRow) -> some View {
        HStack(spacing: 0) {
            Text(row.oldLine.map(String.init) ?? "")
                .frame(width: 24, alignment: .trailing)
            Text(row.newLine.map(String.init) ?? "")
                .frame(width: 24, alignment: .trailing)
            Text(marker(row.kind)).frame(width: 20)
            Text(row.text).padding(.horizontal, 8)
        }
        .font(OpenCodeTypography.code)
        .foregroundStyle(foreground(row.kind))
        .frame(minHeight: 20)
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
