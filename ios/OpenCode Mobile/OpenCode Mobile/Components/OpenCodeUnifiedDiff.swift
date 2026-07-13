import SwiftUI

enum UnifiedDiffRowKind: String, Sendable, Equatable {
    case hunk
    case metadata
    case context
    case addition
    case deletion
}

enum UnifiedDiffContextMode: Sendable {
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

enum UnifiedDiffParser {
    static func parse(_ patch: String) -> [UnifiedDiffRow] {
        var oldLine: Int?
        var newLine: Int?
        return patch.split(separator: "\n", omittingEmptySubsequences: false).enumerated().map { index, slice in
            let line = String(slice)
            if line.hasPrefix("@@") {
                let starts = line.split(separator: " ").compactMap { token -> Int? in
                    guard token.first == "-" || token.first == "+" else { return nil }
                    return Int(token.dropFirst().split(separator: ",").first ?? "")
                }
                oldLine = starts.first
                newLine = starts.dropFirst().first
                return UnifiedDiffRow(id: index, kind: .hunk, oldLine: nil, newLine: nil, text: line)
            }
            if line.hasPrefix("+") && !line.hasPrefix("+++") {
                defer { newLine = newLine.map { $0 + 1 } }
                return UnifiedDiffRow(id: index, kind: .addition, oldLine: nil, newLine: newLine, text: String(line.dropFirst()))
            }
            if line.hasPrefix("-") && !line.hasPrefix("---") {
                defer { oldLine = oldLine.map { $0 + 1 } }
                return UnifiedDiffRow(id: index, kind: .deletion, oldLine: oldLine, newLine: nil, text: String(line.dropFirst()))
            }
            if line.hasPrefix("\\ No newline at end of file") {
                return UnifiedDiffRow(id: index, kind: .metadata, oldLine: nil, newLine: nil, text: line)
            }
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
    }
}

struct OpenCodeUnifiedDiff: View {
    let file: FileDiff
    var contextMode: UnifiedDiffContextMode = .collapsed
    var reviewed = false
    var selectedHunkID: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            fileSummary
            Divider().overlay(OpenCodeColors.borderWeakerBase)
            ScrollView(.horizontal) {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(rows) { row in
                        rowView(row)
                    }
                }
            }
        }
        .background(OpenCodeColors.surfaceBase)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(OpenCodeColors.borderWeakerBase))
        .opacity(reviewed ? 0.72 : 1)
        .accessibilityElement(children: .contain)
    }

    private var rows: [UnifiedDiffRow] {
        let rows = UnifiedDiffParser.parse(file.patch ?? "")
        guard contextMode == .collapsed else { return rows }
        return rows.enumerated().compactMap { index, row in
            guard row.kind == .context else { return row }
            let range = max(0, index - 3) ... min(rows.count - 1, index + 3)
            return range.contains { rows[$0].kind != .context } ? row : nil
        }
    }

    private var fileSummary: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(file.path).font(OpenCodeTypography.code).foregroundStyle(OpenCodeColors.textStrong)
            Text("\(statusLabel)  +\(file.additions)  −\(file.deletions)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
        }
        .padding(12)
        .accessibilityElement(children: .combine)
    }

    private var statusLabel: String {
        switch file.status {
        case .added: "Added"
        case .deleted: "Deleted"
        case .modified: "Modified"
        }
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
