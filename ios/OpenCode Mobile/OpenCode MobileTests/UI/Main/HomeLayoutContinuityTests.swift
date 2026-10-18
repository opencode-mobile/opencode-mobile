import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct HomeLayoutContinuityTests {
    private let chat = SessionListItem(id: "ses-1", title: "Chat", worktree: "/repo")
    private let other = SessionListItem(id: "ses-2", title: "Other", worktree: "/repo")
    private let terminal = TerminalRoute(worktree: "/repo", projectTitle: "repo", ptyId: "pty-1")

    // MARK: Stacked → split

    @Test func unfold_openChatBecomesSelection() {
        let next = HomeLayoutContinuity.toSplit(HomeNavigationState(routes: [.session(chat)]))

        #expect(next == HomeNavigationState(routes: [], selection: chat))
    }

    @Test func unfold_onHomeChangesNothing() {
        let home = HomeNavigationState(routes: [])

        #expect(HomeLayoutContinuity.toSplit(home) == home)
    }

    @Test func unfold_withoutSessionKeepsPushedScreens() {
        let terminals = HomeNavigationState(routes: [.terminalsCatalog, .terminal(terminal)])

        #expect(HomeLayoutContinuity.toSplit(terminals) == terminals)
    }

    @Test func unfold_deepestSessionWins() {
        let next = HomeLayoutContinuity.toSplit(
            HomeNavigationState(routes: [.session(other), .session(chat)])
        )

        #expect(next == HomeNavigationState(routes: [], selection: chat))
    }

    @Test func unfold_keepsScreensPushedAboveTheSession() {
        let next = HomeLayoutContinuity.toSplit(
            HomeNavigationState(routes: [.session(chat), .terminalsCatalog, .terminal(terminal)])
        )

        #expect(next == HomeNavigationState(routes: [.terminalsCatalog, .terminal(terminal)], selection: chat))
    }

    @Test func unfold_dropsScreensBelowTheSession() {
        // The sidebar replaces Home, so nothing should cover the selected chat.
        let next = HomeLayoutContinuity.toSplit(
            HomeNavigationState(routes: [.terminalsCatalog, .session(chat)])
        )

        #expect(next == HomeNavigationState(routes: [], selection: chat))
    }

    // MARK: Split → stacked

    @Test func fold_selectionBecomesSingleChatRoute() {
        let next = HomeLayoutContinuity.toStacked(HomeNavigationState(routes: [], selection: chat))

        #expect(next == HomeNavigationState(routes: [.session(chat)], selection: nil))
    }

    @Test func fold_withoutSelectionShowsHome() {
        let home = HomeNavigationState(routes: [])

        #expect(HomeLayoutContinuity.toStacked(home) == home)
    }

    @Test func fold_withoutSelectionKeepsPushedScreens() {
        let terminals = HomeNavigationState(routes: [.terminalsCatalog])

        #expect(HomeLayoutContinuity.toStacked(terminals) == terminals)
    }

    @Test func fold_chatGoesUnderScreensPushedOverTheSplit() {
        let next = HomeLayoutContinuity.toStacked(
            HomeNavigationState(routes: [.terminalsCatalog], selection: chat)
        )

        #expect(next == HomeNavigationState(routes: [.session(chat), .terminalsCatalog], selection: nil))
    }

    @Test func fold_doesNotDuplicateTheSelectedChat() {
        let next = HomeLayoutContinuity.toStacked(
            HomeNavigationState(routes: [.session(chat)], selection: chat)
        )

        #expect(next.routes == [.session(chat)])
    }

    // MARK: Round trips

    @Test func foldThenUnfold_restoresSelection() {
        let split = HomeNavigationState(routes: [.terminalsCatalog], selection: chat)

        let roundTrip = HomeLayoutContinuity.toSplit(HomeLayoutContinuity.toStacked(split))

        #expect(roundTrip == split)
    }

    @Test func unfoldThenFold_restoresChatRoute() {
        let stacked = HomeNavigationState(routes: [.session(chat)])

        let roundTrip = HomeLayoutContinuity.toStacked(HomeLayoutContinuity.toSplit(stacked))

        #expect(roundTrip == stacked)
    }

    @Test func optimisticCreatesKeepTheirInstance() {
        let placeholder = OptimisticSessionCreate.placeholderSessionID
        let first = SessionListItem(id: placeholder, title: "New", worktree: "/repo", optimisticNavigationInstanceID: UUID())
        let second = SessionListItem(id: placeholder, title: "New", worktree: "/repo", optimisticNavigationInstanceID: UUID())

        let next = HomeLayoutContinuity.toSplit(HomeNavigationState(routes: [.session(first)]))

        #expect(next.selection?.navigationID == first.navigationID)
        #expect(first.navigationID != second.navigationID)
    }

    @Test func navigationIDIsSessionIDForListedSessions() {
        #expect(chat.navigationID == "ses-1")
    }
}
