import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

/// Artifacts (app plan, "Artifacts (M8)"; artifacts-proposal.md §2.7, artifacts-enable-spike.md §3):
/// what an answer means, which file wins, the before and after check, the card's artifact, links,
/// the setup session's events, the prompts, and the browser's mapping.
struct ArtifactsTests {
    // MARK: Detection and drift

    private func json(_ text: String) -> Data { Data(text.utf8) }

    private let listBody = #"{"location":{"directory":"/p"},"data":[{"id":"art_1","provider":"local","sessionID":"ses_a","title":"Login preview","entry":"index.html","url":"/api/experimental/artifacts/art_1/view/","time":{"created":1,"updated":2}}]}"#

    @Test func aListThatDecodesIsOn() {
        let answer = ArtifactsAnswer.read(status: 200, body: json(listBody))
        #expect(answer == .on([ArtifactInfo(id: "art_1", sessionID: "ses_a", title: "Login preview", updated: 2)]))
    }

    @Test func forbiddenIsOffWhateverTheWording() {
        #expect(ArtifactsAnswer.read(status: 403, body: json(#"{"_tag":"ForbiddenError","message":"anything"}"#)) == .off)
        #expect(ArtifactsAnswer.read(status: 403, body: Data()) == .off)
    }

    @Test func aChangedRouteOrShapeIsChanged() {
        #expect(ArtifactsAnswer.read(status: 404, body: Data()) == .changed)
        #expect(ArtifactsAnswer.read(status: 405, body: Data()) == .changed)
        #expect(ArtifactsAnswer.read(status: 400, body: json(#"{"_tag":"InvalidRequestError"}"#)) == .changed)
        #expect(ArtifactsAnswer.read(status: 200, body: json(#"{"items":[]}"#)) == .changed)
        // An item without its time doesn't decode either.
        #expect(ArtifactsAnswer.read(status: 200, body: json(#"{"data":[{"id":"art_1","provider":"local","title":"x","entry":"i","url":"/u"}]}"#)) == .changed)
    }

    @Test func signedOutAndUnknownKeepTheirOwnMeaning() {
        #expect(ArtifactsAnswer.read(status: 401, body: Data()) == .signedOut)
        #expect(ArtifactsAnswer.read(status: 500, body: Data()) == .unknown)
        #expect(ArtifactsAnswer.read(status: 503, body: Data()) == .unknown)
        #expect(ArtifactsAnswer.read(status: nil, body: Data()) == .unknown)
        // A known error with a tag (an unknown location) isn't a changed route.
        #expect(ArtifactsAnswer.read(status: 404, body: json(#"{"_tag":"LocationNotFoundError"}"#)) == .unknown)
    }

    static func contract(view: Bool = true, updated: Bool = true) -> [String: Any] {
        var paths: [String: Any] = [
            "/api/experimental/artifacts": ["get": ["responses": ["200": ["content": ["application/json": ["schema": [
                "type": "object",
                "properties": ["location": ["type": "object"], "data": ["type": "array", "items": ["$ref": "#/components/schemas/Artifact.Info"]]],
            ]]]]]]],
        ]
        if view { paths["/api/experimental/artifacts/{artifactID}/view/*"] = ["get": [:]] }
        return [
            "paths": paths,
            "components": ["schemas": [
                "Artifact.Info": ["type": "object", "properties": [
                    "id": ["type": "string"], "title": ["type": "string"], "url": ["type": "string"],
                    "time": ["$ref": "#/components/schemas/Artifact.Time"],
                ]],
                "Artifact.Time": ["type": "object", "properties": updated ? ["created": [:], "updated": [:]] : ["created": [:]]],
            ]],
        ]
    }

    @Test func theRoutesAndShapeAreDeclared() {
        #expect(ArtifactsContract.declared(Self.contract()))
        #expect(!ArtifactsContract.declared(Self.contract(view: false)))
        #expect(!ArtifactsContract.declared(Self.contract(updated: false)))
        #expect(!ArtifactsContract.declared([:]))
    }

    // MARK: The file that wins

    static let globalFolder = "/Users/example/.config/opencode"
    static let globalFile = globalFolder + "/opencode.json"

