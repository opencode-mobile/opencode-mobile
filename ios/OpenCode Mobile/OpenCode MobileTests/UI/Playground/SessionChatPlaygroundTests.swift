import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
@Suite(.serialized)
struct SessionChatPlaygroundTests {
    private let now: Int64 = 1_800_000_000_000

    private var root: String { PlaygroundContent.rootSessionID }

    @Test func everyScenarioBuildsAConsistentWorld() {
        for id in PlaygroundScenarioID.allCases {
            let scenario = PlaygroundScenarios.build(id, now: now)
            #expect(scenario.id == id)
            #expect(scenario.initial.session(scenario.boundSessionID) != nil, "\(id) binds a session it has")
            expectConsistent(id, scenario.initial)
            var state = scenario.initial
            for step in scenario.steps {
                state = step.apply(state, now)
                expectConsistent(id, state)
            }
            expectConsistent(id, scenario.finalState(now: now))
        }
    }

    @Test func sheetListsTwentyOneScenariosInFourGroups() {
        let codes = PlaygroundScenarioID.allCases.map(\.code)
        #expect(codes == (1...5).map { "M\($0)" } + (1...4).map { "L\($0)" } + (1...11).map { "P\($0)" } + ["S1"])
        var groups: [PlaygroundScenarioGroup] = []
        for id in PlaygroundScenarioID.allCases where groups.last != id.group { groups.append(id.group) }
        #expect(groups == PlaygroundScenarioGroup.allCases)
        for id in PlaygroundScenarioID.allCases {
            #expect(id.group.title.first == id.code.first, "\(id) code matches its group")
            #expect(!id.summary.isEmpty)
        }
    }

    @Test func liveScenariosJumpToLatestAndFullLifecycleAreScripted() {
        let scripted = PlaygroundScenarioID.allCases.filter { PlaygroundScenarios.build($0, now: now).isScripted }
        #expect(scripted == [.streamingTurn, .followUps, .subagents, .abortAndRetry, .jumpToLatest, .fullLifecycle])
    }

