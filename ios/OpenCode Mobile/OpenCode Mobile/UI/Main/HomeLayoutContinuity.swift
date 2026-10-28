import Foundation

/// Screens pushed on Home's own stack. Typed rather than a `NavigationPath` so a
/// split ↔ stacked flip can find the open session in it.
enum HomeRoute: Hashable {
    case session(SessionListItem)
    case terminalsCatalog
    case terminal(TerminalRoute)
    /// Settled list pushed on the same stack, so system Back pops it like any other route.
    case settled(SettledSessionsRoute)

    var session: SessionListItem? {
        if case let .session(session) = self { return session }
        return nil
    }
}

/// Where Home's navigation stands: its stack plus the split detail-pane session.
struct HomeNavigationState: Equatable {
    var routes: [HomeRoute]
    /// Session in the split detail pane. Nil while stacked: there the open chat is a route.
    var selection: SessionListItem?
}

/// Keeps the open session and the screens above it when the window flips between
/// the stacked phone layout and the split (iPhone Duo fold ↔ unfold, iPad resizes).
enum HomeLayoutContinuity {
    /// Stacked → split. The deepest session route becomes the selection and the
    /// routes pushed above it stay on top of the split. Routes below it are dropped,
    /// since the sidebar now stands in for Home. Without a session route nothing changes.
    static func toSplit(_ state: HomeNavigationState) -> HomeNavigationState {
        guard let index = state.routes.lastIndex(where: { $0.session != nil }),
              let session = state.routes[index].session
        else { return state }
        return HomeNavigationState(
            routes: Array(state.routes[state.routes.index(after: index)...]),
            selection: session
        )
    }

    /// Split → stacked. The selected session goes under any routes pushed over the
    /// split, so Back leads to that chat and then Home. Without a selection the
    /// stack stays as it is, which is Home when nothing is pushed.
    static func toStacked(_ state: HomeNavigationState) -> HomeNavigationState {
        guard let selection = state.selection else { return state }
        return HomeNavigationState(
            routes: [.session(selection)] + state.routes.filter { $0.session?.navigationID != selection.navigationID },
            selection: nil
        )
    }
}

extension SessionListItem {
    /// Identity of one opened chat. Optimistic creates get a fresh instance each
    /// time, so a promoted chat is never reused for a new placeholder.
    var navigationID: String {
        if let optimisticNavigationInstanceID {
            return "\(id)-\(optimisticNavigationInstanceID.uuidString)"
        }
        return id
    }
}
