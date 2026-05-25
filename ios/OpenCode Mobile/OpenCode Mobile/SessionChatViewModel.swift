import Foundation
import Observation

struct SessionChatRow: Identifiable, Equatable {
    let id: String
    let role: MessageRole
    let text: String
    let metadataLabel: String
    let isPending: Bool
}

struct SessionChatComposerState: Equatable {
    var text: String
    var selectedModel: String
    var selectedMode: String
    var selectedThinkingEffort: String
}

enum SessionChatTimelineState: Equatable {
    case loading
    case empty
    case ready
    case error
}

enum SessionChatScreenState: Equatable {
    case loading(sessionID: String, sessionTitle: String, composer: SessionChatComposerState)
    case empty(sessionID: String, sessionTitle: String, composer: SessionChatComposerState)
    case loaded(
        sessionID: String,
        sessionTitle: String,
        messages: [SessionChatRow],
        composer: SessionChatComposerState,
        isSending: Bool,
        isRefreshing: Bool,
        errorBanner: String?
    )
    case error(sessionID: String, sessionTitle: String, message: String, composer: SessionChatComposerState)

    var sessionID: String {
        switch self {
        case let .loading(id, _, _), let .empty(id, _, _), let .loaded(id, _, _, _, _, _, _), let .error(id, _, _, _):
            id
        }
    }

    var sessionTitle: String {
        switch self {
        case let .loading(_, title, _), let .empty(_, title, _), let .loaded(_, title, _, _, _, _, _), let .error(_, title, _, _):
            title
        }
    }

    var composer: SessionChatComposerState {
        switch self {
        case let .loading(_, _, composer), let .empty(_, _, composer), let .loaded(_, _, _, composer, _, _, _), let .error(_, _, _, composer):
            composer
        }
    }
}

@MainActor
@Observable
final class SessionChatViewModel {
    private(set) var state: SessionChatScreenState
    private(set) var sessionStatusLabel: String?
    private let sessionID: String
    private let worktree: String
    private let messageRepository: any MessageRepository
    private let sessionRepository: any SessionRepository
    private let foregroundEventStream: any ForegroundEventStream
    private let eventAdapter = EventDomainAdapter()
    private let sentTimeFormatter: DateFormatter
    private var messageRefreshTask: Task<Void, Never>?
    private var eventObservationTask: Task<Void, Never>?
    private var workingPollTask: Task<Void, Never>?

    private static let workingPollIntervalMs = 2_000

    init(
        sessionID: String,
        sessionTitle: String,
        worktree: String,
        messageRepository: any MessageRepository,
        sessionRepository: any SessionRepository,
        foregroundEventStream: any ForegroundEventStream
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageRepository = messageRepository
        self.sessionRepository = sessionRepository
        self.foregroundEventStream = foregroundEventStream
        self.state = .loading(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            composer: Self.defaultComposer()
        )
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "h:mm a"
        self.sentTimeFormatter = formatter
    }

    func startObserving() {
        observeForegroundEvents()
    }

    func loadMessages() async {
        let sessionID = state.sessionID
        let sessionTitle = state.sessionTitle
        let composer = state.composer
        state = .loading(sessionID: sessionID, sessionTitle: sessionTitle, composer: composer)

        do {
            let messages = try await messageRepository.listMessages(sessionID: sessionID, directory: worktree)
            applyLoadedMessages(messages, sessionID: sessionID, isRefreshing: false)
            await refreshSessionStatus()
        } catch {
            applyError(error, sessionID: sessionID)
        }
    }

    func refreshMessages() async {
        let sessionID = state.sessionID
        let sessionTitle = state.sessionTitle
        let composer = state.composer

        if case let .loaded(_, _, messages, _, isSending, _, _) = state {
            state = .loaded(
                sessionID: sessionID,
                sessionTitle: sessionTitle,
                messages: messages,
                composer: composer,
                isSending: isSending,
                isRefreshing: true,
                errorBanner: nil
            )
        }

        do {
            let messages = try await messageRepository.listMessages(sessionID: sessionID, directory: worktree)
            applyLoadedMessages(messages, sessionID: sessionID, isRefreshing: false)
        } catch {
            if case let .loaded(_, _, messages, _, _, _, _) = state {
                state = .loaded(
                    sessionID: sessionID,
                    sessionTitle: sessionTitle,
                    messages: messages,
                    composer: composer,
                    isSending: false,
                    isRefreshing: false,
                    errorBanner: errorMessage(error)
                )
            }
        }
    }

    func updateComposerText(_ text: String) {
        updateComposer { $0.text = text }
    }