    @Test func contentStaysObviouslyFake() throws {
        let links = try Regex(#"https?://([a-z0-9.-]+)"#)
        let homes = try Regex(#"/Users/([A-Za-z0-9_-]+)"#)
        for id in PlaygroundScenarioID.allCases {
            let scenario = PlaygroundScenarios.build(id, now: now)
            // Everything the world holds: titles, directories, message text, tool input, output
            // and metadata, file names, branch, changed files, diffs and requests.
            let text = [scenario.initial, scenario.finalState(now: now)].map { String(reflecting: $0) }.joined(separator: "\n")
            for match in text.matches(of: links) {
                let host = String(text[match.output[1].range!])
                #expect(host.hasSuffix("example.com"), "\(id) links \(host)")
            }
            for match in text.matches(of: homes) {
                #expect(String(text[match.output[1].range!]) == "example", "\(id) names a home folder")
            }
            for session in scenario.finalState(now: now).sessions {
                #expect(
                    [PlaygroundContent.worktree, PlaygroundContent.worktreeCheckout].contains(session.directory),
                    "\(id) session \(session.id) lives in the fake project"
                )
                #expect(session.projectID == PlaygroundContent.projectID)
            }
            let paths = scenario.initial.changedFiles.map(\.path) + scenario.initial.turnDiff.map(\.path)
            #expect(paths.allSatisfy { $0.hasPrefix("src/") }, "\(id) changes only fake project files")
            if let branch = scenario.initial.branch { #expect(branch == PlaygroundContent.branch) }
        }
        #expect(PlaygroundContent.worktree.hasPrefix("/Users/example/acme-"))
        #expect(PlaygroundContent.worktreeCheckout.hasPrefix("/Users/example/acme-"))
    }

    @Test func everyScenarioKeepsItsMessagesInTimeOrder() {
        for id in PlaygroundScenarioID.allCases {
            let scenario = PlaygroundScenarios.build(id, now: now)
            for state in [scenario.initial, scenario.finalState(now: now)] {
                for (sessionID, messages) in state.messages {
                    let times = messages.compactMap(\.sentAtEpochMs)
                    #expect(times == times.sorted(), "\(id)/\(sessionID) is out of order")
                }
            }
        }
    }

    @Test func messageScenariosCoverWhatTheyName() {
        func messages(_ id: PlaygroundScenarioID) -> [Message] {
            let scenario = PlaygroundScenarios.build(id, now: now)
            return scenario.initial.messages(scenario.boundSessionID)
        }

        #expect(messages(.longConversation).count == 28)

        let user = messages(.userMessages)
        let files = user.flatMap(\.parts).compactMap { part -> FilePart? in
            if case let .file(file) = part { return file }
            return nil
        }
        #expect(files.filter { $0.kind == .image }.count == 4)
        #expect(files.filter { $0.kind == .pdf }.count == 1)
        #expect(user.contains { !$0.promptSkills.isEmpty })
        #expect(user.contains { $0.role == .user && $0.text.contains("\n") })
        #expect(tools(user).contains { $0.title == "Shell" && $0.output != nil })

        let replies = PlaygroundScenarios.build(.agentReplies, now: now).initial.messages(root)
        let replyTools = tools(replies)
        #expect(Set(replyTools.map(\.status)) == [.pending, .running, .completed, .error])
        #expect(replyTools.contains { $0.toolName == "edit" && $0.metadata != nil })
        #expect(replyTools.contains { $0.toolName == "todowrite" })
        #expect(replyTools.contains { $0.toolName == "task" && $0.status == .completed })
        #expect(replies.flatMap(\.parts).contains { if case .reasoning = $0 { true } else { false } })

        let notices = PlaygroundScenarios.build(.noticesAndFailures, now: now).initial
        let noticeKinds = Set(notices.messages(root).flatMap(\.parts).compactMap { part -> String? in
            guard case let .notice(notice) = part else { return nil }
            switch notice.kind {
            case .agentChanged: return "agentChanged"
            case .modelChanged: return "modelChanged"
            case .moved: return "moved"
            case .instructionsUpdated: return "instructionsUpdated"
            case .systemNote: return "systemNote"
            case .restart: return "restart"
            }
        })
        #expect(noticeKinds == ["agentChanged", "modelChanged", "moved", "instructionsUpdated"])
        #expect(notices.messages(root).flatMap(\.parts).contains { if case .compaction = $0 { true } else { false } })
        #expect(notices.messages(root).contains { $0.stopReason == .interrupted })
        #expect(notices.messages(root).contains { $0.failure != nil })
        if case .retrying = notices.activity(root) {} else { Issue.record("Notices & failures waits to retry") }

        #expect(messages(.emptySession).isEmpty)
    }

    @Test func streamingTurnStreamsThenEndsIdle() {
        let scenario = PlaygroundScenarios.build(.streamingTurn, now: now)
        #expect(scenario.steps.count == 10)
        #expect(scenario.steps.filter { $0.frames.count > 1 }.map(\.label) == ["Reasoning streams", "Reply streams"])

        // Each frame of the reply shows more of it.
        let replyStep = scenario.steps.firstIndex { $0.label == "Reply streams" }!
        var state = scenario.steps.prefix(replyStep).reduce(scenario.initial) { $1.apply($0, now) }
        let shown = scenario.steps[replyStep].frames.map { frame in
            state = frame.apply(state, now)
            return state.messages(root).last!.text
        }
        for (before, after) in zip(shown, shown.dropFirst()) {
            #expect(after.hasPrefix(before) && after.count > before.count)
        }

        let final = scenario.finalState(now: now)
        #expect(!final.isBusy(root))
        let reply = final.messages(root).last!
        #expect(reply.text == shown.last)
        #expect(reply.completedAtEpochMs != nil)
        let replyTools = tools([reply])
        #expect(replyTools.map(\.toolName) == ["read", "grep", "edit", "bash"])
        #expect(replyTools.allSatisfy { $0.status == .completed })
        #expect(!reply.parts.contains { part in
            switch part {
            case let .text(text): text.isStreaming
            case let .reasoning(reasoning): reasoning.isStreaming
            default: false
            }
        })
    }

