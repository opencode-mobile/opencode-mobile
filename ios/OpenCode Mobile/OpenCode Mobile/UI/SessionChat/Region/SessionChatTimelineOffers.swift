import Foundation

struct SessionChatTimelineOffersRegionState: Equatable {
    // One-time Chip timeline intro (OPE-270): offered after a long turn finishes.
    var chipTimelineIntroVisible = false
    var chipTimelineIntroNotice: String?
    // One-time Timeline Style picker (#805): offered once per chat when history loads.
    var timelineStylePickerVisible = false
    var timelineStylePickerNotice: String?
}

/// The two one-time offers about the timeline style, the notices they leave, and their
/// preference writes. Analytics stay with the Screen Model: the hide calls say whether an
/// offer was showing, so it logs exactly when one closes.
@MainActor
final class SessionChatTimelineOffers {
    var onChange: (() -> Void)?
    /// Session Chat is on screen.
    var isBound: () -> Bool = { false }
    /// Something already open on the chat (`SessionChatUiState.blocksChipTimelineIntro`).
    var isBlocked: () -> Bool = { false }
    /// The published session status shows a turn.
    var isSessionBusy: () -> Bool = { false }

    private(set) var state = SessionChatTimelineOffersRegionState()

    private let clock: any SyncClock
    private let loadedMessages: () -> [Message]
    private var chipTimelineIntroTurnActive: Bool?
    private var chipTimelineIntroTask: Task<Void, Never>?
    private var timelineStylePickerOffered = false
    private var timelineStylePickerTask: Task<Void, Never>?

    static let chipTimelineIntroEnabledNotice = "Chip timeline is on. Turn it off in Settings."
    /// Pause after history loads so the chat is on screen before the picker.
    static let timelineStylePickerDelayMs = 600

    static func timelineStylePickerNotice(_ style: SessionChatTimelineStyle) -> String {
        "Timeline: \(style.title). Change it any time in Settings."
    }

    init(clock: any SyncClock, loadedMessages: @escaping () -> [Message]) {
        self.clock = clock
        self.loadedMessages = loadedMessages
    }

    /// Closes the intro without a preference change. True when it was showing.
    func hideChipTimelineIntro() -> Bool {
        guard state.chipTimelineIntroVisible else { return false }
        state.chipTimelineIntroVisible = false
        return true
    }

    func enableChipTimeline() {
        SessionChatDisplayPreferences.shared.setChipTimeline(true)
        state.chipTimelineIntroNotice = Self.chipTimelineIntroEnabledNotice
        notifyChange()
    }

    /// Later button, swipe, or scrim.
    func postponeChipTimelineIntro() {
        SessionChatDisplayPreferences.shared.postponeChipTimelineIntro(nowMs: clock.nowMs())
        notifyChange()
    }

    func dismissChipTimelineIntro() {
        SessionChatDisplayPreferences.shared.finishChipTimelineIntro()
        notifyChange()
    }

    /// Closes the picker without a preference change. True when it was showing.
    func hideTimelineStylePicker() -> Bool {
        guard state.timelineStylePickerVisible else { return false }
        state.timelineStylePickerVisible = false
        return true
    }

    /// Use button of the Timeline Style picker.
    func chooseTimelineStyle(_ style: SessionChatTimelineStyle) {
        SessionChatDisplayPreferences.shared.setTimelineStyle(style)
        state.timelineStylePickerNotice = Self.timelineStylePickerNotice(style)
        notifyChange()
    }

    /// Swipe, scrim, or back on the Timeline Style picker: the update's default applies.
    func dismissTimelineStylePicker() {
        let preferences = SessionChatDisplayPreferences.shared
        preferences.dismissTimelineStylePicker()
        state.timelineStylePickerNotice = Self.timelineStylePickerNotice(preferences.effectiveTimelineStyle)
        notifyChange()
    }

    var timelineStylePickerVisible: Bool { state.timelineStylePickerVisible }

    func clearNotices() {
        state.chipTimelineIntroNotice = nil
        state.timelineStylePickerNotice = nil
    }

    func reset() {
        chipTimelineIntroTask?.cancel()
        chipTimelineIntroTask = nil
        state.chipTimelineIntroVisible = false
        state.chipTimelineIntroNotice = nil
        chipTimelineIntroTurnActive = nil
        timelineStylePickerTask?.cancel()
        timelineStylePickerTask = nil
        state.timelineStylePickerVisible = false
        timelineStylePickerOffered = false
        state.timelineStylePickerNotice = nil
    }

    /// Called from the Screen Model's publish: mutates silently, publishes only from its task.
    /// A ready history offers the picker; a new or empty chat uses up the offer, so it never
    /// pops mid-turn.
    func observeTimelineLoad(_ timeline: SessionChatTimelineState) {
        switch timeline {
        case .ready:
            offerTimelineStylePickerIfPending()
        case .creating, .empty:
            timelineStylePickerOffered = true
        case .loading, .error:
            break
        }
    }

    /// Offers the picker once, a moment after this chat's history first loads,
    /// while an updated install has not picked a Timeline Style. Anything already
    /// open on the chat skips it until the next session open.
    private func offerTimelineStylePickerIfPending() {
        guard !timelineStylePickerOffered, isBound() else { return }
        let preferences = SessionChatDisplayPreferences.shared
        guard preferences.timelineStylePickerPending else { return }
        timelineStylePickerOffered = true
        timelineStylePickerTask = Task { [clock] in
            await clock.sleep(milliseconds: Self.timelineStylePickerDelayMs)
            guard !Task.isCancelled, isBound(), preferences.timelineStylePickerPending,
                  !state.chipTimelineIntroVisible, !isBlocked() else { return }
            state.timelineStylePickerVisible = true
            notifyChange()
        }
    }

    /// Called from the Screen Model's publish: mutates silently, publishes only from its task.
    /// Offers the intro once a long turn goes busy → idle and stays settled.
    func updateTurnActive(_ isTurnActive: Bool) {
        let previous = chipTimelineIntroTurnActive
        chipTimelineIntroTurnActive = isTurnActive
        if isTurnActive {
            chipTimelineIntroTask?.cancel()
            chipTimelineIntroTask = nil
            return
        }
        guard previous == true, !state.chipTimelineIntroVisible, isBound() else { return }
        let toolCalls = ChipTimelineIntro.lastTurnToolCalls(loadedMessages())
        let preferences = SessionChatDisplayPreferences.shared
        guard preferences.shouldOfferChipTimelineIntro(turnToolCalls: toolCalls, nowMs: clock.nowMs()) else { return }
        chipTimelineIntroTask?.cancel()
        chipTimelineIntroTask = Task { [clock] in
            await clock.sleep(milliseconds: Int(ChipTimelineIntro.settleDelayMs))
            guard !Task.isCancelled, isBound(), !isSessionBusy(), !isBlocked() else { return }
            guard preferences.shouldOfferChipTimelineIntro(turnToolCalls: toolCalls, nowMs: clock.nowMs()) else { return }
            state.chipTimelineIntroVisible = true
            notifyChange()
        }
    }

    private func notifyChange() {
        onChange?()
    }
}
