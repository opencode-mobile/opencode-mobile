import Foundation
import OpenCodeMarkdownCore
import Testing
@testable import OpenCode_Mobile

/// The markdown a cut user message renders (#1043): markers its cut line leaves open are closed
/// before the "…". Same vectors as Android `MessageTextCutMarkdownTest`.
@Suite struct MessageTextCutMarkdownTests {
    /// A shown prefix, what the parser is given for it, and how that renders with the "…".
    struct Vector: Sendable, CustomTestStringConvertible {
        let prefix: String
        let repaired: String
        let rendered: String

        init(_ prefix: String, _ repaired: String, _ rendered: String) {
            self.prefix = prefix
            self.repaired = repaired
            self.rendered = rendered
        }

        var testDescription: String { prefix }
    }

    static let vectors: [Vector] = [
        Vector("Plain and **bold wor", "Plain and **bold wor**", "Plain and <b>bold wor</b>…"),
        Vector("Plain and *italic wor", "Plain and *italic wor*", "Plain and <i>italic wor</i>…"),
        Vector("Plain and _italic wor", "Plain and _italic wor_", "Plain and <i>italic wor</i>…"),
        // The parser has no strikethrough: the whole message shows its tildes too.
        Vector("Plain and ~~struck wor", "Plain and ~~struck wor", "Plain and ~~struck wor…"),
        Vector("Run `git log --one", "Run `git log --one`", "Run <code>git log --one</code>…"),
        Vector("See [the docs", "See the docs", "See the docs…"),
        Vector("See [the docs](https://example.com/pa", "See the docs", "See the docs…"),
        Vector("Look ![a diag", "Look ![a diag]()", "Look <img a diag>…"),
        Vector(
            "Look ![a diagram](https://example.com/a.p",
            "Look ![a diagram](https://example.com/a.p)",
            "Look <img a diagram>…"
        ),
        Vector("_italic and **bold wor", "_italic and **bold wor**_", "<i>italic and <b>bold wor</b></i>…"),
        // `**bold *it***` would close the bold early, so the inner opener is dropped.
        Vector("**bold and *it", "**bold and it**", "<b>bold and it</b>…"),
        Vector("Some **bold** and more wor", "Some **bold** and more wor", "Some <b>bold</b> and more wor…"),
        Vector(
            "See [docs](https://example.com) and **mo",
            "See [docs](https://example.com) and **mo**",
            "See <a https://example.com>docs</a> and <b>mo</b>…"
        ),
        Vector("Price \\*5 and more", "Price \\*5 and more", "Price \\*5 and more…"),
        Vector("Run `a **b` and more wor", "Run `a **b` and more wor", "Run <code>a **b</code> and more wor…"),
        Vector("call some_function and mo", "call some_function and mo", "call some_function and mo…"),
        Vector("Some text **", "Some text", "Some text…"),
        Vector("- one\n- item with **bold wor", "- one\n- item with **bold wor**", "- one\n- item with <b>bold wor</b>…"),
        Vector("## Title with *emph", "## Title with *emph*", "## Title with <i>emph</i>…"),
        Vector(
            "Look:\n\n```\nlet **x = 1\nlet *y",
            "Look:\n\n```\nlet **x = 1\nlet *y",
            "Look:\n```let **x = 1\nlet *y…```"
        ),
    ]

    @Test(arguments: vectors)
    func cutLine_closesTheMarkersItLeftOpen(vector: Vector) {
        let repaired = OpenCodeMarkdownParser.shared.closeMarkersLeftOpen(prefix: vector.prefix)
        #expect(repaired == vector.repaired)
        #expect(Self.render(repaired + "…") == vector.rendered)
    }

    @Test func cutInsideBold_rendersBoldToTheCut() throws {
        let text = (1 ... 20).map { "line \($0)" }.joined(separator: "\n") + "\nStart of the long line **"
            + String(repeating: "abcdef ", count: 90) + "** end"
        let cut = try #require(MessageTextLimit.cut(text))
        let message = Message(id: "m", role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: 1)

        #expect(cut.endsMidLine)
        #expect(!cut.visibleText.hasSuffix("**…"))
        #expect(message.markdownSourceText == String(cut.visibleText.dropLast()) + "**…")
        let document = OpenCodeRenderedMarkdownParser.parse(message.markdownSourceText, structureOnly: true)
        guard document.blocks.count == 1, case let .paragraph(inlines) = try #require(document.blocks.first) else {
            Issue.record("expected one paragraph, got \(document.blocks)")
            return
        }
        #expect(inlines.contains { if case .strong = $0 { true } else { false } })
        guard case let .text(last) = try #require(inlines.last) else {
            Issue.record("expected trailing text, got \(inlines)")
            return
        }
        #expect(last == "…")
    }

    @Test func cutAtALineBreak_isGivenToTheParserAsShown() throws {
        let text = (1 ... 40).map { "line **\($0)" }.joined(separator: "\n")
        let cut = try #require(MessageTextLimit.cut(text))

        #expect(!cut.endsMidLine)
        #expect(cut.markdownSource == cut.visibleText)
    }

    @Test func assistantMessage_isParsedWhole() {
        let text = "Some **bold " + String(repeating: "abcdef ", count: 400)
        let message = Message(id: "m", role: .assistant, text: text, model: nil, mode: nil, sentAtEpochMs: 1)
        #expect(message.markdownSourceText == text)
    }

    /// The parsed blocks of `markdown` in a compact notation both platforms' tests share.
    private static func render(_ markdown: String) -> String {
        OpenCodeRenderedMarkdownParser.parse(markdown, structureOnly: true).blocks.map { block in
            switch block {
            case let .paragraph(inlines):
                render(inlines)
            case let .heading(level, inlines):
                String(repeating: "#", count: level) + " " + render(inlines)
            case let .bulletList(items):
                items.map { "- " + render($0) }.joined(separator: "\n")
            case let .codeBlock(_, code, _):
                "```" + code + "```"
            default:
                "\(block)"
            }
        }.joined(separator: "\n")
    }

    private static func render(_ inlines: [NativeMarkdownInline]) -> String {
        inlines.map { inline in
            switch inline {
            case let .text(text): text
            case let .strong(children): "<b>" + render(children) + "</b>"
            case let .emphasis(children): "<i>" + render(children) + "</i>"
            case let .code(code): "<code>" + code + "</code>"
            case let .link(destination, children): "<a " + destination + ">" + render(children) + "</a>"
            case let .imageAlt(alt): "<img " + alt + ">"
            case .softBreak, .hardBreak: "\n"
            }
        }.joined()
    }
}