    @Test func followUpsDeliverTheSteerThenRunTheQueue() {
        let scenario = PlaygroundScenarios.build(.followUps, now: now)
        let states = runningFold(scenario)
        let pending = states.map { pendingPrompts($0).map(\.delivery) }
        #expect(pending[0] == [.queue, .queue])
        #expect(pending[1].count == 3)
        #expect(pending[1].contains(.steer))
        #expect(pending[2].count == 2)
        #expect(pending[scenario.steps.firstIndex { $0.label == "First queued prompt runs" }! + 1].count == 1)
        #expect(pending.last!.isEmpty)
        let final = scenario.finalState(now: now)
        #expect(!final.isBusy(root))
        #expect(final.messages(root).filter { $0.role == .assistant }.allSatisfy { $0.completedAtEpochMs != nil })
    }

    @Test func subagentsScriptAsksThenFinishes() {
        let scenario = PlaygroundScenarios.build(.subagents, now: now)
        #expect(pill(scenario.initial)?.label == "2 subagents")

        let asking = scenario.steps[0].apply(scenario.initial, now)
        #expect(pill(asking)?.label == "explore needs input · 1 running")

        let final = scenario.finalState(now: now)
        #expect(pill(final) == nil)
        #expect(final.permissions.isEmpty)
        #expect(final.activity.isEmpty)
    }

    @Test func abortAndRetryInterruptsThenRetriesThenFinishes() {
        let scenario = PlaygroundScenarios.build(.abortAndRetry, now: now)
        let states = runningFold(scenario)
        let stopped = states[scenario.steps.firstIndex { $0.label == "Stop interrupts it" }! + 1]
        #expect(!stopped.isBusy(root))
        #expect(stopped.messages(root).last?.stopReason == .interrupted)
        let retrying = states[scenario.steps.firstIndex { $0.label == "Next prompt waits to retry" }! + 1]
        if case .retrying = retrying.activity(root) {} else { Issue.record("the next prompt waits to retry") }
        let final = states.last!
        #expect(!final.isBusy(root))
        #expect(final.messages(root).last?.completedAtEpochMs != nil)
        #expect(final.messages(root).last?.stopReason == nil)
    }

    @Test func jumpToLatestScriptAddsThreeMessagesBelow() {
        let scenario = PlaygroundScenarios.build(.jumpToLatest, now: now)
        #expect(scenario.hint != nil)
        let before = scenario.initial.messages(scenario.boundSessionID)
        let after = scenario.finalState(now: now).messages(scenario.boundSessionID)
        #expect(after.count == before.count + 3)
        #expect(after.suffix(3).map(\.role) == [.user, .assistant, .user])
        #expect(after[after.count - 2].completedAtEpochMs != nil)
        #expect(scenario.finalState(now: now).isBusy(scenario.boundSessionID))
    }

    @Test func fullLifecycleGoesFromSendToDone() {
        let scenario = PlaygroundScenarios.build(.fullLifecycle, now: now)
        #expect(!scenario.initial.isBusy(root))
        let states = runningFold(scenario)
        func after(_ label: String) -> PlaygroundState { states[scenario.steps.firstIndex { $0.label == label }! + 1] }
        #expect(pendingPrompts(after("A prompt is queued")).count == 1)
        #expect(pill(after("Subagents start"))?.label == "2 subagents")
        #expect(pill(after("explore needs input"))?.needsInput == true)
        #expect(pill(after("Subagents finish")) == nil)
        #expect(after("Subagents finish").isBusy(root))
        #expect(pendingPrompts(after("Queued prompt runs")).isEmpty)
        let final = states.last!
        #expect(!final.isBusy(root))
        #expect(final.permissions.isEmpty)
        #expect(final.messages(root).filter { $0.role == .assistant }.allSatisfy { $0.completedAtEpochMs != nil })
    }

