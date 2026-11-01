import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
@Suite(.serialized)
struct SessionChatPlaygroundTests {
    private let now: Int64 = 1_800_000_000_000

    @Test func everyScenarioBuildsAConsistentWorld() {
        for id in PlaygroundScenarioID.allCases {
            let scenario = PlaygroundScenarios.build(id, now: now)
            #expect(scenario.id == id)
            #expect(scenario.initial.session(scenario.boundSessionID) != nil, "\(id) binds a session it has")
            expectConsistent(id, scenario.initial)
            expectConsistent(id, scenario.finalState(now: now))
        }
    }

    @Test func onlyEverythingSubagentsAndScrolledUpAreScripted() {
        let scripted = PlaygroundScenarioID.allCases.filter { PlaygroundScenarios.build($0, now: now).isScripted }
        #expect(scripted == [.everything, .subagents, .scrolledUp])
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
            for session in scenario.initial.sessions {
                #expect(session.directory == PlaygroundContent.worktree, "\(id) session \(session.id) lives in the fake project")
                #expect(session.projectID == PlaygroundContent.projectID)
            }
            let paths = scenario.initial.changedFiles.map(\.path) + scenario.initial.turnDiff.map(\.path)
            #expect(paths.allSatisfy { $0.hasPrefix("src/") }, "\(id) changes only fake project files")
            if let branch = scenario.initial.branch { #expect(branch == PlaygroundContent.branch) }
        }
        #expect(PlaygroundContent.worktree.hasPrefix("/Users/example/acme-"))
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

    @Test func everythingCoversEveryToolStateAndEndsIdle() {
        let scenario = PlaygroundScenarios.build(.everything, now: now)
        let root = scenario.boundSessionID
        let statuses = Set(scenario.initial.messages(root).flatMap(\.parts).compactMap { part -> ToolStatus? in
            if case let .tool(tool) = part { return tool.status }
            return nil
        })
        #expect(statuses == [.pending, .running, .completed, .error])
        #expect(scenario.initial.isBusy(root))
        #expect(SessionChatViewModel.pendingPrompts(loaded: scenario.initial.messages(root), optimistic: []).count == 1)

        let final = scenario.finalState(now: now)
        #expect(!final.isBusy(root))
        #expect(SessionChatViewModel.pendingPrompts(loaded: final.messages(root), optimistic: []).isEmpty)
        #expect(final.messages(root).last { $0.role == .assistant }?.completedAtEpochMs != nil)
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

    @Test func scrolledUpScriptAddsThreeMessagesBelow() {
        let scenario = PlaygroundScenarios.build(.scrolledUp, now: now)
        let root = scenario.boundSessionID
        let before = scenario.initial.messages(root)
        let after = scenario.finalState(now: now).messages(root)
        #expect(after.count == before.count + 3)
        #expect(after.suffix(3).map(\.role) == [.user, .assistant, .user])
        #expect(after[after.count - 2].completedAtEpochMs != nil)
        #expect(scenario.finalState(now: now).isBusy(root))
    }

    @Test func stillScenariosSetUpTheirMoment() {
        let quiet = PlaygroundScenarios.build(.quiet, now: now).initial
        #expect(!quiet.isBusy(quiet.rootSessionID))
        #expect(quiet.branch != nil && !quiet.changedFiles.isEmpty && !quiet.todos.isEmpty)

        let working = PlaygroundScenarios.build(.working, now: now).initial
        #expect(working.isBusy(working.rootSessionID))
        #expect(SessionChatViewModel.pendingPrompts(loaded: working.messages(working.rootSessionID), optimistic: []).count == 2)

        let needsInput = PlaygroundScenarios.build(.needsInput, now: now)
        #expect(needsInput.collapsesPermission)
        #expect(pill(needsInput.initial)?.needsInput == true)
        #expect(needsInput.initial.questions.count == 1)

        let side = PlaygroundScenarios.build(.sideQuestionReady, now: now)
        #expect(side.readySideQuestions.count == 1)
        #expect(side.initial.isBusy(side.boundSessionID))

        let failed = PlaygroundScenarios.build(.requestFailed, now: now).initial
        #expect(failed.messages(failed.rootSessionID).last?.failure != nil)

        let busy = PlaygroundScenarios.build(.busyCorner, now: now)
        #expect(busy.collapsesPermission)
        #expect(busy.readySideQuestions.count == 1 && busy.runningSideQuestions.count == 1)
        #expect(SessionChatViewModel.pendingPrompts(loaded: busy.initial.messages(busy.boundSessionID), optimistic: []).count == 2)
        #expect(pill(busy.initial) != nil)

        let keyboard = PlaygroundScenarios.build(.keyboardUp, now: now).initial
        #expect(pill(keyboard)?.needsInput == false)
        #expect(keyboard.branch != nil)

        #expect(PlaygroundScenarios.build(.childSession, now: now).boundSession.parentID != nil)
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
        var scenario = PlaygroundScenarios.build(.subagents, now: now)
        scenario.steps = [
            PlaygroundStep(afterMs: 50, label: "first") { state, _ in var copy = state; copy.branch = "first"; return copy },
            PlaygroundStep(afterMs: 150, label: "second") { state, _ in var copy = state; copy.branch = "second"; return copy },
        ]
        let session = PlaygroundSession(scenario: scenario)
        await session.prepare()
        #expect(await eventually { session.world.state.branch == "first" })
        session.close()
        let afterClose = session.world.state
        try? await Task.sleep(for: .milliseconds(300))
        #expect(session.world.state == afterClose)
        #expect(session.world.state.branch == "first")
        #expect(!session.player.isPlaying)
    }

    @Test func pauseThenPlayKeepsTheRemainingWait() async {
        var scenario = PlaygroundScenarios.build(.subagents, now: now)
        scenario.steps = [
            PlaygroundStep(afterMs: 600, label: "only") { state, _ in var copy = state; copy.branch = "done"; return copy },
        ]
        let session = PlaygroundSession(scenario: scenario)
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
        #expect(session.player.isFinished)
    }

    @Test func restartMidScriptStartsAFreshWorld() async {
        let preferences = SessionChatDisplayPreferences(defaults: freshDefaults("restart"))
        let before = ProductAnalytics.suppressionCount
        let model = SessionChatPlaygroundModel(preferences: preferences)
        defer { model.close() }
        #expect(await eventually { model.isReady && model.session?.player.isPlaying == true })
        let first = model.session!
        first.world.update { state in var copy = state; copy.branch = "changed"; return copy }

        model.restart()
        let second = model.session!
        #expect(second !== first)
        #expect(second.scenario.id == .everything)
        #expect(second.world.state.branch == PlaygroundContent.branch)
        #expect(!first.player.isPlaying)
        #expect(await eventually { model.isReady && second.player.isPlaying })
        // Switching scenarios does not stack isolation.
        model.open(.quiet)
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
        model.open(.quiet)
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
