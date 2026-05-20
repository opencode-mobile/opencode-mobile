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
    private let messageRepository: any MessageRepository
    private let sentTimeFormatter: DateFormatter

    init(sessionID: String, sessionTitle: String, messageRepository: any MessageRepository) {
        self.messageRepository = messageRepository
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

    func loadMessages() async {
        let sessionID = state.sessionID
        let sessionTitle = state.sessionTitle
        let composer = state.composer
        state = .loading(sessionID: sessionID, sessionTitle: sessionTitle, composer: composer)

        do {
            let messages = try await messageRepository.listMessages(sessionID: sessionID)
            applyLoadedMessages(messages, sessionID: sessionID, isRefreshing: false)
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
            let messages = try await messageRepository.listMessages(sessionID: sessionID)
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
            try await messageRepository.sendPromptAsync(sessionID: sessionID, text: trimmed)
            let messages = try await messageRepository.listMessages(sessionID: sessionID)
            applyLoadedMessages(messages, sessionID: sessionID, isRefreshing: false)
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

    private func applyLoadedMessages(_ messages: [Message], sessionID: String, isRefreshing: Bool) {
        let sessionTitle = state.sessionTitle
        let composer = state.composer
        let rows = messages.map(row(from:))
        if rows.isEmpty {
            state = .empty(sessionID: sessionID, sessionTitle: sessionTitle, composer: composer)
        } else {
            state = .loaded(
                sessionID: sessionID,
                sessionTitle: sessionTitle,
                messages: rows,
                composer: composer,
                isSending: false,
                isRefreshing: isRefreshing,
                errorBanner: nil
            )
        }
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
}

enum SessionChatPickers {
    static let models = ["DeepSeek V4 Flash", "GPT-5 Preview", "Claude Sonnet"]
    static let modes = ["Build", "Plan"]
    static let thinkingEfforts = ["Default", "Low", "Medium", "High"]
}
