import OpenCodeMarkdownCore
import SwiftUI
import UIKit

enum OpenCodeRenderedMarkdownVariant: String, CaseIterable {
    case assistantMessage
    case outgoingMessage
    case standalone
}

enum OpenCodeRenderedMarkdownDetector {
    static func shouldRender(_ text: String) -> Bool {
        OpenCodeMarkdownDetector.shared.shouldRender(text: text)
    }
}

struct OpenCodeRenderedMarkdown: View {
    let document: NativeMarkdownDocument
    let variant: OpenCodeRenderedMarkdownVariant
    let initialHeight: CGFloat
    let onHeightChange: (CGFloat) -> Void
    let onLinkTap: (URL) -> Void

    @State private var measuredHeight: CGFloat

    init(
        document: NativeMarkdownDocument,
        variant: OpenCodeRenderedMarkdownVariant,
        initialHeight: CGFloat = 1,
        onHeightChange: @escaping (CGFloat) -> Void = { _ in },
        onLinkTap: @escaping (URL) -> Void = { url in UIApplication.shared.open(url) }
    ) {
        self.document = document
        self.variant = variant
        self.initialHeight = max(1, initialHeight)
        self.onHeightChange = onHeightChange
        self.onLinkTap = onLinkTap
        _measuredHeight = State(initialValue: max(1, initialHeight))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: blockSpacing) {
            ForEach(Array(document.blocks.enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .readRenderedMarkdownSize { size in
            let nextHeight = max(1, size.height)
            guard abs(measuredHeight - nextHeight) > 1 else { return }
            measuredHeight = nextHeight
            onHeightChange(nextHeight)
        }
        .environment(\.openURL, OpenURLAction { url in
            guard url.scheme == "http" || url.scheme == "https" else {
                return .discarded
            }
            onLinkTap(url)
            return .handled
        })
    }

    @ViewBuilder
    private func blockView(_ block: NativeMarkdownBlock) -> some View {
        switch block {
        case .paragraph(let inlines):
            inlineText(inlines, font: OpenCodeTypography.body, color: OpenCodeColors.textPrimary)
        case .heading(let level, let inlines):
            inlineText(inlines, font: headingFont(level), color: headingColor(level))
        case .blockquote(let inlines):
            HStack(alignment: .top, spacing: 10) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(OpenCodeColors.markdownQuoteBar)
                    .frame(width: 3, height: 26)
                inlineText(inlines, font: OpenCodeTypography.body, color: OpenCodeColors.markdownQuoteText)
            }
        case .bulletList(let items):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(items.indices, id: \.self) { index in
                    listRow(marker: "•", inlines: items[index])
                }
            }
        case .orderedList(let start, let items):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(items.indices, id: \.self) { index in
                    listRow(marker: "\(start + index).", inlines: items[index])
                }
            }
        case .codeBlock(_, let code, let spans):
            codeBlock(code: code, spans: spans)
        case .diffBlock(let lines):
            diffBlock(lines: lines)
        case .table(let header, let rows):
            table(header: header, rows: rows)
        case .thematicBreak:
            Rectangle()
                .fill(OpenCodeColors.markdownHr)
                .frame(height: 1)
        }
    }

    private func listRow(marker: String, inlines: [NativeMarkdownInline]) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(marker)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.markdownListMarker)
                .frame(width: 24, alignment: .leading)
            inlineText(inlines, font: OpenCodeTypography.body, color: OpenCodeColors.textPrimary)
        }
    }

    private func inlineText(
        _ inlines: [NativeMarkdownInline],
        font: Font,
        color: Color
    ) -> some View {
        Text(NativeMarkdownAttributedString.inline(inlines, font: font, color: color))
            .fixedSize(horizontal: false, vertical: true)
    }

    private func codeBlock(
        code: String,
        spans: [NativeCodeSpan]
    ) -> some View {
        ScrollView(.horizontal, showsIndicators: true) {
            Text(NativeMarkdownAttributedString.code(code, spans: spans))
                .font(OpenCodeTypography.code)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceSecondary, in: RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
        )
    }

    private func diffBlock(lines: [NativeDiffLine]) -> some View {
        ScrollView(.horizontal, showsIndicators: true) {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(lines.indices, id: \.self) { index in
                    let line = lines[index]
                    Text(NativeMarkdownAttributedString.code(line.text, spans: line.spans))
                        .font(OpenCodeTypography.code)
                        .fixedSize(horizontal: true, vertical: false)
                        .frame(minWidth: 320, alignment: .leading)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 1)
                        .background(diffLineBackground(line.kind))
                }
            }
            .padding(.vertical, 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceSecondary, in: RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
        )
    }

    private func table(
        header: [[NativeMarkdownInline]],
        rows: [[[NativeMarkdownInline]]]
    ) -> some View {
        ScrollView(.horizontal, showsIndicators: true) {
            VStack(alignment: .leading, spacing: 0) {
                tableRow(header, isHeader: true)
                ForEach(rows.indices, id: \.self) { index in
                    tableRow(rows[index], isHeader: false)
                }
            }
            .overlay(
                RoundedRectangle(cornerRadius: 6)
                    .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
            )
        }
    }

    private func tableRow(
        _ row: [[NativeMarkdownInline]],
        isHeader: Bool
    ) -> some View {
        HStack(spacing: 0) {
            ForEach(row.indices, id: \.self) { index in
                inlineText(
                    row[index],
                    font: isHeader ? OpenCodeTypography.bodySecondary.weight(.medium) : OpenCodeTypography.bodySecondary,
                    color: OpenCodeColors.textPrimary
                )
                .frame(minWidth: 112, maxWidth: 220, alignment: .leading)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(isHeader ? OpenCodeColors.surfaceSecondary : OpenCodeColors.surfacePrimary)
                .border(OpenCodeColors.borderSubtle, width: 0.5)
            }
        }
    }

    private var blockSpacing: CGFloat {
        switch variant {
        case .assistantMessage, .outgoingMessage:
            8
        case .standalone:
            12
        }
    }

    private func headingFont(_ level: Int) -> Font {
        switch level {
        case 1:
            OpenCodeTypography.title
        case 2:
            OpenCodeTypography.heading
        default:
            OpenCodeTypography.bodySecondary.weight(.medium)
        }
    }

    private func headingColor(_ level: Int) -> Color {
        switch level {
        case 1:
            OpenCodeColors.markdownH1
        case 2:
            OpenCodeColors.markdownH2
        default:
            OpenCodeColors.markdownH3
        }
    }

    private func diffLineBackground(_ kind: NativeDiffLineKind) -> Color {
        switch kind {
        case .added:
            OpenCodeColors.diffAdd.opacity(0.18)
        case .deleted:
            OpenCodeColors.diffDelete.opacity(0.16)
        case .header:
            OpenCodeColors.syntaxDiffUnknown.opacity(0.14)
        case .context:
            Color.clear
        }
    }
}

