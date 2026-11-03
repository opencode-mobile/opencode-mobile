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
}

struct SessionContextStatItem: Equatable, Identifiable {
    let id: String
    let label: OpenCodeText
    /// Session content and formatted numbers, shown as is.
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
            let fallback = setRefreshing
                ? String(localized: "session_context.refresh_failed", defaultValue: "Unable to refresh session context")
                : String(localized: "session_context.load_failed", defaultValue: "Unable to load session context")
            let message = errorMessage(error, fallback: fallback)
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
            SessionContextStatItem(id: "session", label: "session_context.stat.session", value: snapshot.sessionTitle),
            SessionContextStatItem(id: "messages", label: "session_context.stat.messages", value: number(snapshot.messageCounts.total)),
            SessionContextStatItem(id: "provider", label: "session_context.stat.provider", value: snapshot.providerLabel ?? "—"),
            SessionContextStatItem(id: "model", label: "session_context.stat.model", value: snapshot.modelLabel ?? "—"),
            SessionContextStatItem(id: "limit", label: "session_context.stat.context_limit", value: number(tokenStats?.contextLimit)),
            SessionContextStatItem(id: "total", label: "session_context.stat.total_tokens", value: number(tokenStats?.total)),
            SessionContextStatItem(id: "usage", label: "session_context.stat.usage", value: percent(tokenStats?.usagePercent)),
            SessionContextStatItem(id: "input", label: "session_context.stat.input_tokens", value: number(tokenStats?.input)),
            SessionContextStatItem(id: "output", label: "session_context.stat.output_tokens", value: number(tokenStats?.output)),
            SessionContextStatItem(id: "reasoning", label: "session_context.stat.reasoning_tokens", value: number(tokenStats?.reasoning)),
            SessionContextStatItem(
                id: "cache",
                label: "session_context.stat.cache_tokens",
                value: "\(number(tokenStats?.cacheRead)) / \(number(tokenStats?.cacheWrite))"
            ),
            SessionContextStatItem(id: "user", label: "session_context.stat.user_messages", value: number(snapshot.messageCounts.user)),
            SessionContextStatItem(id: "assistant", label: "session_context.stat.assistant_messages", value: number(snapshot.messageCounts.assistant)),
            SessionContextStatItem(id: "cost", label: "session_context.stat.total_cost", value: currency(snapshot.totalCost)),
            SessionContextStatItem(id: "created", label: "session_context.stat.created", value: time(snapshot.createdAtEpochMs)),
            SessionContextStatItem(id: "activity", label: "session_context.stat.last_activity", value: time(snapshot.lastActivityEpochMs)),
        ]
        return SessionContextDisplayState(stats: stats, breakdown: snapshot.breakdown)
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