    @Test func stillScenariosSetUpTheirMoment() {
        func build(_ id: PlaygroundScenarioID) -> PlaygroundScenario { PlaygroundScenarios.build(id, now: now) }

        let idle = build(.idleOnBranch).initial
        #expect(!idle.isBusy(idle.rootSessionID))
        #expect(idle.changedFiles.map(\.additions).reduce(0, +) == 128)
        #expect(idle.changedFiles.map(\.deletions).reduce(0, +) == 14)
        #expect(idle.todos.filter { $0.status == .completed }.count == 3)
        #expect(idle.todos.count == 5)

        let clean = build(.cleanWorktree)
        #expect(clean.boundSession.directory == PlaygroundContent.worktreeCheckout)
        #expect(clean.initial.changedFiles.isEmpty)
        #expect(clean.initial.branch != nil)

        let queued = build(.runningQueued).initial
        #expect(queued.isBusy(queued.rootSessionID))
        #expect(pendingPrompts(queued).count == 2)

        #expect(pill(build(.backgroundWork).initial)?.label == "2 subagents")

        let needsInput = build(.needsInput)
        #expect(needsInput.collapsesPermission)
        #expect(pill(needsInput.initial)?.needsInput == true)
        #expect(needsInput.initial.questions.count == 1)

        let side = build(.sideQuestionReady)
        #expect(side.readySideQuestions.count == 1)
        #expect(side.initial.isBusy(side.boundSessionID))

        let failed = build(.requestFailed).initial
        #expect(failed.messages(failed.rootSessionID).last?.failure != nil)

        let busy = build(.busyCorner)
        #expect(busy.collapsesPermission)
        #expect(busy.readySideQuestions.count == 1 && busy.runningSideQuestions.count == 1)
        #expect(pendingPrompts(busy.initial).count == 2)
        #expect(pill(busy.initial) != nil)

        let keyboard = build(.keyboardUp).initial
        #expect(pill(keyboard)?.needsInput == false)
        #expect(keyboard.branch != nil)

        #expect(build(.childSession).boundSession.parentID != nil)
    }

    @Test func cleanWorktreeShowsTheWorktreeCheckout() async {
        let scenario = PlaygroundScenarios.build(.cleanWorktree, now: now)
        let session = PlaygroundSession(scenario: scenario)
        defer { session.close() }
        await session.prepare()
        let viewModel = session.chatViewModel
        viewModel.startObserving()
        await viewModel.loadMessages()

        #expect(await eventually { viewModel.uiState.worktreeCheckout })
        #expect(viewModel.uiState.projectLabel == "acme-web-coupon-field")
        viewModel.stopObserving()
    }

    @Test func nextFinishesAStreamingStepThenRunsTheNextAtOnce() async {
        let session = PlaygroundSession(scenario: scripted([
            PlaygroundStep(label: "stream", frames: ["one", "one two", "one two three"].enumerated().map { index, text in
                PlaygroundFrame(afterMs: index == 0 ? 100 : 500) { state, _ in branch(state, text) }
            }),
            PlaygroundStep(afterMs: 5_000, label: "done") { state, _ in branch(state, "done") },
        ]))
        defer { session.close() }
        await session.prepare()
        // Two frames of the three-frame step have streamed.
        #expect(await eventually { session.world.state.branch == "one two" })
        #expect(session.player.progress.shownSteps == 1)
        #expect(session.player.progress.label == "stream")

        session.player.next()
        #expect(session.world.state.branch == "one two three")
        #expect(session.player.progress.isPlaying)

        session.player.pause()
        session.player.next()
        #expect(session.world.state.branch == "done")
        #expect(session.player.progress.shownSteps == 2)
        #expect(session.player.progress.isFinished)
        #expect(!session.player.progress.isPlaying)
    }

