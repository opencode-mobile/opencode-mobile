import Foundation

/// One `/btw` answer shown in the answer sheet.
struct SessionChatSideQuestionAnswer: Equatable, Identifiable {
    let id: UUID
    let question: String
    let answer: String
}

struct SessionChatSideQuestionsUiState: Equatable {
    /// The server can answer `/btw`; gates the Btw tile and `/btw` parsing.
    var supported = false
    /// The composer asks a side question on Send (the `/btw` pill).
    var composerMode = false
    var pendingCount = 0
    /// Answers waiting behind the "answer ready" chip.
    var readyCount = 0
    var failedCount = 0
    /// The answer sheet's content; nil while it is closed.
    var presentedAnswer: SessionChatSideQuestionAnswer?
    /// Shown above the composer when the draft is a `/btw` this server cannot answer.
    var unsupportedNotice: String?
}

/// A one-shot snackbar from a `/btw` request.
struct SessionChatSideQuestionNotice: Equatable {
    let id: UInt64
    let message: String
    let isError: Bool
    /// The failed question Retry asks again; nil when asking again cannot work.
    let retryID: UUID?
}

/// `/btw` side questions in Session Chat. Requests and unread answers live in the
/// connection's `SideQuestionStore`, so they outlive this screen; this region only decides what
/// the screen shows. An answer that arrives while the screen is up opens the answer sheet (or
/// waits behind the one already open); answers that arrived while away wait as a chip.
@MainActor
final class SessionChatSideQuestions {
    static let unsupportedNotice =
        "/btw needs an opencode v2 server that answers side questions. This server can't answer without adding to the conversation."
    static let unavailableNotice = "/btw needs a newer opencode server."
    static let copiedNotice = "Answer copied"

    static func failedNotice(question: String) -> String {
        "/btw failed: “\(question)”"
    }

    var onChange: (() -> Void)?
    private(set) var state = SessionChatSideQuestionsUiState()
    private(set) var notice: SessionChatSideQuestionNotice?

    private var sessionID: String
    private let store: SideQuestionStore
    private let supported: () -> Bool
    /// False while another sheet or dialog is up, so a new answer waits instead of stacking.
    var canPresentAnswer: () -> Bool = { true }
    private var observation: UUID?
    private var presentedID: UUID?
    /// Answers that arrived while a sheet was open, oldest first.
    private var queuedIDs: [UUID] = []
    private var noticeSequence: UInt64 = 0

    init(sessionID: String, store: SideQuestionStore, supported: @escaping () -> Bool) {
        self.sessionID = sessionID
        self.store = store
        self.supported = supported
        refresh(notify: false)
    }

    func rebind(sessionID: String) {
        self.sessionID = sessionID
        refresh()
    }

    func start() {
        if observation == nil {
            observation = store.observe { [weak self] event in self?.handle(event) }
        }
        refresh()
    }

    /// Leaving Session Chat: an answer on screen was read, so it is forgotten; requests and
    /// unread answers stay in the store.
    func stop() {
        if let observation {
            store.stopObserving(observation)
        }
        observation = nil
        queuedIDs = []
        state.composerMode = false
        notice = nil
        if let presentedID {
            self.presentedID = nil
            store.remove(presentedID)
        }
        refresh(notify: false)
    }

    func enterMode() {
        guard supported(), !state.composerMode else { return }
        state.composerMode = true
        onChange?()
    }

    func exitMode() {
        guard state.composerMode else { return }
        state.composerMode = false
        onChange?()
    }

    /// Sends one `generate` request and leaves side-question mode. False when nothing was asked.
    @discardableResult
    func ask(_ question: String) -> Bool {
        guard supported(), store.ask(sessionID: sessionID, question: question) != nil else { return false }
        state.composerMode = false
        refresh()
        return true
    }

    /// The "answer ready" chip: opens the oldest unread answer.
    func openReadyAnswer() {
        guard presentedID == nil,
              let entry = store.entries(sessionID: sessionID).first(where: { $0.answer != nil })
        else { return }
        queuedIDs.removeAll { $0 == entry.id }
        present(entry.id)
    }

    /// Closing the sheet forgets its answer, then opens the next one that arrived meanwhile.
    func closeAnswer() {
        guard let id = presentedID else { return }
        presentedID = nil
        store.remove(id)
        while let next = queuedIDs.first {
            queuedIDs.removeFirst()
            if store.entry(id: next)?.answer != nil {
                present(next)
                return
            }
        }
        refresh()
    }

    func answerCopied() {
        post(SessionChatSideQuestionNotice(id: nextNoticeID(), message: Self.copiedNotice, isError: false, retryID: nil))
    }

    func retry(_ id: UUID) {
        if notice?.retryID == id {
            notice = nil
        }
        store.retry(id)
    }

    /// The failed chip: asks every failed question again.
    func retryFailed() {
        notice = nil
        for entry in store.entries(sessionID: sessionID) where entry.status == .failed {
            store.retry(entry.id)
        }
    }

    func dismissFailed() {
        notice = nil
        for entry in store.entries(sessionID: sessionID) where entry.status == .failed {
            store.remove(entry.id)
        }
    }

    func clearNotice() {
        notice = nil
    }

    /// The composer draft changed: say when a typed `/btw` cannot be asked here.
    func updateDraft(_ text: String) {
        let notice = !supported() && SideQuestion.question(inDraft: text) != nil ? Self.unsupportedNotice : nil
        guard notice != state.unsupportedNotice else { return }
        state.unsupportedNotice = notice
        onChange?()
    }

    private func handle(_ event: SideQuestionEvent) {
        switch event {
        case let .changed(sessionID):
            guard sessionID == self.sessionID else { return }
            refresh()
        case let .answered(sessionID, id):
            guard sessionID == self.sessionID else { return }
            if presentedID == nil, canPresentAnswer() {
                present(id)
            } else {
                queuedIDs.append(id)
            }
        case let .failed(sessionID, id, question, retryable, _):
            guard sessionID == self.sessionID else { return }
            post(SessionChatSideQuestionNotice(
                id: nextNoticeID(),
                message: retryable ? Self.failedNotice(question: question) : Self.unavailableNotice,
                isError: true,
                retryID: retryable ? id : nil
            ))
        }
    }

    private func present(_ id: UUID) {
        presentedID = id
        refresh()
    }

    private func post(_ notice: SessionChatSideQuestionNotice) {
        self.notice = notice
        onChange?()
    }

    private func nextNoticeID() -> UInt64 {
        noticeSequence += 1
        return noticeSequence
    }

    private func refresh(notify: Bool = true) {
        let entries = store.entries(sessionID: sessionID)
        if let presentedID, entries.first(where: { $0.id == presentedID })?.answer == nil {
            self.presentedID = nil
        }
        state.supported = supported()
        if !state.supported {
            state.composerMode = false
        }
        state.pendingCount = entries.count { $0.status == .pending }
        state.readyCount = entries.count { $0.answer != nil && $0.id != presentedID }
        state.failedCount = entries.count { $0.status == .failed }
        state.presentedAnswer = presentedID.flatMap { id in
            entries.first { $0.id == id }.flatMap { entry in
                entry.answer.map { SessionChatSideQuestionAnswer(id: entry.id, question: entry.question, answer: $0) }
            }
        }
        if notify {
            onChange?()
        }
    }
}