    func selectModel(_ model: String) {
        guard SessionChatPickers.models.contains(model) else { return }
        updateComposer { $0.selectedModel = model }
    }

    func selectMode(_ mode: String) {
        guard SessionChatPickers.modes.contains(mode) else { return }
        updateComposer { $0.selectedMode = mode }
    }

    func selectThinkingEffort(_ effort: String) {
        guard SessionChatPickers.thinkingEfforts.contains(effort) else { return }
        updateComposer { $0.selectedThinkingEffort = effort }
    }

    func restoreComposerText(_ text: String) {
        updateComposer { $0.text = text }
    }

    func sendPrompt() async {
        let trimmed = state.composer.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        let sessionID = state.sessionID
        let sessionTitle = state.sessionTitle
        var composer = state.composer
        composer.text = ""

        let optimistic = SessionChatRow(
            id: "pending-\(UUID().uuidString)",
            role: .user,
            text: trimmed,
            metadataLabel: metadataLabel(mode: composer.selectedMode, model: composer.selectedModel),
            isPending: true
        )

        let existing = messagesFromCurrentState()
        state = .loaded(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            messages: existing + [optimistic],
            composer: composer,
            isSending: true,
            isRefreshing: false,
            errorBanner: nil
        )

        do {
            try await messageRepository.sendPromptAsync(sessionID: sessionID, text: trimmed, directory: worktree)
            await refreshSessionStatus()
            do {
                let messages = try await messageRepository.listMessages(sessionID: sessionID, directory: worktree)
                applyLoadedMessages(messages, sessionID: sessionID, isRefreshing: false)
            } catch {
                // Prompt was accepted; SSE or pull-to-refresh will sync the timeline.
                if case let .loaded(id, title, messages, composer, _, _, _) = state {
                    state = .loaded(
                        sessionID: id,
                        sessionTitle: title,
                        messages: messages,
                        composer: composer,
                        isSending: false,
                        isRefreshing: false,
                        errorBanner: nil
                    )
                }
            }
        } catch {
            if case let .loaded(id, title, messages, composer, _, _, _) = state {
                state = .loaded(
                    sessionID: id,
                    sessionTitle: title,
                    messages: messages.filter { $0.id != optimistic.id },
                    composer: composer,
                    isSending: false,
                    isRefreshing: false,
                    errorBanner: errorMessage(error)
                )
            } else {
                applyError(error, sessionID: sessionID)
            }
        }
    }

    private func applyLoadedMessages(
        _ messages: [Message],
        sessionID: String,
        isRefreshing: Bool,
        fromQuietRefresh: Bool = false
    ) {
        let sessionTitle = state.sessionTitle
        let composer = state.composer
        let isSending = fromQuietRefresh ? isSendingFromCurrentState() : false
        let errorBanner = fromQuietRefresh ? errorBannerFromCurrentState() : nil
        let serverRows =
            messages
                .sorted {
                    let leftTime = $0.sentAtEpochMs ?? Int64.max
                    let rightTime = $1.sentAtEpochMs ?? Int64.max
                    if leftTime != rightTime { return leftTime < rightTime }
                    return $0.id < $1.id
                }
                .map(row(from:))
        let rows =
            if fromQuietRefresh, isSending {
                mergeWithPending(serverRows, pendingRowsFromCurrentState())
            } else {
                serverRows
            }
        if rows.isEmpty {
            state = .empty(sessionID: sessionID, sessionTitle: sessionTitle, composer: composer)
        } else {
            state = .loaded(
                sessionID: sessionID,
                sessionTitle: sessionTitle,
                messages: rows,
                composer: composer,
                isSending: fromQuietRefresh ? isSending : false,
                isRefreshing: isRefreshing,
                errorBanner: fromQuietRefresh ? errorBanner : nil
            )
        }
    }

    private func mergeWithPending(_ serverRows: [SessionChatRow], _ pendingRows: [SessionChatRow]) -> [SessionChatRow] {
        guard !pendingRows.isEmpty else { return serverRows }
        let pendingToKeep =
            pendingRows.filter { pending in
                !serverRows.contains(where: { $0.role == .user && $0.text == pending.text })
            }
        return serverRows + pendingToKeep
    }

    private func pendingRowsFromCurrentState() -> [SessionChatRow] {
        if case let .loaded(_, _, messages, _, _, _, _) = state {
            return messages.filter(\.isPending)
        }
        return []
    }

    private func isSendingFromCurrentState() -> Bool {
        if case let .loaded(_, _, _, _, isSending, _, _) = state {
            return isSending
        }
        return false
    }

    private func errorBannerFromCurrentState() -> String? {
        if case let .loaded(_, _, _, _, _, _, errorBanner) = state {
            return errorBanner
        }
        return nil
    }

