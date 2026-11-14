import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

/// A server for artifacts: answers set by the test, calls recorded.
final class FakeArtifactsAPI: ArtifactsAPI, @unchecked Sendable {
    var isDeclared: Bool? = true
    /// The list route's status; `200` lists `artifacts`.
    var listStatus: Int? = 403
    var artifacts: [ArtifactInfo] = []
    var configs: [OpenCodeConfig?] = []
    var config: OpenCodeConfig? = ArtifactsTests.config([(ArtifactsTests.globalFile, #"{"model":"x","experimental":{"todo":true}}"#)])
    var folderFiles: [String]? = ["opencode.json"]
    var fileText: Data? = Data(#"{ "model": "x", "experimental": { "todo": true } }"#.utf8)
    var writeSucceeds = true
    var busy: Int64? = 100
    var existingSetup: String?
    var pending: [PendingPermissionV2] = []
    var messages: [SetupMessage] = []
    var sendFails = false

    private(set) var calls: [String] = []
    private(set) var created: [(directory: String, rules: [PermissionRule])] = []
    private(set) var sent: [(text: String, sessionID: String)] = []
    private(set) var replies: [(requestID: String, allow: Bool)] = []
    private(set) var writes: [(data: Data, path: String)] = []

    func declared() async -> Bool? { calls.append("declared"); return isDeclared }

    func list(directory: String) async -> HTTPAnswer {
        calls.append("list \(directory)")
        guard let listStatus else { return HTTPAnswer(status: nil) }
        guard listStatus == 200 else { return HTTPAnswer(status: listStatus) }
        let items = artifacts.map {
            #"{"id":"\#($0.id)","provider":"\#($0.provider)",\#($0.sessionID.map { #""sessionID":"\#($0)","# } ?? "")"title":"\#($0.title)","entry":"index.html","url":"\#($0.url)","time":{"created":1,"updated":\#($0.updated)}}"#
        }
        return HTTPAnswer(status: 200, body: Data(#"{"location":{"directory":"/p"},"data":[\#(items.joined(separator: ","))]}"#.utf8))
    }

    func config(directory: String) async -> OpenCodeConfig? {
        calls.append("config")
        if !configs.isEmpty { return configs.removeFirst() }
        return config
    }

    func files(in folder: String, directory: String) async -> [String]? { calls.append("files \(folder)"); return folderFiles }
    func read(name: String, folder: String) async -> Data? { calls.append("read \(folder)/\(name)"); return fileText }

    func write(_ data: Data, to path: String, directory: String) async -> Bool {
        writes.append((data, path))
        return writeSucceeds
    }

    func putRules(sessionID: String) async -> Bool { calls.append("put rules \(sessionID)"); return true }
    func deleteRules(sessionID: String) async { calls.append("delete rules \(sessionID)") }
    func busyStart(sessionID: String) async -> Int64? { busy }
    func findSetupSession(directory: String) async throws -> String? { calls.append("find setup"); return existingSetup }

    func createSetupSession(directory: String, model: ModelChoice, rules: [PermissionRule]) async throws -> String {
        created.append((directory, rules))
        return "ses_setup"
    }

    func send(_ text: String, sessionID: String) async throws {
        if sendFails { throw SessionStateAPIError.httpStatus(400, #"{"message":"Model not found"}"#) }
        sent.append((text, sessionID))
    }

    func pendingApprovals(sessionID: String) async -> [PendingPermissionV2] { pending }

    func reply(sessionID: String, requestID: String, allow: Bool) async throws {
        replies.append((requestID, allow))
        pending = []
    }

    func transcript(sessionID: String) async -> [SetupMessage] { messages }
}

@MainActor
struct ArtifactsFlowTests {
    final class Clock: @unchecked Sendable {
        var now = Date(timeIntervalSince1970: 1_000_000)
    }

    let api = FakeArtifactsAPI()
    let clock = Clock()
    let toasts = HexToaster()
    let defaults = UserDefaults(suiteName: "artifacts-tests-\(UUID().uuidString)")!

    func makeModel(today: String? = "ses_today") -> ArtifactsModel {
        let clock = clock
        let model = ArtifactsModel(
            api: api,
            directory: { "/p" },
            model: { ModelChoice(providerID: "opencode", modelID: "beam-free", variant: nil) },
            todaySessionID: { today },
            toasts: toasts,
            defaults: defaults,
            now: { clock.now }
        )
        // The 2 s loop never runs by itself: the tests tick.
        model.sleep = { _ in try await Task.sleep(for: .seconds(3600)) }
        model.restoreWait = { _ in }
        return model
    }

    private func line(_ type: String, _ data: String) -> String { #"{"type":"\#(type)","data":\#(data)}"# }

    private let approval = #"{"id":"per_1","sessionID":"ses_setup","action":"edit","resources":["/Users/example/.config/opencode/opencode.json"],"save":["*"],"metadata":{"files":[{"file":"/Users/example/.config/opencode/opencode.json","patch":"--- a\n+++ b\n@@\n-  \"experimental\": { \"todo\": true }\n+  \"experimental\": { \"todo\": true, \"artifacts\": true }\n","additions":1,"deletions":1}]}}"#

    /// The sheet open, the config read, Send tapped.
    private func sent(_ model: ArtifactsModel) async {
        await model.open()?.value
        await model.send()
    }

    // MARK: State and the rule

    @Test func undeclaredRoutesAreNotAvailableAndTheRuleGoes() async {
        api.isDeclared = false
        let model = makeModel()
        await model.sessionOpened()
        #expect(model.state == .unavailable)
        #expect(api.calls.contains("delete rules ses_today"))
        #expect(!api.calls.contains { $0.hasPrefix("list") })
        #expect(model.rowValue == "Not available")
        #expect(!model.rowEnabled)
    }

    @Test func onPutsTheRuleOnceAndOffDeletesIt() async {
        api.listStatus = 200
        let model = makeModel()
        await model.sessionOpened()
        await model.sessionOpened()
        #expect(model.state == .on)
        #expect(api.calls.filter { $0 == "put rules ses_today" }.count == 1)
        api.listStatus = 403
        await model.refresh()
        #expect(model.state == .off)
        #expect(api.calls.contains("delete rules ses_today"))
        #expect(model.rowValue == "Off")
    }

    @Test func aChangedAnswerSwitchesTheFeatureOffQuietly() async {
        api.listStatus = 200
        let model = makeModel()
        await model.sessionOpened()
        api.listStatus = 404
        await model.refresh()
        #expect(model.state == .unavailable)
        #expect(api.calls.last == "delete rules ses_today")
        // A timeout keeps the last state.
        let other = makeModel()
        api.listStatus = 403
        await other.sessionOpened()
        api.listStatus = nil
        await other.refresh()
        #expect(other.state == .off)
    }

    @Test func theCardShowsTheNewestOfTheLatestRun() async {
        api.listStatus = 200
        api.busy = 100
        api.artifacts = [
            ArtifactInfo(id: "art_old", sessionID: "ses_today", title: "Old", updated: 50),
            ArtifactInfo(id: "art_sub", sessionID: "ses_child", title: "From a subagent", updated: 300),
            ArtifactInfo(id: "art_new", sessionID: "ses_today", title: "New", updated: 200),
        ]
        let model = makeModel()
        #expect(await model.cardArtifact(rootID: "ses_today", sessions: ["ses_child"]) == CardArtifact(id: "art_sub", title: "From a subagent"))
        api.listStatus = 403
        #expect(await model.cardArtifact(rootID: "ses_today", sessions: []) == nil)
        #expect(model.state == .off)
    }

    // MARK: Before sending

    @Test func theSheetNamesTheFileAndSavesItsText() async {
        let model = makeModel()
        await model.sessionOpened()
        await model.open()?.value
        #expect(model.sheetShown)
        #expect(model.flow?.mode == .enable)
        #expect(model.flow?.stage == .explain)
        #expect(model.flow?.target?.path == ArtifactsTests.globalFile)
        #expect(model.flow?.prompt == SessionPrompts.Artifacts.Setup.enable(file: ArtifactsTests.globalFile))
        #expect(api.calls.contains("read \(ArtifactsTests.globalFolder)/opencode.json"))
        #expect(model.canRestore)
        #expect(model.isChecking)
    }

    @Test func anAlreadyBrokenFileIsNeverSentThePrompt() async {
        api.config = ArtifactsTests.config([])
        api.folderFiles = ["opencode.json"]
        let model = makeModel()
        await model.sessionOpened()
        await model.open()?.value
        #expect(model.flow?.stage == .cantSend(.alreadyBroken))
        await model.send()
        #expect(api.sent.isEmpty)
        #expect(model.flow?.target?.line == #""experimental": { "artifacts": true }"#)
    }

    @Test func theEnvironmentGetsTheLineOnly() async {
        api.config = ArtifactsTests.config([(nil, #"{"experimental":{}}"#)])
        let model = makeModel()
        await model.sessionOpened()
        await model.open()?.value
        #expect(model.flow?.stage == .cantSend(.environment))
    }

    // MARK: The setup session

    @Test func theSetupSessionIsCreatedOnceLockedDownAndReused() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        #expect(api.created.count == 1)
        #expect(api.created[0].directory == "/p")
        #expect(api.created[0].rules.last == PermissionRule(action: "edit", resource: "*", effect: "ask"))
        #expect(api.created[0].rules.contains(PermissionRule(action: "external_directory", resource: ArtifactsTests.globalFolder + "/*", effect: "allow")))
        #expect(api.sent.map(\.sessionID) == ["ses_setup"])
        #expect(api.sent[0].text == SessionPrompts.Artifacts.Setup.enable(file: ArtifactsTests.globalFile))
        #expect(model.flow?.stage == .running)
        #expect(model.flow?.sent == .ok)
        #expect(model.flow?.work == .doing)
        // Rejected, then Try again: the same session.
        model.received(line: line("permission.asked", approval))
        await model.answerApproval(allow: false)
        await model.tryAgain()
        #expect(api.created.count == 1)
        #expect(api.sent.count == 2)
    }

    @Test func anExistingSetupSessionIsReused() async {
        api.existingSetup = "ses_found"
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        #expect(api.created.isEmpty)
        #expect(api.sent.first?.sessionID == "ses_found")
    }

    @Test func theSetupSessionsToolsMakeTheLiveLine() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        model.received(line: line("session.tool.input.started", #"{"sessionID":"ses_setup","assistantMessageID":"m","id":"c","name":"read"}"#))
        #expect(model.flow?.liveLine == .reading("opencode.json"))
        // Today's session's tools don't.
        model.received(line: line("session.tool.input.started", #"{"sessionID":"ses_today","assistantMessageID":"m","id":"c","name":"edit"}"#))
        #expect(model.flow?.liveLine == .reading("opencode.json"))
    }

    @Test func theSetupApprovalNeverReachesTodaysSession() {
        var today = LiveSessionState(rootID: "ses_today")
        guard case let .approvalAsked(item)? = SetupEvent.decode(line("permission.asked", approval), sessionID: "ses_setup") else {
            Issue.record("not an approval")
            return
        }
        today.apply(.permissionAsked(item))
        #expect(today.pending.isEmpty)
    }

    @Test func theApprovalOffersOnlyAllowOnceOrReject() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        model.received(line: line("permission.asked", approval))
        #expect(model.flow?.pendingApproval?.id == "per_1")
        #expect(model.flow?.showsApproval == true)
        #expect(model.flow?.approval == .warn)
        #expect(model.rowValue == "Needs you")
        await model.answerApproval(allow: true)
        #expect(api.replies.map(\.allow) == [true])
        #expect(model.flow?.approval == .ok)
        #expect(model.flow?.showsApproval == false)
        #expect(model.flow?.stage == .running)
    }

    @Test func rejectingFallsBackToTheLine() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        model.received(line: line("permission.asked", approval))
        await model.answerApproval(allow: false)
        #expect(api.replies.map(\.allow) == [false])
        #expect(model.flow?.stage == .fallback(.rejected))
        #expect(model.flow?.approval == .fail)
        // The check keeps running: the line added by hand flips it to On.
        api.listStatus = 200
        api.config = ArtifactsTests.config([(ArtifactsTests.globalFile, #"{"model":"x","experimental":{"todo":true,"artifacts":true}}"#)])
        await model.tick()
        #expect(model.flow?.stage == .done)
        #expect(model.state == .on)
    }

    @Test func aMissedApprovalIsReadOnTheNextCheck() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        api.pending = [PendingPermissionV2(id: "per_2", sessionID: "ses_setup", action: "edit", resources: ["x"], save: ["*"])]
        await model.tick()
        #expect(model.flow?.pendingApproval?.id == "per_2")
    }

    // MARK: Checking

    @Test func theCheckTurnsItOnBeforeTheReplyAndPutsTheRule() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        api.listStatus = 200
        api.config = ArtifactsTests.config([(ArtifactsTests.globalFile, #"{"model":"x","experimental":{"todo":true,"artifacts":true}}"#)])
        await model.tick()
        #expect(model.flow?.stage == .done)
        #expect(model.flow?.check == .ok && model.flow?.end == .ok)
        #expect(model.state == .on)
        #expect(api.calls.contains("put rules ses_today"))
        #expect(!model.isChecking)
        #expect(model.rowValue == "On")
    }

    @Test func theLoopChecksEveryTwoSeconds() async {
        actor Waits { var all: [Duration] = []; func add(_ d: Duration) { all.append(d) } }
        let waits = Waits()
        let model = makeModel()
        model.sleep = { duration in
            await waits.add(duration)
            try await Task.sleep(for: .milliseconds(5))
        }
        await model.sessionOpened()
        api.listStatus = 403
        await model.open()?.value
        // Two checks, however busy the machine is.
        for _ in 0 ..< 200 where await waits.all.count < 3 {
            try? await Task.sleep(for: .milliseconds(20))
        }
        let seen = await waits.all
        #expect(seen.count >= 2)
        #expect(seen.allSatisfy { $0 == .seconds(2) })
        #expect(api.calls.filter { $0.hasPrefix("list") }.count >= 2)
        model.close()
    }

    @Test func onWithAChangedConfigOffersRestore() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        api.listStatus = 200
        api.config = ArtifactsTests.config([(ArtifactsTests.globalFile, #"{"experimental":{"artifacts":true}}"#)])
        await model.tick()
        #expect(model.flow?.stage == .broken(.changed(["model", "experimental.todo"])))
        #expect(model.state == .on)
    }

    @Test func aBrokenFileAfterTheRunOffersRestoreAndRestoreWritesTheSavedBytes() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        api.config = ArtifactsTests.config([])
        model.received(line: line("session.execution.succeeded", #"{"sessionID":"ses_setup"}"#))
        await model.runEnded(error: nil)
        #expect(model.flow?.stage == .broken(.broken))
        // OpenCode reads the restored file a moment later.
        api.configs = [ArtifactsTests.config([])]
        api.config = ArtifactsTests.config([(ArtifactsTests.globalFile, #"{"model":"x","experimental":{"todo":true}}"#)])
        await model.restore()
        #expect(api.writes.count == 1)
        #expect(api.writes[0].path == ArtifactsTests.globalFile)
        #expect(api.writes[0].data == api.fileText)
        #expect(model.flow?.stage == .fallback(.restored))
    }

    @Test func aFailedRestoreKeepsTheProblem() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        api.config = ArtifactsTests.config([])
        await model.runEnded(error: nil)
        api.writeSucceeds = false
        await model.restore()
        #expect(model.flow?.stage == .broken(.broken))
    }

    @Test func aFailedReplyGivesItsReasonAndTheLine() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        api.messages = [SetupMessage(id: "1", kind: .agent, text: "FAILED: the file is generated")]
        await model.runEnded(error: nil)
        #expect(model.flow?.stage == .fallback(.failed("the file is generated")))
        #expect(model.flow?.offersLine == true)
        // A reply saying DONE is never the status: still off is still off.
        let other = makeModel()
        await other.sessionOpened()
        await sent(other)
        api.messages = [SetupMessage(id: "1", kind: .agent, text: "DONE")]
        await other.runEnded(error: nil)
        #expect(other.flow?.stage == .fallback(.stillOff))
    }

    @Test func theFreeLimitFallsBackWithItsMessage() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        model.received(line: line("session.execution.failed", #"{"sessionID":"ses_setup","error":{"type":"provider.free-limit","message":"Free usage limit reached","status":429}}"#))
        await model.runEnded(error: SetupRunError(type: "provider.free-limit", message: "Free usage limit reached", status: 429))
        #expect(model.flow?.stage == .fallback(.freeLimit("Free usage limit reached")))
    }

    @Test func aPromptThatCantBeSentFallsBack() async {
        api.sendFails = true
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        #expect(model.flow?.stage == .fallback(.couldntRun("Model not found")))
    }

    @Test func sixtySecondsWithoutTheChangeTimeOutNotCountingTheApproval() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        clock.now += 30
        model.received(line: line("permission.asked", approval))
        clock.now += 40
        await model.answerApproval(allow: true)
        clock.now += 20
        await model.tick()
        #expect(model.flow?.stage == .running)
        clock.now += 11
        await model.tick()
        #expect(model.flow?.stage == .fallback(.timedOut))
    }

    // MARK: Turning off

    @Test func turningOffSendsTheDisablePromptAndDeletesTheRule() async {
        api.listStatus = 200
        api.config = ArtifactsTests.config([(ArtifactsTests.globalFile, #"{"model":"x","experimental":{"todo":true,"artifacts":true}}"#)])
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        #expect(model.flow?.mode == .disable)
        #expect(api.sent.first?.text == SessionPrompts.Artifacts.Setup.disable(file: ArtifactsTests.globalFile))
        api.listStatus = 403
        api.config = ArtifactsTests.config([(ArtifactsTests.globalFile, #"{"model":"x","experimental":{"todo":true}}"#)])
        await model.tick()
        #expect(model.flow?.stage == .done)
        #expect(model.state == .off)
        #expect(api.calls.last { $0.contains("rules") } == "delete rules ses_today")
    }

    // MARK: The sheet

    @Test func closingHidesARunningFlowAndEndsASettledOne() async {
        let model = makeModel()
        await model.sessionOpened()
        await sent(model)
        model.close()
        #expect(!model.sheetShown)
        #expect(model.flow != nil)
        // Hidden: the approval comes as a notice.
        model.received(line: line("permission.asked", approval))
        #expect(toasts.current?.title == "Approval needed")
        model.open()
        #expect(model.sheetShown && model.flow?.showsApproval == true)
        await model.answerApproval(allow: false)
        model.close()
        #expect(model.flow == nil)
        #expect(!model.isChecking)
    }

    @Test func theSuggestionGoesIntoTheDraft() async {
        var suggested: String?
        let model = makeModel()
        model.onSuggestion = { suggested = $0 }
        await model.sessionOpened()
        await sent(model)
        model.suggestPage()
        #expect(suggested == "Make a small page that previews")
        #expect(model.flow == nil && !model.sheetShown)
    }

    // MARK: The hint

    @Test func anHtmlWriteWhileOffShowsTheHintOncePerPhone() async {
        let model = makeModel()
        await model.sessionOpened()
        model.noteTools([ToolPartV2(id: "t", name: "write", status: "completed", path: "/p/preview/login.html")])
        #expect(model.hintShown)
        model.dismissHint()
        model.noteTools([ToolPartV2(id: "t", name: "write", status: "completed", path: "/p/x.html")])
        #expect(!model.hintShown)
    }

    @Test func noHintWhileOnOrForOtherFiles() async {
        let model = makeModel()
        await model.sessionOpened()
        model.noteTools([ToolPartV2(id: "t", name: "write", status: "completed", path: "/p/notes.md")])
        #expect(!model.hintShown)
        api.listStatus = 200
        await model.refresh()
        model.noteTools([ToolPartV2(id: "t", name: "write", status: "completed", path: "/p/x.html")])
        #expect(!model.hintShown)
    }
}
