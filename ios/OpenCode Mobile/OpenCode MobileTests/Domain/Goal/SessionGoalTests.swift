import Foundation
import Testing
@testable import OpenCode_Mobile

/// The goal domain: RPC and event decoding, pause classification, kickoff and draft parsing.
struct SessionGoalTests {
    private static let state = #"{"id":"goal_1","objective":"Make the tests pass","status":"active","reason":"2 tests still fail","rounds":3,"maxRounds":50,"stalls":1,"boot":"b1","updatedAt":1700}"#

    // MARK: RPC output

    @Test func rpcOutput_decodesTheGoalState() throws {
        let goal = try #require(try SessionGoalAdapter.goal(fromRPCOutput: Data(#"{"output":\#(Self.state)}"#.utf8)))
        #expect(goal == SessionGoal(
            id: "goal_1",
            objective: "Make the tests pass",
            status: .active,
            reason: "2 tests still fail",
            rounds: 3,
            maxRounds: 50,
            stalls: 1,
            updatedAt: 1700
        ))
    }

    @Test func rpcOutput_nullOrMissingIsNoGoal() throws {
        #expect(try SessionGoalAdapter.goal(fromRPCOutput: Data(#"{"output":null}"#.utf8)) == nil)
        #expect(try SessionGoalAdapter.goal(fromRPCOutput: Data(#"{}"#.utf8)) == nil)
    }

    @Test func rpcOutput_optionalFieldsMayBeAbsent() throws {
        let body = #"{"output":{"id":"g","objective":"Ship it","status":"paused","rounds":0,"maxRounds":50,"boot":"b","updatedAt":5}}"#
        let goal = try #require(try SessionGoalAdapter.goal(fromRPCOutput: Data(body.utf8)))
        #expect(goal.reason == nil)
        #expect(goal.stalls == nil)
        #expect(goal.status == .paused)
    }

    @Test func rpcOutput_malformedOrUnknownStatusThrows() {
        #expect(throws: SessionGoalAdapter.DecodeError.invalidOutput) {
            try SessionGoalAdapter.goal(fromRPCOutput: Data("not json".utf8))
        }
        #expect(throws: SessionGoalAdapter.DecodeError.invalidOutput) {
            try SessionGoalAdapter.goal(
                fromRPCOutput: Data(#"{"output":{"id":"g","objective":"x","status":"done","rounds":0,"maxRounds":50,"updatedAt":1}}"#.utf8)
            )
        }
    }

    // MARK: Changed event

    @Test func changedEvent_fromTheWireCarriesTheGoal() throws {
        let line = #"{"id":"evt_1","created":1,"type":"rpc.opencode.goal.changed","location":{"directory":"/Users/example/project"},"data":{"sessionID":"ses_1","goal":\#(Self.state)}}"#
        let event = try #require(EventDomainAdapter().parseEvents(dataLine: line).first)
        let change = try #require(SessionGoalAdapter.change(from: event))
        #expect(change.sessionID == "ses_1")
        #expect(change.goal?.id == "goal_1")
        #expect(change.goal?.rounds == 3)
    }

    @Test func changedEvent_nullGoalIsACleared() throws {
        let line = #"{"id":"evt_2","created":2,"type":"rpc.opencode.goal.changed","location":{"directory":"/Users/example/project"},"data":{"sessionID":"ses_1","goal":null}}"#
        let event = try #require(EventDomainAdapter().parseEvents(dataLine: line).first)
        #expect(SessionGoalAdapter.change(from: event) == SessionGoalChange(sessionID: "ses_1", goal: nil))
    }

    @Test func changedEvent_otherEventsAndMalformedGoalsAreIgnored() {
        #expect(SessionGoalAdapter.change(from: OpencodeEvent(type: "session.updated", sessionID: "ses_1")) == nil)
        let malformed = OpencodeEvent(
            type: SessionGoalAdapter.changedEvent,
            sessionID: "ses_1",
            rawProperties: ["sessionID": .string("ses_1"), "goal": .dictionary(["id": .string("g")])]
        )
        #expect(SessionGoalAdapter.change(from: malformed) == nil)
    }

    @Test func commandError_readsTheServerMessage() {
        let error = CommandError.http(
            statusCode: 500,
            message: #"{"_tag":"CommandExecutionError","command":"goal","message":"No active goal to pause."}"#
        )
        #expect(SessionGoalAdapter.commandErrorMessage(error) == "No active goal to pause")
        #expect(SessionGoalAdapter.commandErrorMessage(CommandError.transport(.captured(from: URLError(.timedOut)))) == nil)
    }

    // MARK: Pause classification

    @Test(arguments: [
        ("Paused by the user", SessionGoalPauseKind.byUser, true),
        ("Stopped by the user", .stopped, true),
        ("The session failed: the provider returned 429 Too Many Requests", .failed, false),
        ("Not making progress: 1 test still fails", .noProgress, false),
        ("Reached the limit of 50 rounds", .roundLimit, true),
        ("The server restarted while the goal was active", .serverRestart, true),
        ("The goal reviewer gave no usable verdict", .reviewerFailed, false),
        ("The goal reviewer's request is too large for the model", .reviewerFailed, false),
        ("Nothing ran for 30 minutes while the goal waited on background work", .backgroundQuiet, false),
        ("Needs a decision: keep the old sort order?", .other, false),
    ])
    func pause_classifiesServerReasons(reason: String, kind: SessionGoalPauseKind, hidesReason: Bool) {
        let pause = SessionGoalPause.classify(reason)
        #expect(pause.kind == kind)
        #expect(pause.hidesReason == hidesReason)
        #expect(pause.isKnown == (kind != .other))
    }

    @Test func pause_emptyReasonIsOtherWithNoReasonLine() {
        #expect(SessionGoalPause.classify(nil) == SessionGoalPause(kind: .other, hidesReason: true))
        #expect(SessionGoalPause.classify("  ") == SessionGoalPause(kind: .other, hidesReason: true))
    }

    @Test func pause_onlyForPausedGoals() {
        #expect(goal(.active, reason: "Paused by the user").pause == nil)
        #expect(goal(.paused, reason: "Paused by the user").pause?.kind == .byUser)
    }

    // MARK: Kickoff

    @Test func kickoff_objectiveIsParsedAndUnescaped() {
        let text = """
        A goal is now active for this session. Work toward it across turns until it is met.

        <untrusted_objective>
        Keep a &lt;b&gt; tag &amp;&amp; fix &amp;lt; escapes
        </untrusted_objective>

        Start now.
        """
        #expect(SessionGoalKickoff.objective(fromKickoffText: text) == "Keep a <b> tag && fix &lt; escapes")
    }

    @Test func kickoff_otherTextIsNotAKickoff() {
        #expect(SessionGoalKickoff.objective(fromKickoffText: "Refactor the session list") == nil)
        #expect(SessionGoalKickoff.objective(fromKickoffText: "<untrusted_objective>\nx\n</untrusted_objective>") == nil)
    }

    @Test func kickoff_currentGoalWinsOnlyForItsOwnKickoff() {
        let text = "A goal is now active for this session.\n<untrusted_objective>\nOld words\n</untrusted_objective>"
        let current = goal(.active, id: "goal_new", objective: "New words")
        #expect(SessionGoalKickoff.displayObjective(kickoffText: text, goalID: "goal_new", current: current) == "New words")
        #expect(SessionGoalKickoff.displayObjective(kickoffText: text, goalID: "goal_old", current: current) == "Old words")
        #expect(SessionGoalKickoff.displayObjective(kickoffText: text, goalID: nil, current: current) == "Old words")
    }

    @Test func kickoff_goalMetadataNeedsSourceAndID() {
        #expect(SessionGoalKickoff.goalID(source: "goal", goalID: "g1") == "g1")
        #expect(SessionGoalKickoff.goalID(source: "plan", goalID: "g1") == nil)
        #expect(SessionGoalKickoff.goalID(source: "goal", goalID: "") == nil)
    }

    // MARK: Draft

    @Test func draft_objectiveOfAGoalCommand() {
        #expect(SessionGoalDraft.objective("/goal Ship it") == "Ship it")
        #expect(SessionGoalDraft.objective("/goal ") == "")
        #expect(SessionGoalDraft.objective("/goals x") == nil)
        #expect(SessionGoalDraft.objective("/goal pause") == nil)
        #expect(SessionGoalDraft.objective("/goal Resume") == nil)
        #expect(SessionGoalDraft.objective("hello /goal x") == nil)
        #expect(SessionGoalDraft.awaitsObjective("/goal "))
        #expect(!SessionGoalDraft.awaitsObjective("/goal"))
    }

    @Test func draft_sendStaysOffWhileEmptyOrTooLong() {
        #expect(!SessionGoalDraft.canSend(objective: ""))
        #expect(SessionGoalDraft.canSend(objective: String(repeating: "a", count: 4_000)))
        #expect(!SessionGoalDraft.canSend(objective: String(repeating: "a", count: 4_001)))
        #expect(!SessionGoalDraft.showsCount(objective: String(repeating: "a", count: 3_599)))
        #expect(SessionGoalDraft.showsCount(objective: String(repeating: "a", count: 3_600)))
    }

    private func goal(
        _ status: SessionGoalStatus,
        id: String = "goal_1",
        objective: String = "Make the tests pass",
        reason: String? = nil
    ) -> SessionGoal {
        SessionGoal(id: id, objective: objective, status: status, reason: reason, rounds: 1, maxRounds: 50, updatedAt: 1)
    }
}