    private func applyError(_ error: Error, sessionID: String) {
        state = .error(
            sessionID: sessionID,
            sessionTitle: state.sessionTitle,
            message: errorMessage(error),
            composer: state.composer
        )
    }

    private func row(from message: Message) -> SessionChatRow {
        SessionChatRow(
            id: message.id,
            role: message.role,
            text: message.text,
            metadataLabel: metadataLabel(
                mode: message.mode,
                model: message.model,
                sentAtEpochMs: message.sentAtEpochMs
            ),
            isPending: false
        )
    }

    private func metadataLabel(mode: String?, model: String?, sentAtEpochMs: Int64? = nil) -> String {
        let timeLabel = sentAtEpochMs.map { sentTimeFormatter.string(from: Date(timeIntervalSince1970: TimeInterval($0) / 1000)) }
        return [mode, model, timeLabel].compactMap { $0 }.joined(separator: " · ")
    }

    private func messagesFromCurrentState() -> [SessionChatRow] {
        if case let .loaded(_, _, messages, _, _, _, _) = state {
            return messages
        }
        return []
    }

    private func updateComposer(_ update: (inout SessionChatComposerState) -> Void) {
        var composer = state.composer
        update(&composer)
        switch state {
        case let .loading(id, title, _):
            state = .loading(sessionID: id, sessionTitle: title, composer: composer)
        case let .empty(id, title, _):
            state = .empty(sessionID: id, sessionTitle: title, composer: composer)
        case let .loaded(id, title, messages, _, isSending, isRefreshing, errorBanner):
            state = .loaded(
                sessionID: id,
                sessionTitle: title,
                messages: messages,
                composer: composer,
                isSending: isSending,
                isRefreshing: isRefreshing,
                errorBanner: errorBanner
            )
        case let .error(id, title, message, _):
            state = .error(sessionID: id, sessionTitle: title, message: message, composer: composer)
        }
    }

    private func errorMessage(_ error: Error) -> String {
        if let messageError = error as? MessageError {
            return messageError.localizedDescription
        }
        return error.localizedDescription
    }

    private static func defaultComposer() -> SessionChatComposerState {
        SessionChatComposerState(
            text: "",
            selectedModel: SessionChatPickers.models[0],
            selectedMode: SessionChatPickers.modes[0],
            selectedThinkingEffort: SessionChatPickers.thinkingEfforts[0]
        )
    }

    private func observeForegroundEvents() {
        eventObservationTask?.cancel()
        eventObservationTask = Task {
            for await event in foregroundEventStream.events() {
                switch event.type {
                case "stream.reconnected":
                    await refreshMessagesQuietly()
                    await refreshSessionStatus()
                default:
                    if event.triggersMessageRefresh, event.sessionID == sessionID {
                        scheduleMessageRefresh()
                    }
                    if event.triggersStatusRefresh, event.sessionID == sessionID {
                        await refreshSessionStatus()
                    }
                }
            }
        }
    }

    private func scheduleMessageRefresh() {
        messageRefreshTask?.cancel()
        messageRefreshTask = Task {
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            await refreshMessagesQuietly()
        }
    }

    private func refreshMessagesQuietly() async {
        do {
            let messages = try await messageRepository.listMessages(sessionID: sessionID, directory: worktree)
            applyLoadedMessages(messages, sessionID: sessionID, isRefreshing: false, fromQuietRefresh: true)
        } catch {
            return
        }
    }

    func stopObserving() {
        eventObservationTask?.cancel()
        eventObservationTask = nil
        messageRefreshTask?.cancel()
        messageRefreshTask = nil
        workingPollTask?.cancel()
        workingPollTask = nil
    }

    private func refreshSessionStatus() async {
        guard let statuses = try? await sessionRepository.getAllSessionStatuses(directory: worktree) else { return }
        let status = statuses.first(where: { $0.sessionID == sessionID })
        sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
        updateWorkingPoll(isBusy: status?.raw["type"] == "busy")
    }

    private func updateWorkingPoll(isBusy: Bool) {
        if isBusy {
            guard workingPollTask == nil else { return }
            workingPollTask = Task {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(Self.workingPollIntervalMs))
                    guard !Task.isCancelled else { return }
                    await refreshMessagesQuietly()
                }
            }
        } else {
            workingPollTask?.cancel()
            workingPollTask = nil
        }
    }
}

enum SessionChatPickers {
    static let models = ["DeepSeek V4 Flash", "GPT-5 Preview", "Claude Sonnet"]
    static let modes = ["Build", "Plan"]
    static let thinkingEfforts = ["Default", "Low", "Medium", "High"]
}