    @Test func fastPlayWaitsAQuarterAndScalesAWaitInProgress() async {
        let session = PlaygroundSession(scenario: scripted([
            PlaygroundStep(afterMs: 200, label: "first") { state, _ in branch(state, "first") },
            PlaygroundStep(afterMs: 2_000, label: "second") { state, _ in branch(state, "second") },
        ]))
        defer { session.close() }
        session.player.setFast(true)
        #expect(session.player.progress.isFast)
        #expect(session.player.nextWaitMs == 50)

        await session.prepare()
        #expect(await eventually { session.world.state.branch == "first" })
        // 2 000 ms at 4× is 500 ms; pause partway, then the speed scales what is left.
        try? await Task.sleep(for: .milliseconds(100))
        session.player.pause()
        let remaining = session.player.nextWaitMs ?? 0
        #expect(remaining > 0 && remaining < 500, "paused with \(remaining) ms left")
        session.player.setFast(false)
        #expect(session.player.nextWaitMs == remaining * PlaygroundPlayer.fastFactor)
        session.player.setFast(true)
        #expect(session.player.nextWaitMs == remaining)
        #expect(session.world.state.branch == "first")

        let resumedAt = PlaygroundWorld.systemNow()
        session.player.play()
        #expect(await eventually { session.world.state.branch == "second" })
        // A full wait at 1× would land well after the quarter-speed remainder.
        #expect(PlaygroundWorld.systemNow() - resumedAt < remaining + 400)
    }

    @Test func realScreenModelStreamsTheReplyIncrementally() async {
        let scenario = PlaygroundScenarios.build(.streamingTurn, now: now)
        let session = PlaygroundSession(scenario: scenario)
        defer { session.close() }
        await session.prepare()
        // Step through the script here instead of waiting on its timers.
        session.player.pause()
        let viewModel = session.chatViewModel
        viewModel.startObserving()
        await viewModel.loadMessages()

        let replyStep = scenario.steps.firstIndex { $0.label == "Reply streams" }!
        for step in scenario.steps.prefix(replyStep) {
            session.world.update { step.apply($0, now) }
        }
        var seen: [String] = []
        for frame in scenario.steps[replyStep].frames {
            session.world.update { frame.apply($0, now) }
            let expected = session.world.state.messages(root).last!.text
            #expect(await eventually { viewModel.uiState.messages.first { $0.id == "pg-l1-a" }?.text == expected })
            if let shown = viewModel.uiState.messages.first(where: { $0.id == "pg-l1-a" })?.text, shown != seen.last {
                seen.append(shown)
            }
        }
        #expect(seen.count >= 5, "the reply showed in several frames: \(seen)")
        for (before, after) in zip(seen, seen.dropFirst()) {
            #expect(after.hasPrefix(before))
        }
        #expect(seen.last == scenario.finalState(now: now).messages(root).last?.text)
        viewModel.stopObserving()
    }

    @Test func realScreenModelFollowsTheSubagentsScript() async {
        let scenario = PlaygroundScenarios.build(.subagents, now: now)
        let session = PlaygroundSession(scenario: scenario)
        defer { session.close() }
        await session.prepare()
        // Step through the script here instead of waiting on its timers.
        session.player.pause()
        let viewModel = session.chatViewModel
        viewModel.startObserving()
        await viewModel.loadMessages()

        #expect(await eventually { viewModel.uiState.backgroundWorkPill?.label == "2 subagents" })

        session.world.update { scenario.steps[0].apply($0, now) }
        #expect(await eventually { viewModel.uiState.backgroundWorkPill?.label == "explore needs input · 1 running" })
        #expect(viewModel.uiState.permissionRequest != nil)

        for step in scenario.steps.dropFirst() {
            session.world.update { step.apply($0, now) }
        }
        #expect(await eventually { viewModel.uiState.backgroundWorkPill == nil && viewModel.uiState.permissionRequest == nil })
        viewModel.stopObserving()
    }

    @Test func leavingMidScriptStopsTheScript() async {
        let session = PlaygroundSession(scenario: scripted([
            PlaygroundStep(afterMs: 50, label: "first") { state, _ in branch(state, "first") },
            PlaygroundStep(afterMs: 150, label: "second") { state, _ in branch(state, "second") },
        ]))
        await session.prepare()
        #expect(session.player.progress.isPlaying)
        #expect(await eventually { session.world.state.branch == "first" })
        session.close()
        let afterClose = session.world.state
        try? await Task.sleep(for: .milliseconds(300))
        #expect(session.world.state == afterClose)
        #expect(session.world.state.branch == "first")
        #expect(!session.player.progress.isPlaying)
    }

