import Foundation
import Testing
@testable import Hex_Code

/// The in-app browser (app plan, "The in-app browser"; "Testing" › Browser).
struct BrowserTests {
    @Test func aLocalhostLinkKeepsItsPortPathQueryAndFragmentAndCarriesNoCredentials() throws {
        let link = CardLink(label: "Login preview", url: "http://localhost:5173/login/form?next=%2Fhome&x=1#top")
        let destination = try #require(CardLinks.destination(for: link, computerHost: "192.168.0.47"))
        #expect(destination.inApp)
        #expect(destination.url.absoluteString == "http://192.168.0.47:5173/login/form?next=%2Fhome&x=1#top")
        #expect(destination.url.user == nil && destination.url.password == nil)
        #expect(destination.shownHost == "192.168.0.47:5173")
        #expect(destination.label == "Login preview")
    }

    @Test func zeroAndLoopbackOpenInAppToo() {
        for host in ["127.0.0.1", "0.0.0.0", "LOCALHOST"] {
            let destination = CardLinks.destination(for: CardLink(label: "x", url: "http://\(host):3000/"), computerHost: "100.76.89.86")
            #expect(destination?.inApp == true)
            #expect(destination?.url.host == "100.76.89.86")
        }
    }

    @Test func anExternalLinkOpensTheSystemBrowser() {
        let destination = CardLinks.destination(for: CardLink(label: "PR", url: "https://github.com/o/r/pull/1"), computerHost: "192.168.0.47")
        #expect(destination?.inApp == false)
    }

    private let home = URL(string: "http://192.168.0.47:5173/")!

    @Test func theSameOriginStaysInTheBrowser() {
        #expect(BrowserPolicy.decide(URL(string: "http://192.168.0.47:5173/settings?a=1")!, home: home, mainFrame: true) == .allow)
        #expect(BrowserPolicy.decide(URL(string: "about:blank")!, home: home, mainFrame: true) == .allow)
    }

    @Test func anotherOriginOpensTheSystemBrowser() {
        #expect(BrowserPolicy.decide(URL(string: "https://vitejs.dev/guide")!, home: home, mainFrame: true) == .system)
        // Another port on the computer is another origin.
        #expect(BrowserPolicy.decide(URL(string: "http://192.168.0.47:4196/")!, home: home, mainFrame: true) == .system)
        // A frame inside the page may come from anywhere.
        #expect(BrowserPolicy.decide(URL(string: "https://www.youtube.com/embed/x")!, home: home, mainFrame: false) == .allow)
    }

    @Test func otherSchemesGoToTheSystem() {
        #expect(BrowserPolicy.decide(URL(string: "mailto:a@example.com")!, home: home, mainFrame: true) == .system)
        #expect(BrowserPolicy.decide(URL(string: "tel:+4930123")!, home: home, mainFrame: true) == .system)
        #expect(BrowserPolicy.decide(URL(string: "itms-apps://apps.apple.com")!, home: home, mainFrame: true) == .system)
    }

    @Test func aRefusedConnectionShowsTheDevServerError() throws {
        let error = NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotConnectToHost)
        let failure = try #require(BrowserFailure.of(error, url: home, name: "Rapid Bison"))
        #expect(failure.title == "Couldn’t reach port 5173 on Rapid Bison")
        #expect(failure.detail.hasPrefix("The dev server may only listen on the computer itself."))
        #expect(failure.button == "Try again")
    }

    @Test func aTimeoutSaysTheComputerCantBeReached() throws {
        let error = NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut)
        let failure = try #require(BrowserFailure.of(error, url: home, name: "Rapid Bison"))
        #expect(failure.title == "Couldn’t reach Rapid Bison")
    }

    @Test func aCancelledLoadIsntAnError() {
        #expect(BrowserFailure.of(NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled), url: home, name: "x") == nil)
        #expect(BrowserFailure.of(NSError(domain: "WebKitErrorDomain", code: 102), url: home, name: "x") == nil)
    }
}
