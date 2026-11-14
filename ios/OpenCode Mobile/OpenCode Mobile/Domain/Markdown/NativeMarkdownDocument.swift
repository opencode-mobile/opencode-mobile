import Foundation
import OpenCodeMarkdownCore

// Rendered Markdown document model: the data the shared KMP parser returns. The SwiftUI
// rendering lives in Components/Chat/OpenCodeRenderedMarkdown.swift.

enum OpenCodeRenderedMarkdownVariant: String, CaseIterable, Sendable {
    case assistantMessage
    case outgoingMessage
    case standalone
}

enum OpenCodeRenderedMarkdownDetector: Sendable {
    nonisolated static func shouldRender(_ text: String) -> Bool {
        OpenCodeMarkdownDetector.shared.shouldRender(text: text)
    }
}

nonisolated struct NativeMarkdownDocument: Decodable, Sendable {
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

nonisolated enum NativeMarkdownBlock: Decodable, Sendable {
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

struct NativeMarkdownListItem: Decodable, Sendable {
    let inlines: [NativeMarkdownInline]
}

struct NativeMarkdownTableRow: Decodable, Sendable {
    let cells: [NativeMarkdownTableCell]
}

struct NativeMarkdownTableCell: Decodable, Sendable {
    let inlines: [NativeMarkdownInline]
}

nonisolated enum NativeMarkdownInline: Decodable, Sendable {
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

nonisolated struct NativeCodeSpan: Decodable, Hashable, Sendable {
    let start: Int
    let end: Int
    let role: NativeCodeTokenRole
}

nonisolated enum NativeCodeTokenRole: String, CaseIterable, Decodable, Hashable, Sendable {
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

nonisolated struct NativeDiffLine: Decodable, Sendable {
    let kind: NativeDiffLineKind
    let text: String
    let spans: [NativeCodeSpan]
}

nonisolated enum NativeDiffLineKind: String, Decodable, Sendable {
    case context = "Context"
    case added = "Added"
    case deleted = "Deleted"
    case header = "Header"
}

nonisolated struct NativeMarkdownDiagnostic: Decodable, Sendable {
    let kind: String
    let message: String
}
