import Foundation
import Observation

enum SessionContextViewState: Equatable {
    case loading
    case loaded(SessionContextDisplayState)
    case error(String)
}

struct SessionContextDisplayState: Equatable {
    let stats: [SessionContextStatItem]
    let breakdown: [SessionContextBreakdownItem]
    let rawMessages: [SessionContextRawMessageRow]
}

struct SessionContextStatItem: Equatable, Identifiable {
    let id: String
    let label: String
    let value: String
}

@MainActor
@Observable
final class SessionContextViewModel {
    private(set) var state: SessionContextViewState = .loading
    private(set) var isRefreshing = false
    private(set) var refreshErrorMessage: String?

    private let sessionID: String
    private let worktree: String
    private let repository: any SessionContextRepository
    private let numberFormatter: NumberFormatter
    private let currencyFormatter: NumberFormatter
    private let dateFormatter: DateFormatter

    init(sessionID: String, worktree: String, repository: any SessionContextRepository) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.repository = repository

        let numberFormatter = NumberFormatter()
        numberFormatter.numberStyle = .decimal
        self.numberFormatter = numberFormatter

        let currencyFormatter = NumberFormatter()
        currencyFormatter.numberStyle = .currency
        currencyFormatter.currencyCode = "USD"
        self.currencyFormatter = currencyFormatter

        let dateFormatter = DateFormatter()
        dateFormatter.locale = Locale(identifier: "en_US_POSIX")
        dateFormatter.dateStyle = .medium
        dateFormatter.timeStyle = .short
        self.dateFormatter = dateFormatter
    }

    func load() async {
        state = .loading
        await refresh(setRefreshing: false)
    }

    func refresh() async {
        await refresh(setRefreshing: true)
    }

    private func refresh(setRefreshing: Bool) async {
        if setRefreshing { isRefreshing = true }
        defer { isRefreshing = false }

        do {
            let snapshot = try await repository.loadSessionContext(sessionID: sessionID, directory: worktree)
            refreshErrorMessage = nil
            state = .loaded(displayState(from: snapshot))
        } catch is CancellationError {
            return
        } catch let error as URLError where error.code == .cancelled {
            return
        } catch {
            let message = errorMessage(error, fallback: setRefreshing ? "Unable to refresh session context" : "Unable to load session context")
            if setRefreshing, case .loaded = state {
                refreshErrorMessage = message
            } else {
                refreshErrorMessage = nil
                state = .error(message)
            }
        }
    }

    private func errorMessage(_ error: Error, fallback: String) -> String {
        let message = (error as? SessionContextError)?.localizedDescription ?? error.localizedDescription
        return message.isEmpty ? fallback : message
    }

    private func displayState(from snapshot: SessionContextSnapshot) -> SessionContextDisplayState {
        let tokenStats = snapshot.tokenStats
        let stats = [
            SessionContextStatItem(id: "session", label: "Session", value: snapshot.sessionTitle),
            SessionContextStatItem(id: "messages", label: "Messages", value: number(snapshot.messageCounts.total)),
            SessionContextStatItem(id: "provider", label: "Provider", value: snapshot.providerLabel ?? "—"),
            SessionContextStatItem(id: "model", label: "Model", value: snapshot.modelLabel ?? "—"),
            SessionContextStatItem(id: "limit", label: "Context Limit", value: number(tokenStats?.contextLimit)),
            SessionContextStatItem(id: "total", label: "Total Tokens", value: number(tokenStats?.total)),
            SessionContextStatItem(id: "usage", label: "Usage", value: percent(tokenStats?.usagePercent)),
            SessionContextStatItem(id: "input", label: "Input Tokens", value: number(tokenStats?.input)),
            SessionContextStatItem(id: "output", label: "Output Tokens", value: number(tokenStats?.output)),
            SessionContextStatItem(id: "reasoning", label: "Reasoning Tokens", value: number(tokenStats?.reasoning)),
            SessionContextStatItem(
                id: "cache",
                label: "Cache Tokens (read/write)",
                value: "\(number(tokenStats?.cacheRead)) / \(number(tokenStats?.cacheWrite))"
            ),
            SessionContextStatItem(id: "user", label: "User Messages", value: number(snapshot.messageCounts.user)),
            SessionContextStatItem(id: "assistant", label: "Assistant Messages", value: number(snapshot.messageCounts.assistant)),
            SessionContextStatItem(id: "cost", label: "Total Cost", value: currency(snapshot.totalCost)),
            SessionContextStatItem(id: "created", label: "Session Created", value: time(snapshot.createdAtEpochMs)),
            SessionContextStatItem(id: "activity", label: "Last Activity", value: time(snapshot.lastActivityEpochMs)),
        ]
        return SessionContextDisplayState(stats: stats, breakdown: snapshot.breakdown, rawMessages: snapshot.rawMessages)
    }

    private func number(_ value: Int?) -> String {
        value.map { numberFormatter.string(from: NSNumber(value: $0)) ?? "\($0)" } ?? "—"
    }

    private func number(_ value: Int64?) -> String {
        value.map { numberFormatter.string(from: NSNumber(value: $0)) ?? "\($0)" } ?? "—"
    }

    private func percent(_ value: Int?) -> String {
        value.map { "\($0)%" } ?? "—"
    }

    private func currency(_ value: Double) -> String {
        currencyFormatter.string(from: NSNumber(value: value)) ?? "$0.00"
    }

    private func time(_ epochMs: Int64?) -> String {
        guard let epochMs else { return "—" }
        return dateFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000))
    }
}