    @Test func pauseThenPlayKeepsTheRemainingWait() async {
        let session = PlaygroundSession(scenario: scripted([
            PlaygroundStep(afterMs: 600, label: "only") { state, _ in branch(state, "done") },
        ]))
        defer { session.close() }
        await session.prepare()
        try? await Task.sleep(for: .milliseconds(300))
        session.player.pause()
        let remaining = session.player.nextWaitMs ?? 0
        #expect(remaining > 0 && remaining < 600, "paused with \(remaining) ms left")
        #expect(session.world.state.branch != "done")

        let resumedAt = PlaygroundWorld.systemNow()
        session.player.play()
        #expect(await eventually { session.world.state.branch == "done" })
        // A full 600 ms wait again would land well after the remaining time.
        #expect(PlaygroundWorld.systemNow() - resumedAt < 550)
        #expect(session.player.progress.isFinished)
    }

    @Test func restartMidScriptStartsAFreshWorldWithoutStackingIsolation() async {
        let preferences = SessionChatDisplayPreferences(defaults: freshDefaults("restart"))
        let before = ProductAnalytics.suppressionCount
        let model = SessionChatPlaygroundModel(preferences: preferences)
        defer { model.close() }
        #expect(model.session?.scenario.id == .longConversation)
        #expect(ProductAnalytics.suppressionCount == before + 1)
        #expect(preferences.isSandboxed)

        model.select(.streamingTurn)
        let first = model.session!
        #expect(await eventually { model.isReady && first.player.progress.shownSteps == 1 })
        first.player.setFast(true)

        model.restart()
        let second = model.session!
        #expect(second !== first)
        #expect(second.scenario.id == .streamingTurn)
        #expect(second.player.progress.shownSteps == 0)
        #expect(second.player.progress.isFast, "restart keeps the speed")
        #expect(!first.player.progress.isPlaying)
        let firstAfterRestart = first.world.state
        #expect(await eventually { model.isReady && second.player.progress.isPlaying })
        try? await Task.sleep(for: .milliseconds(300))
        #expect(first.world.state == firstAfterRestart)

        model.selectNext()
        #expect(model.session?.scenario.id == .followUps)
        model.selectPrevious()
        model.selectPrevious()
        #expect(model.session?.scenario.id == .emptySession)
        model.select(.childSession)
        model.selectNext()
        #expect(model.session?.scenario.id == .longConversation)
        #expect(model.stepCounts[.streamingTurn] == 10)
        #expect(model.stepCounts[.longConversation] == 0)
        // Switching scenarios does not stack isolation.
        #expect(ProductAnalytics.suppressionCount == before + 1)
    }

    @Test func closingReleasesAnalyticsAndPreferences() {
        let defaults = freshDefaults("close")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        let before = ProductAnalytics.suppressionCount
        let model = SessionChatPlaygroundModel(preferences: preferences)
        #expect(ProductAnalytics.suppressionCount == before + 1)
        #expect(preferences.isSandboxed)

        model.close()
        model.close()
        #expect(ProductAnalytics.suppressionCount == before)
        #expect(!preferences.isSandboxed)
        #expect(model.isClosed)
        let session = model.session
        model.select(.idleOnBranch)
        #expect(model.session === session, "a closed playground opens nothing")
    }

