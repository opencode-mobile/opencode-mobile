import SwiftUI
import UIKit

/// Inspector chrome pinned under the sheet title: eyebrow, subline, command strip.
struct ToolResultPinnedHeader: View {
    let presentation: ToolResultPresentation

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let eyebrow = presentation.eyebrow {
                Text(eyebrow)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeaker)
            }
            switch presentation {
            case .fileStatus(let status):
                countLine(status.counts)
                commandStrip(status.command, exitCode: status.exitCode, durationLabel: status.durationLabel)
            case .pathList(let list):
                commandStrip(list.command, exitCode: nil, durationLabel: nil)
            case .pullRequest(let pull):
                commandStrip(pull.command, exitCode: pull.exitCode, durationLabel: pull.durationLabel)
            case .thought(let thought):
                if let duration = thought.durationLabel {
                    subline(duration)
                }
            case .source(let source):
                sourceSubline(source)
            case .grep(let grep):
                subline(grepSubline(grep))
            case .edit(let edit):
                diffCounts(additions: edit.additions, deletions: edit.deletions)
            case .unrecognized:
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 8)
    }

    private func grepSubline(_ grep: ToolResultPresentation.Grep) -> String {
        var text = "\(grep.matchCount) matches · \(grep.groups.count) files"
        if let searchPath = grep.searchPath, !searchPath.isEmpty {
            text += " · \(searchPath)"
        }
        return text
    }
}

/// Scrolling inspector body. Source is full-bleed; other shapes expect the sheet inset.
struct ToolResultBody: View {
    let presentation: ToolResultPresentation

    var body: some View {
        switch presentation {
        case .fileStatus(let status):
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(status.files.enumerated()), id: \.offset) { _, row in
                    FileResultRow(
                        letter: row.letter,
                        tone: row.tone,
                        name: row.name,
                        directory: row.directory,
                        accessible: "\(row.letter) \(row.name), \(row.fullPath)"
                    )
                }
            }
        case .pathList(let list):
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(list.paths.enumerated()), id: \.offset) { _, row in
                    FileResultRow(
                        letter: nil,
                        tone: nil,
                        name: row.name,
                        directory: row.directory,
                        accessible: [row.name, row.fullPath].joined(separator: ", ")
                    )
                }
            }
        case .pullRequest(let pull):
            PullRequestBody(presentation: pull)
        case .thought(let thought):
            ThoughtBody(presentation: thought)
        case .source(let source):
            SourceBody(presentation: source)
        case .grep(let grep):
            GrepBody(presentation: grep)
        case .edit(let edit):
            EditBody(presentation: edit)
        case .unrecognized(let unrecognized):
            UnrecognizedBody(presentation: unrecognized)
        }
    }
}

struct ToolResultBodyInset: ViewModifier {
    let fullBleed: Bool

    func body(content: Content) -> some View {
        if fullBleed {
            content.frame(maxWidth: .infinity, alignment: .leading)
        } else {
            content
                .frame(maxWidth: .infinity, alignment: .leading)
                .openCodeBottomSheetContentInset()
        }
    }
}

struct ToolResultCopyFooter: View {
    let raw: String

    var body: some View {
        OpenCodeButton(variant: .fullWidthText(title: "Copy output")) {
            UIPasteboard.general.string = raw
        }
    }
}

private struct FileResultRow: View {
    let letter: String?
    let tone: ToolResultPresentation.StatusTone?
    let name: String
    let directory: String?
    let accessible: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            if let letter {
                Text(letter)
                    .font(OpenCodeTypography.code)
                    .foregroundStyle(tone.map(toneColor) ?? OpenCodeColors.textBase)
                    .frame(width: 16, alignment: .leading)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                    .truncationMode(.tail)
                if let directory {
                    StartEllipsisText(directory)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessible)
    }
}

/// Directory caption ellipsized from the start, same trick as desktop apply-patch.
private struct StartEllipsisText: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text("\u{202A}\(text)\u{202C}")
            .font(OpenCodeTypography.bodySecondary)
            .foregroundStyle(OpenCodeColors.textWeaker)
            .lineLimit(1)
            .truncationMode(.tail)
            .multilineTextAlignment(.leading)
            .environment(\.layoutDirection, .rightToLeft)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityHidden(true)
    }
}