    static func config(_ documents: [(String?, String)]) -> OpenCodeConfig {
        let entries = documents.map { path, info in
            path.map { #"{"type":"document","path":"\#($0)","info":\#(info)}"# } ?? #"{"type":"document","info":\#(info)}"#
        } + [#"{"type":"directory","path":"\#(globalFolder)"}"#]
        return OpenCodeConfig.read(Data(("[" + entries.joined(separator: ",") + "]").utf8))!
    }

    @Test func theConfigReadsItsDocumentsAndTheGlobalFolder() {
        let config = Self.config([(Self.globalFile, #"{"model":{"providerID":"opencode","model":"beam-free"}}"#)])
        #expect(config.documents.count == 1)
        #expect(config.globalFolder == Self.globalFolder)
        #expect(config.documents[0].info["model"]?.object?["model"] == .string("beam-free"))
    }

    @Test func withNoExperimentalBlockTheGlobalFileIsNamed() {
        guard case let .file(target) = ConfigTarget.choose(Self.config([])) else { Issue.record("no file"); return }
        #expect(target.path == Self.globalFile)
        #expect(target.isGlobal)
        #expect(!target.hasExperimental)
        #expect(!target.hasDocument)
        #expect(target.line == #""experimental": { "artifacts": true }"#)
    }

    @Test func theGlobalJsoncIsNamedWhenOpenCodeReadsOne() {
        let jsonc = Self.globalFolder + "/opencode.jsonc"
        guard case let .file(target) = ConfigTarget.choose(Self.config([(jsonc, #"{"model":"x"}"#)])) else { Issue.record("no file"); return }
        #expect(target.path == jsonc)
        #expect(target.hasDocument)
    }

    @Test func theHighestDocumentWithAnExperimentalBlockWins() {
        let project = "/Users/example/acme/opencode.json"
        let config = Self.config([
            (Self.globalFile, #"{"experimental":{"todo":true}}"#),
            (project, #"{"experimental":{"todo":true}}"#),
            ("/Users/example/acme/.opencode/opencode.json", #"{"model":"x"}"#),
        ])
        guard case let .file(target) = ConfigTarget.choose(config) else { Issue.record("no file"); return }
        #expect(target.path == project)
        #expect(!target.isGlobal)
        #expect(target.hasExperimental)
        #expect(target.line == #""artifacts": true"#)
    }

    @Test func theGlobalFileWithItsOwnBlockIsGlobal() {
        guard case let .file(target) = ConfigTarget.choose(Self.config([(Self.globalFile, #"{"experimental":{"artifacts":false}}"#)])) else { Issue.record("no file"); return }
        #expect(target.isGlobal && target.hasExperimental)
    }

    @Test func theEnvironmentAndAnUnreadableConfigAreNoFile() {
        #expect(ConfigTarget.choose(Self.config([(nil, #"{"experimental":{"artifacts":true}}"#)])) == .environment)
        #expect(ConfigTarget.choose(nil) == .unreadable)
    }

    // MARK: Before and after

    @Test func addingTheFlagKeepsEverythingElse() {
        let before = Self.config([(Self.globalFile, #"{"model":"x","experimental":{"todo":true},"update":"disable"}"#)])
        let after = Self.config([(Self.globalFile, #"{"model":"x","experimental":{"todo":true,"artifacts":true},"update":"disable"}"#)])
        #expect(ConfigCheck.compare(before: before, after: after, target: Self.globalFile, fileExists: true) == .intact)
    }

    @Test func aDroppedSiblingKeyOrSettingIsNamed() {
        let before = Self.config([(Self.globalFile, #"{"model":"x","providers":{"a":{}},"experimental":{"todo":true}}"#)])
        let after = Self.config([(Self.globalFile, #"{"model":"x","experimental":{"artifacts":true}}"#)])
        #expect(ConfigCheck.compare(before: before, after: after, target: Self.globalFile, fileExists: true) == .changed(["providers", "experimental.todo"]))
    }

    @Test func aFileThatNoLongerParsesIsBroken() {
        let before = Self.config([(Self.globalFile, #"{"model":"x"}"#)])
        let after = Self.config([])
        #expect(ConfigCheck.compare(before: before, after: after, target: Self.globalFile, fileExists: true) == .broken)
        #expect(ConfigCheck.compare(before: before, after: after, target: Self.globalFile, fileExists: nil) == .broken)
        // A new file that is listed but has no document is broken too.
        #expect(ConfigCheck.compare(before: Self.config([]), after: Self.config([]), target: Self.globalFile, fileExists: true) == .broken)
    }

    @Test func aNewFileWithTheFlagIsIntact() {
        let after = Self.config([(Self.globalFile, #"{"experimental":{"artifacts":true}}"#)])
        #expect(ConfigCheck.compare(before: Self.config([]), after: after, target: Self.globalFile, fileExists: true) == .intact)
    }

    @Test func anotherDocumentGoneIsNamed() {
        let project = "/Users/example/acme/opencode.json"
        let before = Self.config([(Self.globalFile, #"{"model":"x"}"#), (project, #"{"experimental":{}}"#)])
        let after = Self.config([(project, #"{"experimental":{"artifacts":true}}"#)])
        #expect(ConfigCheck.compare(before: before, after: after, target: project, fileExists: true) == .changed(["opencode.json"]))
    }

    // MARK: The card's artifact

    private func artifact(_ id: String, session: String?, updated: Int64, provider: String = "local", url: String? = nil) -> ArtifactInfo {
        ArtifactInfo(id: id, provider: provider, sessionID: session, title: "T \(id)", url: url, updated: updated)
    }

    @Test func theNewestOfTheLatestRunShows() {
        let list = [artifact("art_2", session: "ses_a", updated: 200), artifact("art_3", session: "ses_a", updated: 300), artifact("art_1", session: "ses_a", updated: 50)]
        #expect(ArtifactPick.newest(list, sessions: ["ses_a"], since: 100) == CardArtifact(id: "art_3", title: "T art_3"))
    }

    @Test func oneFromAnEarlierRunDoesNot() {
        #expect(ArtifactPick.newest([artifact("art_1", session: "ses_a", updated: 50)], sessions: ["ses_a"], since: 100) == nil)
        #expect(ArtifactPick.newest([artifact("art_1", session: "ses_a", updated: 50)], sessions: ["ses_a"], since: nil) == nil)
    }

    @Test func aSubagentsShowsAndAnotherSessionsDoesNot() {
        #expect(ArtifactPick.newest([artifact("art_1", session: "ses_child", updated: 200)], sessions: ["ses_a", "ses_child"], since: 100)?.id == "art_1")
        #expect(ArtifactPick.newest([artifact("art_1", session: "ses_other", updated: 200)], sessions: ["ses_a"], since: 100) == nil)
    }

    @Test func anotherProviderOrNoSessionMakesNoTile() {
        #expect(ArtifactPick.newest([artifact("art_1", session: "ses_a", updated: 200, provider: "gist")], sessions: ["ses_a"], since: 100) == nil)
        #expect(ArtifactPick.newest([artifact("art_1", session: "ses_a", updated: 200, url: "https://example.com/a")], sessions: ["ses_a"], since: 100) == nil)
        #expect(ArtifactPick.newest([artifact("art_1", session: nil, updated: 200)], sessions: ["ses_a"], since: 100) == nil)
    }

    @Test func theArtifactIsSavedWithTheCard() throws {
        var card = StatusCard(title: "Login", now: "Published it.")
        card.artifact = CardArtifact(id: "art_1", title: "Login preview")
        let data = try JSONEncoder().encode(StatusSummary.card(card))
        #expect(try JSONDecoder().decode(StatusSummary.self, from: data) == .card(card))
        // Cards saved before artifacts read without one.
        let old = try JSONDecoder().decode(StatusCard.self, from: Data(#"{"title":"x","now":"y"}"#.utf8))
        #expect(old.artifact == nil)
    }

    // MARK: Links

    @Test func anArtifactLinkMakesNoTileWhateverTheHost() {
        let reply = """
        ## Login
        Now: Published the preview at http://localhost:3000/api/experimental/artifacts/art_9/view/ for you.
        Next: Commit it
        Link: Preview | http://localhost:3000/api/experimental/artifacts/art_1/view/
        Link: Docs | https://example.com/docs
        Link: PR | https://example.com/pr/1
        """
        guard case let .card(card) = StatusSummary.read(reply).summary else { Issue.record("no card"); return }
        #expect(card.links.map(\.url) == ["https://example.com/docs", "https://example.com/pr/1"])
    }

    @Test func artifactPathsAreRecognised() {
        #expect(ArtifactLinks.isArtifact("/api/experimental/artifacts/art_1/view/"))
        #expect(ArtifactLinks.isArtifact("https://example.com/api/experimental/artifacts/art_1/view/index.html"))
        #expect(!ArtifactLinks.isArtifact("https://example.com/api/experimental/other"))
    }

    // MARK: The setup session

    @Test func theRepliesReadDoneAndFailed() {
        #expect(SetupReply.read("DONE") == .done)
        #expect(SetupReply.read("  DONE.\n") == .done)
        #expect(SetupReply.read("FAILED: the file isn't valid JSON") == .failed("the file isn't valid JSON"))
        #expect(SetupReply.read("I added it; you may need a restart") == .other("I added it; you may need a restart"))
    }

    @Test func onlyTheSetupSessionsEventsCount() throws {
        let asked = #"{"type":"permission.asked","data":{"id":"per_1","sessionID":"ses_setup","action":"edit","resources":["/c/opencode.json"],"save":["*"],"metadata":{"files":[{"file":"/c/opencode.json","patch":"@@\n-a\n+b\n"}]}}}"#
        guard case let .approvalAsked(item)? = SetupEvent.decode(asked, sessionID: "ses_setup") else { Issue.record("not an approval"); return }
        #expect(item.id == "per_1")
        #expect(item.approval?.files.first?.file == "/c/opencode.json")
        #expect(SetupEvent.decode(asked, sessionID: "ses_today") == nil)
        let tool = #"{"type":"session.tool.input.started","data":{"sessionID":"ses_setup","assistantMessageID":"msg_1","id":"c1","name":"edit"}}"#
        #expect(SetupEvent.decode(tool, sessionID: "ses_setup") == .toolStarted(name: "edit"))
        let ended = #"{"type":"session.execution.succeeded","data":{"sessionID":"ses_setup"}}"#
        #expect(SetupEvent.decode(ended, sessionID: "ses_setup") == .runSucceeded)
    }

    @Test func theFreeLimitIsReadFromTheRunsError() {
        let failed = #"{"type":"session.execution.failed","data":{"sessionID":"ses_setup","error":{"type":"provider.free-limit","message":"Free usage limit reached","status":429}}}"#
        guard case let .runFailed(error)? = SetupEvent.decode(failed, sessionID: "ses_setup") else { Issue.record("not a failure"); return }
        #expect(error.isFreeLimit)
        #expect(error.message == "Free usage limit reached")
        #expect(!SetupRunError(type: "provider.auth", message: "x", status: 401).isFreeLimit)
    }

    @Test func theTranscriptReadsOldestFirst() {
        let body = #"{"data":[{"id":"msg_3","type":"idle","outcome":"failed","error":{"type":"x","message":"Free usage limit reached"},"time":{"created":3}},{"id":"msg_2","type":"assistant","content":[{"type":"tool","name":"edit","state":{"status":"completed","input":{"filePath":"/c/opencode.json"}}},{"type":"text","text":"DONE"}],"time":{"created":2}},{"id":"msg_1","type":"user","text":"Turn on","time":{"created":1}}]}"#
        let messages = SetupMessage.read(Data(body.utf8))
        #expect(messages.map(\.kind) == [.you, .tool, .agent, .error])
        #expect(messages[1].text == "edit /c/opencode.json")
        #expect(SetupMessage.lastReply(messages) == "DONE")
    }

    // MARK: Prompts

    @Test func thePromptsNameTheAbsolutePathVerbatim() {
        let file = "/Users/example/.config/opencode/opencode.json"
        #expect(SessionPrompts.Artifacts.Setup.enable(file: file) == "Turn on OpenCode's experimental artifacts for me. In `/Users/example/.config/opencode/opencode.json`, add `\"artifacts\": true` inside the `\"experimental\"` object. If there's no `\"experimental\"` key, add `\"experimental\": { \"artifacts\": true }`; if the file doesn't exist, create it with just that. Change nothing else: keep every other setting, comment and the formatting as they are. Don't run commands, look anything up or touch any other file. Reply with exactly DONE, or FAILED: <reason>.")
        #expect(SessionPrompts.Artifacts.Setup.disable(file: file) == "Turn off OpenCode's experimental artifacts for me. In `/Users/example/.config/opencode/opencode.json`, remove `\"artifacts\": true` from the `\"experimental\"` object. Change nothing else: keep every other setting, comment and the formatting as they are. Don't run commands, look anything up or touch any other file. Reply with exactly DONE, or FAILED: <reason>.")
    }

    @Test func theLockdownAllowsOnlyTheGlobalFolderAndAsksForEdits() {
        let rules = SessionPrompts.Artifacts.Setup.lockdown(globalFolder: "/c/opencode")
        #expect(rules.map { "\($0.action) \($0.resource) \($0.effect)" } == [
            "shell * deny", "execute * deny", "webfetch * deny", "websearch * deny", "skill * deny", "subagent * deny",
            "external_directory * deny", "external_directory /c/opencode/* allow", "edit * ask",
        ])
        #expect(SessionPrompts.Artifacts.Setup.title == "Hex Code setup")
        #expect(DailySession.number(ofTitle: SessionPrompts.Artifacts.Setup.title, today: "2026-11-09") == nil)
    }

    // MARK: The browser

    private let connection = ServerConnection(baseURL: URL(string: "http://192.168.1.2:4096")!, username: "opencode", password: "test-password")

    @Test func anArtifactURLMapsToItsViewRoute() throws {
        #expect(ArtifactURL.start(id: "art_1Ab").absoluteString == "hexcode-artifact://art_1Ab/")
        let page = try #require(ArtifactURL.serverRequest(for: URL(string: "hexcode-artifact://art_1Ab/")!, id: "art_1Ab", directory: "/Users/example/acme", connection: connection))
        #expect(page.url?.absoluteString == "http://192.168.1.2:4096/api/experimental/artifacts/art_1Ab/view/?location%5Bdirectory%5D=/Users/example/acme")
        #expect(page.value(forHTTPHeaderField: "Authorization")?.hasPrefix("Basic ") == true)
        let file = try #require(ArtifactURL.serverRequest(for: URL(string: "hexcode-artifact://art_1ab/css/a%20b.css?v=2")!, id: "art_1Ab", directory: "/p", connection: connection))
        #expect(file.url?.absoluteString.contains("/api/experimental/artifacts/art_1Ab/view/css/a%20b.css?v=2&") == true)
        #expect(ArtifactURL.serverRequest(for: URL(string: "hexcode-artifact://art_other/")!, id: "art_1Ab", directory: "/p", connection: connection) == nil)
        #expect(ArtifactURL.serverRequest(for: URL(string: "https://example.com/")!, id: "art_1Ab", directory: "/p", connection: connection) == nil)
    }

    @Test func markdownShowsAsPlainText() {
        #expect(ArtifactURL.pageHeaders(["Content-Type": "text/markdown; charset=utf-8"])["Content-Type"] == "text/plain; charset=utf-8")
        #expect(ArtifactURL.pageHeaders(["content-type": "text/css"])["content-type"] == "text/css")
    }

    @Test func theArtifactsOwnFilesStayAndOtherOriginsGoToTheSystem() {
        let home = ArtifactURL.start(id: "art_1")
        #expect(BrowserPolicy.decide(URL(string: "hexcode-artifact://art_1/signup.html")!, home: home, mainFrame: true) == .allow)
        #expect(BrowserPolicy.decide(URL(string: "https://example.com/")!, home: home, mainFrame: true) == .system)
    }

    @Test func statusesMapToTheirErrors() {
        #expect(ArtifactFailure.of(status: 200) == nil)
        #expect(ArtifactFailure.of(status: 304) == nil)
        #expect(ArtifactFailure.of(status: 404) == .gone)
        #expect(ArtifactFailure.of(status: 403) == .off)
        #expect(ArtifactFailure.of(status: 401) == .signedOut)
        #expect(ArtifactFailure.of(status: 500) == .failed(500))
        #expect(ArtifactFailure.of(error: URLError(.timedOut)) == .unreachable)
        #expect(ArtifactFailure.of(error: URLError(.cancelled)) == nil)
    }

    @Test func theErrorsReadAsWritten() {
        let gone = ArtifactFailure.gone.shown(name: "Rapid Bison")
        #expect(gone?.title == "This artifact is gone")
        #expect(gone?.action == .done)
        let off = ArtifactFailure.off.shown(name: "Rapid Bison")
        #expect(off?.title == "Artifacts are off on Rapid Bison")
        #expect(off?.secondary == "Turn on…")
        #expect(ArtifactFailure.unreachable.shown(name: "Rapid Bison")?.title == "Couldn’t reach Rapid Bison")
        #expect(ArtifactFailure.failed(502).shown(name: "x")?.detail == "The server answered 502.")
        #expect(ArtifactFailure.signedOut.shown(name: "x") == nil)
    }
}