enum NativeMarkdownAttributedString {
    static func inline(
        _ inlines: [NativeMarkdownInline],
        font: Font,
        color: Color
    ) -> AttributedString {
        var output = AttributedString()
        append(inlines, into: &output, font: font, color: color)
        return output
    }

    static func code(
        _ code: String,
        spans: [NativeCodeSpan]
    ) -> AttributedString {
        var output = AttributedString(code)
        output.font = OpenCodeTypography.code
        output.foregroundColor = OpenCodeColors.textPrimary

        let sortedSpans = spans.sorted {
            let leftPriority = stylePriority(for: $0.role)
            let rightPriority = stylePriority(for: $1.role)
            if leftPriority != rightPriority { return leftPriority < rightPriority }
            let leftWidth = $0.end - $0.start
            let rightWidth = $1.end - $1.start
            if leftWidth != rightWidth { return leftWidth > rightWidth }
            if $0.start != $1.start { return $0.start < $1.start }
            return $0.end < $1.end
        }

        for span in sortedSpans {
            let start = max(0, min(span.start, code.utf16.count))
            let end = max(start, min(span.end, code.utf16.count))
            guard start < end,
                  let stringStart = stringIndex(in: code, utf16Offset: start),
                  let stringEnd = stringIndex(in: code, utf16Offset: end) else {
                continue
            }

            let startIndex = output.index(
                output.startIndex,
                offsetByCharacters: code.distance(from: code.startIndex, to: stringStart)
            )
            let endIndex = output.index(
                output.startIndex,
                offsetByCharacters: code.distance(from: code.startIndex, to: stringEnd)
            )
            output[startIndex..<endIndex].foregroundColor = color(for: span.role)
        }

        return output
    }

    private static func stringIndex(in value: String, utf16Offset: Int) -> String.Index? {
        let utf16Index = value.utf16.index(value.utf16.startIndex, offsetBy: utf16Offset)
        return String.Index(utf16Index, within: value)
    }

    private static func append(
        _ inlines: [NativeMarkdownInline],
        into output: inout AttributedString,
        font: Font,
        color: Color
    ) {
        for inline in inlines {
            switch inline {
            case .text(let value):
                output.append(styled(value, font: font, color: color))
            case .code(let value):
                output.append(styled(value, font: OpenCodeTypography.code, color: OpenCodeColors.markdownCodeText))
            case .emphasis(let children):
                append(children, into: &output, font: font.italic(), color: OpenCodeColors.markdownEm)
            case .strong(let children):
                append(children, into: &output, font: font.weight(.semibold), color: OpenCodeColors.markdownStrong)
            case .link(let destination, let children):
                var linkText = AttributedString()
                append(children, into: &linkText, font: font, color: OpenCodeColors.markdownLinkText)
                if let url = URL(string: destination) {
                    linkText.link = url
                }
                output.append(linkText)
            case .imageAlt(let alt):
                output.append(styled("[image: \(alt)]", font: font.italic(), color: OpenCodeColors.markdownImage))
            case .softBreak, .hardBreak:
                output.append(styled("\n", font: font, color: color))
            }
        }
    }

