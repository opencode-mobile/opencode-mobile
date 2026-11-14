import Foundation
import Testing
@testable import Hex_Code

/// The summary's inline formatting (app plan, "Formatting").
struct InlineMarkdownTests {
    private func run(_ text: String, _ style: InlineRun.Style = .plain) -> InlineRun {
        InlineRun(text: text, style: style)
    }

    @Test func backticksAreCode() {
        #expect(InlineMarkdown.runs("Run `npm test` now") == [run("Run "), run("npm test", .code), run(" now")])
    }

    @Test func nothingInsideBackticksIsMarkdown() {
        #expect(InlineMarkdown.runs("`**x** [a](b)`") == [run("**x** [a](b)", .code)])
    }

    @Test func anUnpairedBacktickShowsAsWritten() {
        #expect(InlineMarkdown.runs("Run `npm test") == [run("Run `npm test")])
        #expect(InlineMarkdown.runs("`a` and `b") == [run("a", .code), run(" and `b")])
    }

    @Test func doubleStarsAreBold() {
        #expect(InlineMarkdown.runs("An **expired session** renews") == [run("An "), run("expired session", .bold), run(" renews")])
    }

    @Test func unpairedOrEmptyStarsShowAsWritten() {
        #expect(InlineMarkdown.runs("2 ** 3") == [run("2 ** 3")])
        #expect(InlineMarkdown.runs("a **** b") == [run("a **** b")])
    }

    @Test func linksAndImagesKeepTheirLabel() {
        #expect(InlineMarkdown.plain("Read the [refresh docs](https://example.com/a) first") == "Read the refresh docs first")
        #expect(InlineMarkdown.plain("See ![the chart](chart.png)") == "See the chart")
    }

    @Test func aLeadingHashOrQuoteGoes() {
        #expect(InlineMarkdown.plain("# Login") == "Login")
        #expect(InlineMarkdown.plain("### Login") == "Login")
        #expect(InlineMarkdown.plain("> Waiting") == "Waiting")
        #expect(InlineMarkdown.plain("Issue #42 > 3") == "Issue #42 > 3")
    }

    @Test func plainCountsShownCharacters() {
        #expect(InlineMarkdown.plain("`AuthClient` is **done**").count == "AuthClient is done".count)
    }

    @Test func clipCountsShownCharactersAndKeepsFormatting() {
        let short = "Use `AuthClient` **now**"
        #expect(InlineMarkdown.clip(short, 18) == short) // 18 shown, 24 written
        let clipped = InlineMarkdown.clip("Use `AuthClient` for the whole refresh flow", 20)
        #expect(clipped == "Use `AuthClient` for…")
        #expect(InlineMarkdown.plain(clipped).count <= 20)
    }

    @Test func clipInsideCodeClosesTheCode() {
        let clipped = InlineMarkdown.clip("`averyveryverylongidentifiername` x", 10)
        #expect(InlineMarkdown.runs(clipped) == [run("averyvery", .code), run("…")])
    }

    @Test func serialisedReadsBackTheSame() {
        let runs = InlineMarkdown.runs("# A `b` **c** [d](e)")
        #expect(InlineMarkdown.runs(InlineMarkdown.serialised(runs)) == runs)
    }

    @Test func nowSizeCountsShownCharacters() {
        let code = "`" + String(repeating: "a", count: 58) + "`" // 60 written, 58 shown
        #expect(NowSize.forText(code) == NowSize.forLength(58))
    }
}
