import Foundation

struct SessionChatTimelineOffersRegionState: Equatable {
    // One-time Timeline Style picker (#805): offered once per chat when history loads.
    var timelineStylePickerVisible = false
    var timelineStylePickerNotice: String?
}

/// The one-time Timeline Style picker, the notice it leaves, and its preference writes.
/// Analytics stay with the Screen Model: the hide call says whether the picker was
/// showing, so it logs exactly when it closes.
@MainActor
final class SessionChatTimelineOffers {
    var onChange: (() -> Void)?
    /// Session Chat is on screen.
    var isBound: () -> Bool = { false }
    /// Something already open on the chat (`SessionChatUiState.blocksTimelineStylePicker`).
    var isBlocked: () -> Bool = { false }

    private(set) var state = SessionChatTimelineOffersRegionState()

    private let clock: any SyncClock
    private var timelineStylePickerOffered = false
    private var timelineStylePickerTask: Task<Void, Never>?

    /// Pause after history loads so the chat is on screen before the picker.
    static let timelineStylePickerDelayMs = 600

    /// "Timeline: Step Rail. Change it any time in Settings.", with the keyed style name.
    static func timelineStylePickerNotice(_ style: SessionChatTimelineStyle) -> String {
        let title = style.settingsTitle.resolved
        return String(localized: "session_chat.timeline_style_notice", defaultValue: "Timeline: \(title). Change it any time in Settings.")
    }

    init(clock: any SyncClock) {
        self.clock = clock
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
        state.timelineStylePickerNotice = Self.timelineStylePickerNotice(preferences.timelineStyle)
        notifyChange()
    }

    var timelineStylePickerVisible: Bool { state.timelineStylePickerVisible }

    func clearNotices() {
        state.timelineStylePickerNotice = nil
    }

    func reset() {
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
                  !isBlocked() else { return }
            state.timelineStylePickerVisible = true
            notifyChange()
        }
    }

    private func notifyChange() {
        onChange?()
    }
}