    private static func styled(
        _ value: String,
        font: Font,
        color: Color
    ) -> AttributedString {
        var text = AttributedString(value)
        text.font = font
        text.foregroundColor = color
        return text
    }

    private static func styledCode(
        _ value: String,
        color: Color
    ) -> AttributedString {
        styled(value, font: OpenCodeTypography.code, color: color)
    }

    private static func stylePriority(for role: NativeCodeTokenRole) -> Int {
        switch role {
        case .punctuation:
            0
        case .operatorToken:
            1
        case .plain:
            2
        case .variable:
            3
        case .property, .attribute:
            4
        case .function:
            5
        case .number:
            6
        case .constant:
            7
        case .string:
            8
        case .comment:
            9
        case .keyword:
            10
        case .type:
            11
        case .tag:
            12
        case .inserted:
            14
        case .deleted:
            15
        case .warning:
            16
        case .critical:
            17
        }
    }

    private static func color(for role: NativeCodeTokenRole) -> Color {
        switch role {
        case .plain:
            OpenCodeColors.textPrimary
        case .comment:
            OpenCodeColors.syntaxComment
        case .keyword:
            OpenCodeColors.syntaxKeyword
        case .type:
            OpenCodeColors.syntaxType
        case .variable:
            OpenCodeColors.textPrimary
        case .property, .constant:
            OpenCodeColors.syntaxConstant
        case .function:
            OpenCodeColors.syntaxPrimitive
        case .number, .attribute:
            OpenCodeColors.syntaxProperty
        case .string:
            OpenCodeColors.syntaxString
        case .operatorToken, .punctuation:
            OpenCodeColors.textPrimary
        case .tag:
            OpenCodeColors.syntaxInfo
        case .inserted:
            OpenCodeColors.diffAdd
        case .deleted:
            OpenCodeColors.diffDelete
        case .warning:
            OpenCodeColors.syntaxDiffUnknown
        case .critical:
            OpenCodeColors.syntaxCritical
        }
    }
}

struct NativeMarkdownDocument: Decodable {
    let renderTreeVersion: Int
    let sourceHash: Int
    let blocks: [NativeMarkdownBlock]
    let diagnostics: [NativeMarkdownDiagnostic]

    init(
        renderTreeVersion: Int = 1,
        sourceHash: Int,
        blocks: [NativeMarkdownBlock],
        diagnostics: [NativeMarkdownDiagnostic] = []
    ) {
        self.renderTreeVersion = renderTreeVersion
        self.sourceHash = sourceHash
        self.blocks = blocks
        self.diagnostics = diagnostics
    }

    var isLikelyExpandable: Bool {
        let visualLines = blocks.reduce(0) { total, block in
            switch block {
            case .codeBlock(_, let code, _):
                return total + code.components(separatedBy: "\n").count + 1
            case .diffBlock(let lines):
                return total + lines.count + 1
            case .table(_, let rows):
                return total + rows.count + 2
            case .bulletList(let items), .orderedList(_, let items):
                return total + items.count
            case .blockquote, .heading:
                return total + 2
            case .paragraph, .thematicBreak:
                return total + 1
            }
        }
        return visualLines > 6
    }
}

enum NativeMarkdownBlock: Decodable {
    case paragraph([NativeMarkdownInline])
    case heading(level: Int, inlines: [NativeMarkdownInline])
    case blockquote([NativeMarkdownInline])
    case bulletList([[NativeMarkdownInline]])
    case orderedList(start: Int, items: [[NativeMarkdownInline]])
    case codeBlock(language: String?, code: String, spans: [NativeCodeSpan])
    case diffBlock([NativeDiffLine])
    case table(header: [[NativeMarkdownInline]], rows: [[[NativeMarkdownInline]]])
    case thematicBreak

    private enum CodingKeys: String, CodingKey {
        case type
        case inlines
        case level
        case items
        case start
        case language
        case code
        case lines
        case spans
        case header
        case rows
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)