    @Test func playgroundLeavesRealPreferencesUntouched() {
        let defaults = freshDefaults("untouched")
        // An updated install that has not answered the Timeline Style picker yet.
        defaults.set("standard", forKey: "timelineStyle")
        defaults.set(false, forKey: "timelineStylePickerDone")
        let stored = defaults.dictionaryRepresentation().filter { Self.preferenceKeys.contains($0.key) }
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        #expect(preferences.timelineStylePickerPending)

        let model = SessionChatPlaygroundModel(preferences: preferences)
        // The one-time offers stay for the real app.
        #expect(!preferences.timelineStylePickerPending)
        #expect(!preferences.shouldOfferChipTimelineIntro(turnToolCalls: 20, nowMs: now))
        // What Session Chat writes when the user answers them or switches delivery.
        preferences.dismissTimelineStylePicker()
        preferences.setTimelineStyle(.chips)
        preferences.postponeChipTimelineIntro(nowMs: now)
        preferences.finishChipTimelineIntro()
        preferences.setFollowUpDelivery(.queue)
        preferences.setCollapseConsecutiveToolCalls(false)
        #expect(preferences.timelineStyle == .chips)
        model.close()

        let after = defaults.dictionaryRepresentation().filter { Self.preferenceKeys.contains($0.key) }
        #expect(NSDictionary(dictionary: after).isEqual(to: stored))
        #expect(preferences.timelineStylePickerPending)
        #expect(preferences.timelineStyle == .standard)
        #expect(preferences.followUpDelivery == .steer)
        #expect(preferences.collapseConsecutiveToolCalls)
        #expect(!preferences.chipTimelineIntroDone)
    }

    private static let preferenceKeys: Set<String> = [
        "collapseConsecutiveToolCalls", "chipTimeline", "timelineStyle", "followUpDelivery",
        "chipTimelineIntroDone", "chipTimelineIntroSnoozedUntilMs", "timelineStylePickerDone",
    ]

    private func freshDefaults(_ name: String) -> UserDefaults {
        let suite = "SessionChatPlaygroundTests.\(name)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    /// Subagents with `steps` in place of its script.
    private func scripted(_ steps: [PlaygroundStep]) -> PlaygroundScenario {
        var scenario = PlaygroundScenarios.build(.subagents, now: now)
        scenario.steps = steps
        return scenario
    }

    private func runningFold(_ scenario: PlaygroundScenario) -> [PlaygroundState] {
        scenario.steps.reduce(into: [scenario.initial]) { states, step in states.append(step.apply(states.last!, now)) }
    }

    private func pendingPrompts(_ state: PlaygroundState) -> [PendingPrompt] {
        SessionChatViewModel.pendingPrompts(loaded: state.messages(state.rootSessionID), optimistic: [])
    }

    private func tools(_ messages: [Message]) -> [ToolPart] {
        messages.flatMap(\.parts).compactMap { part -> ToolPart? in
            if case let .tool(tool) = part { return tool }
            return nil
        }
    }

    private func eventually(_ condition: () -> Bool) async -> Bool {
        for _ in 0..<100 {
            if condition() { return true }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return condition()
    }

    private func pill(_ state: PlaygroundState) -> SessionChatBackgroundWorkPill? {
        SessionChatBackgroundWork.pill(for: SessionChatBackgroundWork.subagents(
            messages: state.messages(state.rootSessionID),
            sessions: state.sessions,
            busySessionIDs: Set(state.activity.keys),
            requestSessionIDs: state.permissions.map(\.sessionID) + state.questions.map(\.sessionID)
        ))
    }

    private func expectConsistent(_ id: PlaygroundScenarioID, _ state: PlaygroundState) {
        let sessionIDs = Set(state.sessions.map(\.id))
        #expect(state.messages.keys.allSatisfy(sessionIDs.contains), "\(id) has messages only for its sessions")
        #expect(state.activity.keys.allSatisfy(sessionIDs.contains), "\(id) is busy only in its sessions")
        let askers = state.permissions.map(\.sessionID) + state.questions.map(\.sessionID)
        #expect(askers.allSatisfy(sessionIDs.contains), "\(id) asks only from its sessions")
        for (sessionID, messages) in state.messages {
            #expect(Set(messages.map(\.id)).count == messages.count, "\(id)/\(sessionID) message ids are unique")
            let toolIDs = messages.flatMap(\.parts).compactMap { part -> String? in
                if case let .tool(tool) = part { return tool.id }
                return nil
            }
            #expect(Set(toolIDs).count == toolIDs.count, "\(id)/\(sessionID) tool ids are unique")
        }
    }
}

private func branch(_ state: PlaygroundState, _ name: String) -> PlaygroundState {
    var copy = state
    copy.branch = name
    return copy
}