private struct PullRequestBody: View {
    let presentation: ToolResultPresentation.PullRequest

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("#\(presentation.number)")
                .font(OpenCodeTypography.display)
                .foregroundStyle(OpenCodeColors.textStrong)
            Button {
                UIPasteboard.general.string = presentation.url
            } label: {
                Text(presentation.url)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.interactive)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(presentation.url)
            .accessibilityHint("Copies the link")
            if let branch = presentation.branch {
                Text(branch)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
            if let title = presentation.pullTitle {
                Text(title)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ThoughtBody: View {
    let presentation: ToolResultPresentation.Thought

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 10) {
                Rectangle()
                    .fill(OpenCodeColors.interactive)
                    .frame(width: 2)
                Text(presentation.callout)
                    .font(OpenCodeTypography.body)
                    .fontWeight(.medium)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineSpacing(4)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .fixedSize(horizontal: false, vertical: true)
            ForEach(Array(presentation.paragraphs.enumerated()), id: \.offset) { _, paragraph in
                Text(paragraph)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineSpacing(4)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct SourceBody: View {
    let presentation: ToolResultPresentation.Source

    @State private var spans: [NativeCodeSpan] = []

    var body: some View {
        let source = presentation.lines.map(\.text).joined(separator: "\n")
        let offsets = lineOffsets(presentation.lines)
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .trailing, spacing: 0) {
                ForEach(Array(presentation.lines.enumerated()), id: \.offset) { _, line in
                    Text(String(line.number))
                        .font(OpenCodeTypography.code)
                        .foregroundStyle(OpenCodeColors.textWeaker)
                        .frame(width: 36, height: 20, alignment: .trailing)
                        .padding(.trailing, 8)
                        .accessibilityHidden(true)
                }
            }
            ScrollView(.horizontal, showsIndicators: true) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(presentation.lines.enumerated()), id: \.offset) { index, line in
                        sourceLine(line, spans: spansForLine(offsets[index], spans))
                    }
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.leading, 12)
        .task(id: source + (presentation.highlightLanguage ?? "")) {
            guard let language = presentation.highlightLanguage else {
                spans = []
                return
            }
            let highlighted = await Task.detached(priority: .userInitiated) {
                HighlightCoreOneShotHighlighter.spans(language: language, source: source)
            }.value
            spans = highlighted
        }
    }

    @ViewBuilder
    private func sourceLine(_ line: ToolResultPresentation.SourceLine, spans: [NativeCodeSpan]) -> some View {
        Group {
            if spans.isEmpty {
                Text(line.text.isEmpty ? " " : line.text)
                    .font(OpenCodeTypography.code)
                    .foregroundStyle(OpenCodeColors.textStrong)
            } else {
                Text(NativeMarkdownAttributedString.code(line.text, spans: spans))
                    .font(OpenCodeTypography.code)
            }
        }
        .lineLimit(1)
        .fixedSize(horizontal: true, vertical: false)
        .frame(height: 20, alignment: .leading)
        .accessibilityLabel("Line \(line.number), \(line.text)")
    }

    private func lineOffsets(_ lines: [ToolResultPresentation.SourceLine]) -> [(start: Int, end: Int)] {
        var offset = 0
        return lines.enumerated().map { index, line in
            let start = offset
            let length = (line.text as NSString).length
            offset += length
            if index < lines.count - 1 {
                offset += 1
            }
            return (start, start + length)
        }
    }

    private func spansForLine(_ bounds: (start: Int, end: Int), _ spans: [NativeCodeSpan]) -> [NativeCodeSpan] {
        spans.compactMap { span in
            let from = max(span.start, bounds.start)
            let to = min(span.end, bounds.end)
            guard from < to else { return nil }
            return NativeCodeSpan(start: from - bounds.start, end: to - bounds.start, role: span.role)
        }
    }
}

private struct GrepBody: View {
    let presentation: ToolResultPresentation.Grep

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(presentation.groups.enumerated()), id: \.offset) { _, group in
                VStack(alignment: .leading, spacing: 2) {
                    Text(group.name)
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    if let directory = group.directory {
                        StartEllipsisText(directory)
                    }
                    ForEach(Array(group.hits.enumerated()), id: \.offset) { _, hit in
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(alignment: .firstTextBaseline, spacing: 0) {
                                Text(String(hit.line))
                                    .font(OpenCodeTypography.code)
                                    .foregroundStyle(OpenCodeColors.textWeaker)
                                    .frame(width: 36, alignment: .trailing)
                                    .padding(.trailing, 8)
                                Text(markPattern(hit.text, pattern: presentation.pattern))
                                    .font(OpenCodeTypography.code)
                                    .foregroundStyle(OpenCodeColors.textStrong)
                                    .lineLimit(1)
                                    .fixedSize(horizontal: true, vertical: false)
                            }
                        }
                        .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

private struct EditBody: View {
    let presentation: ToolResultPresentation.Edit

    var body: some View {
        OpenCodeUnifiedDiff(
            file: FileDiff(
                path: presentation.filename,
                patch: presentation.patch,
                additions: presentation.additions,
                deletions: presentation.deletions,
                status: .modified
            ),
            document: UnifiedDiffParser.parse(presentation.patch),
            contextMode: .expanded
        )
    }
}

private struct UnrecognizedBody: View {
    let presentation: ToolResultPresentation.Unrecognized

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let error = presentation.error {
                Text(error)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.error)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            ScrollView(.horizontal, showsIndicators: true) {
                Text(presentation.raw)
                    .font(OpenCodeTypography.code)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .fixedSize(horizontal: true, vertical: true)
                    .textSelection(.enabled)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

@ViewBuilder
private func countLine(_ counts: [ToolResultPresentation.Count]) -> some View {
    if counts.isEmpty {
        EmptyView()
    } else {
        HStack(spacing: 10) {
            ForEach(Array(counts.enumerated()), id: \.offset) { _, count in
                Text(count.label)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(toneColor(count.tone))
            }
        }
    }
}

private func subline(_ text: String) -> some View {
    Text(text)
        .font(OpenCodeTypography.bodySecondary)
        .foregroundStyle(OpenCodeColors.textBase)
        .lineLimit(1)
        .truncationMode(.tail)
}

@ViewBuilder
private func sourceSubline(_ source: ToolResultPresentation.Source) -> some View {
    let parts = [
        source.language,
        source.lines.isEmpty ? nil : "\(source.lines.count) lines",
        source.rangeLabel,
    ].compactMap { $0 }
    if parts.isEmpty {
        EmptyView()
    } else {
        subline(parts.joined(separator: " · "))
    }
}

@ViewBuilder
private func commandStrip(_ command: String?, exitCode: Int?, durationLabel: String?) -> some View {
    let hasCommand = command?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    if !hasCommand, exitCode == nil, durationLabel == nil {
        EmptyView()
    } else {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                if let command, hasCommand {
                    Text(command)
                        .font(OpenCodeTypography.code)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                }
                if let exitCode {
                    Text("exit \(exitCode)")
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(exitCode == 0 ? OpenCodeColors.success : OpenCodeColors.error)
                        .lineLimit(1)
                }
                if let durationLabel {
                    Text(durationLabel)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeaker)
                        .lineLimit(1)
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

private func diffCounts(additions: Int, deletions: Int) -> some View {
    HStack(spacing: 8) {
        if additions > 0 {
            Text("+\(additions)")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.diffAdd)
        }
        if deletions > 0 {
            Text("−\(deletions)")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.diffDelete)
        }
    }
}

private func toneColor(_ tone: ToolResultPresentation.StatusTone) -> Color {
    switch tone {
    case .modified: OpenCodeColors.warning
    case .added: OpenCodeColors.diffAdd
    case .deleted: OpenCodeColors.diffDelete
    case .other: OpenCodeColors.textBase
    }
}

private func markPattern(_ text: String, pattern: String) -> AttributedString {
    guard !pattern.isEmpty else { return AttributedString(text) }
    var result = AttributedString()
    var index = text.startIndex
    while index < text.endIndex {
        guard let found = text.range(of: pattern, options: .caseInsensitive, range: index..<text.endIndex) else {
            result += AttributedString(text[index...])
            break
        }
        if index < found.lowerBound {
            result += AttributedString(text[index..<found.lowerBound])
        }
        var marked = AttributedString(text[found])
        marked.backgroundColor = OpenCodeColors.warning.opacity(0.28)
        result += marked
        index = found.upperBound
    }
    return result
}