        switch type {
        case "paragraph":
            self = .paragraph(try container.decode([NativeMarkdownInline].self, forKey: .inlines))
        case "heading":
            self = .heading(
                level: try container.decode(Int.self, forKey: .level),
                inlines: try container.decode([NativeMarkdownInline].self, forKey: .inlines)
            )
        case "blockquote":
            self = .blockquote(try container.decode([NativeMarkdownInline].self, forKey: .inlines))
        case "bulletList":
            self = .bulletList(try container.decode([NativeMarkdownListItem].self, forKey: .items).map(\.inlines))
        case "orderedList":
            self = .orderedList(
                start: try container.decode(Int.self, forKey: .start),
                items: try container.decode([NativeMarkdownListItem].self, forKey: .items).map(\.inlines)
            )
        case "codeBlock":
            self = .codeBlock(
                language: try container.decodeIfPresent(String.self, forKey: .language),
                code: try container.decode(String.self, forKey: .code),
                spans: try container.decode([NativeCodeSpan].self, forKey: .spans)
            )
        case "diffBlock":
            self = .diffBlock(try container.decode([NativeDiffLine].self, forKey: .lines))
        case "table":
            let header = try container.decode(NativeMarkdownTableRow.self, forKey: .header)
            let rows = try container.decode([NativeMarkdownTableRow].self, forKey: .rows)
            self = .table(
                header: header.cells.map(\.inlines),
                rows: rows.map { row in row.cells.map(\.inlines) }
            )
        case "thematicBreak":
            self = .thematicBreak
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: container,
                debugDescription: "Unsupported markdown block type: \(type)"
            )
        }
    }
}

struct NativeMarkdownListItem: Decodable {
    let inlines: [NativeMarkdownInline]
}

struct NativeMarkdownTableRow: Decodable {
    let cells: [NativeMarkdownTableCell]
}

struct NativeMarkdownTableCell: Decodable {
    let inlines: [NativeMarkdownInline]
}

enum NativeMarkdownInline: Decodable {
    case text(String)
    case emphasis([NativeMarkdownInline])
    case strong([NativeMarkdownInline])
    case code(String)
    case link(destination: String, children: [NativeMarkdownInline])
    case imageAlt(String)
    case softBreak
    case hardBreak

    private enum CodingKeys: String, CodingKey {
        case type
        case text
        case children
        case code
        case destination
        case alt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)

        switch type {
        case "text":
            self = .text(try container.decode(String.self, forKey: .text))
        case "emphasis":
            self = .emphasis(try container.decode([NativeMarkdownInline].self, forKey: .children))
        case "strong":
            self = .strong(try container.decode([NativeMarkdownInline].self, forKey: .children))
        case "code":
            self = .code(try container.decode(String.self, forKey: .code))
        case "link":
            self = .link(
                destination: try container.decode(String.self, forKey: .destination),
                children: try container.decode([NativeMarkdownInline].self, forKey: .children)
            )
        case "imageAlt":
            self = .imageAlt(try container.decode(String.self, forKey: .alt))
        case "softBreak":
            self = .softBreak
        case "hardBreak":
            self = .hardBreak
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: container,
                debugDescription: "Unsupported markdown inline type: \(type)"
            )
        }
    }
}

struct NativeCodeSpan: Decodable {
    let start: Int
    let end: Int
    let role: NativeCodeTokenRole
}

enum NativeCodeTokenRole: String, Decodable {
    case plain = "Plain"
    case comment = "Comment"
    case keyword = "Keyword"
    case string = "String"
    case number = "Number"
    case type = "Type"
    case function = "Function"
    case property = "Property"
    case variable = "Variable"
    case constant = "Constant"
    case operatorToken = "Operator"
    case punctuation = "Punctuation"
    case tag = "Tag"
    case attribute = "Attribute"
    case inserted = "Inserted"
    case deleted = "Deleted"
    case warning = "Warning"
    case critical = "Critical"
}

struct NativeDiffLine: Decodable {
    let kind: NativeDiffLineKind
    let text: String
    let spans: [NativeCodeSpan]
}

enum NativeDiffLineKind: String, Decodable {
    case context = "Context"
    case added = "Added"
    case deleted = "Deleted"
    case header = "Header"
}

struct NativeMarkdownDiagnostic: Decodable {
    let kind: String
    let message: String
}

private struct RenderedMarkdownSizePreferenceKey: PreferenceKey {
    static var defaultValue: CGSize = .zero

    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        value = nextValue()
    }
}

private extension View {
    func readRenderedMarkdownSize(onChange: @escaping (CGSize) -> Void) -> some View {
        background(
            GeometryReader { geometry in
                Color.clear.preference(key: RenderedMarkdownSizePreferenceKey.self, value: geometry.size)
            }
        )
        .onPreferenceChange(RenderedMarkdownSizePreferenceKey.self, perform: onChange)
    }
}

#Preview("Rendered Markdown") {
    OpenCodeRenderedMarkdown(
        document: RenderedMarkdownGalleryMocks.samples[0].document,
        variant: .standalone
    )
    .padding()
    .openCodeTheme()
}
