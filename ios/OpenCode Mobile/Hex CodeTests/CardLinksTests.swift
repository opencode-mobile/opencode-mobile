import Foundation
import Testing
@testable import Hex_Code

/// The card's link tiles: where links come from and how they open (app plan, "Links").
struct CardLinksTests {
    private func card(_ reply: String) -> StatusCard? {
        guard case let .card(card) = StatusSummary.parse(reply) else { return nil }
        return card
    }

    @Test func linkLinesGiveLabelAndURL() {
        #expect(CardLinks.fromLinkLine("Login preview | http://localhost:5173/login") == CardLink(label: "Login preview", url: "http://localhost:5173/login"))
    }

    @Test func aLinkLineWithOnlyAURLUsesTheHost() {
        #expect(CardLinks.fromLinkLine("https://www.github.com/sst/opencode") == CardLink(label: "github.com", url: "https://www.github.com/sst/opencode"))
    }

    @Test func linkLinesNeedAWebURL() {
        #expect(CardLinks.fromLinkLine("Notes | file:///tmp/notes.md") == nil)
        #expect(CardLinks.fromLinkLine("Just words") == nil)
    }

    @Test func theHostDropsWwwAndKeepsThePort() {
        #expect(CardLinks.host(of: "https://www.example.com/a") == "example.com")
        #expect(CardLinks.host(of: "http://localhost:5173/login") == "localhost:5173")
    }

    @Test func onlyTheFirstTwoWebLinkLinesCount() {
        let card = card("""
        ## Title
        Now: Working.
        Link: Notes | file:///tmp/notes.md
        Link: A | https://a.example
        Link: B | https://b.example
        Link: C | https://c.example
        """)
        #expect(card?.links.map(\.label) == ["A", "B"])
        #expect(card?.now == "Working.")
    }

    @Test func linksInASentenceStayAsTheirLabelOrHost() {
        let pulled = CardLinks.pull(from: "Read the [refresh docs](https://example.com/docs), then open http://localhost:5173/login.")
        #expect(pulled.text == "Read the refresh docs, then open localhost:5173.")
        #expect(pulled.links == [
            CardLink(label: "refresh docs", url: "https://example.com/docs"),
            CardLink(label: "localhost:5173", url: "http://localhost:5173/login"),
        ])
    }

    @Test func linksComeFromLinkLinesThenNowThenNext() {
        let card = card("""
        ## Title
        Now: See https://now.example/a and https://shared.example.
        Next: Read [the docs](https://next.example/b) and https://shared.example
        Link: Line | https://line.example
        """)
        #expect(card?.links.map(\.url) == ["https://line.example", "https://now.example/a", "https://shared.example", "https://next.example/b"])
        #expect(card?.next == "Read the docs and shared.example")
    }

    @Test func repeatedURLsShowOnce() {
        let links = [CardLink(label: "A", url: "https://a.example"), CardLink(label: "again", url: "https://a.example"), CardLink(label: "B", url: "https://b.example")]
        #expect(CardLinks.unique(links) == [links[0], links[2]])
    }

    @Test func aReplyWithoutLinksHasNone() {
        #expect(card("## Title\nNow: Working.")?.links == [])
    }

    @Test func aLocalLinkOpensOnTheComputerInApp() {
        for host in ["localhost", "127.0.0.1", "0.0.0.0"] {
            let destination = CardLinks.destination(for: CardLink(label: "App", url: "http://\(host):5173/login?next=1"), computerHost: "192.168.1.20")
            #expect(destination == .init(url: URL(string: "http://192.168.1.20:5173/login?next=1")!, shownHost: "192.168.1.20:5173", inApp: true, label: "App"))
        }
    }

    @Test func aWebLinkOpensInTheSystemBrowser() {
        let destination = CardLinks.destination(for: CardLink(label: "Docs", url: "https://www.example.com/docs"), computerHost: "192.168.1.20")
        #expect(destination == .init(url: URL(string: "https://www.example.com/docs")!, shownHost: "example.com", inApp: false, label: "Docs"))
    }

    @Test func savedCardsWithoutLinksStillDecode() throws {
        let old = #"{"title":"T","now":"N","steps":[]}"#
        let card = try JSONDecoder().decode(StatusCard.self, from: Data(old.utf8))
        #expect(card.links == [])
    }

    @Test func savedCardsKeepTheirLinks() throws {
        let card = StatusCard(title: "T", now: "N", next: nil, links: [CardLink(label: "A", url: "https://a.example")])
        let decoded = try JSONDecoder().decode(StatusCard.self, from: JSONEncoder().encode(card))
        #expect(decoded == card)
    }
}
