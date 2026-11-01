import Foundation
import Observation

/// How Session Chat presents an assistant turn (#779). Wire values are stored.
nonisolated enum SessionChatTimelineStyle: String, CaseIterable, Sendable {
    /// Tool cards and tool groups between the agent's messages.
    case standard
    /// Narration paragraphs with the tools each one used as chips (OPE-260).
    case chips
    /// One short line per step on a vertical rail with live status.
    case stepRail = "step_rail"
    /// Running steps on one live card per segment that settles into a summary line.
    case activityCard = "activity_card"

    var wireValue: String { rawValue }

    static func fromWire(_ value: String?) -> SessionChatTimelineStyle? {
        value.flatMap(SessionChatTimelineStyle.init(rawValue:))
    }

    var title: String {
        switch self {
        case .standard: "Standard"
        case .chips: "Chips"
        case .stepRail: "Step Rail"
        case .activityCard: "Activity Card"
        }
    }

    var subtitle: String {
        switch self {
        case .standard: "Tool cards and groups between the agent's messages"
        case .chips: "Tools as chips under the agent's narration"
        case .stepRail: "One line per step on a rail, live while the agent works"
        case .activityCard: "Running steps on one live card that folds into a summary"
        }
    }
}

/// Session Chat display preferences (OPE-144).
/// Device-local, default on, cleared by Clear All Data.
@Observable
nonisolated final class SessionChatDisplayPreferences {
    static let shared = SessionChatDisplayPreferences()
    static let defaultCollapseConsecutiveToolCalls = true
    static let defaultChipTimeline = false
    static let defaultTimelineStyle = SessionChatTimelineStyle.standard
    static let defaultFollowUpDelivery = FollowUpDelivery.steer

    private enum StorageKeys {
        static let collapseConsecutiveToolCalls = "collapseConsecutiveToolCalls"
        /// Legacy boolean (OPE-260). Read once to migrate; still mirrored on writes.
        static let chipTimeline = "chipTimeline"
        static let timelineStyle = "timelineStyle"
        static let followUpDelivery = "followUpDelivery"
        static let chipTimelineIntroDone = "chipTimelineIntroDone"
        static let chipTimelineIntroSnoozedUntilMs = "chipTimelineIntroSnoozedUntilMs"
        static let timelineStylePickerDone = "timelineStylePickerDone"
    }

    private let defaults: UserDefaults
    private let featureFlags: FeatureFlags

    /// Values to put back when the last sandbox ends; set while one is open.
    @ObservationIgnored private var sandboxSnapshot: Snapshot?
    @ObservationIgnored private var sandboxDepth = 0

    private struct Snapshot {
        let collapseConsecutiveToolCalls: Bool
        let timelineStyle: SessionChatTimelineStyle
        let chipTimelineIntroDone: Bool
        let chipTimelineIntroSnoozedUntilMs: Int64?
        let timelineStylePickerDone: Bool?
        let followUpDelivery: FollowUpDelivery
    }

    /// True while a `beginSandbox()` handle is open.
    var isSandboxed: Bool { sandboxDepth > 0 }

    /// When true, a run of two or more consecutive visible tool parts in one
    /// assistant message collapses into one summary tool group.
    private(set) var collapseConsecutiveToolCalls: Bool

    /// Stored Timeline Style, independent of the remote flag.
    private(set) var timelineStyle: SessionChatTimelineStyle

    /// Stored "Chip timeline" choice, independent of the remote flag.
    var chipTimelinePreference: Bool {
        timelineStyle == .chips
    }

    /// True once the Chip timeline intro sheet has been answered for good (OPE-270).
    private(set) var chipTimelineIntroDone: Bool

    /// Epoch ms until which a Later answer hides the intro sheet, or nil if never snoozed.
    private(set) var chipTimelineIntroSnoozedUntilMs: Int64?

    /// True while the `session_chat_chip_timeline` flag allows the mode at all (OPE-269).
    var chipTimelineAvailable: Bool {
        featureFlags.isEnabled(.sessionChatChipTimeline)
    }

    /// When true, Session Chat shows an assistant turn as narration paragraphs
    /// with the tools each one used as chips (OPE-260). Effective value: the
    /// stored preference gated by `chipTimelineAvailable`; a remotely disabled
    /// flag renders the standard timeline regardless of the stored preference.
    var chipTimeline: Bool {
        timelineStyle == .chips && chipTimelineAvailable
    }

    /// When true, Session Chat shows an assistant turn as a step rail (#779). Not flag-gated.
    var stepRailTimeline: Bool {
        timelineStyle == .stepRail
    }

    /// When true, Session Chat shows each run of steps as one activity card (#779). Not flag-gated.
    var activityCardTimeline: Bool {
        timelineStyle == .activityCard
    }

    /// The style Session Chat renders: Chips falls back to Standard while the flag is off.
    var effectiveTimelineStyle: SessionChatTimelineStyle {
        timelineStyle == .chips && !chipTimelineAvailable ? .standard : timelineStyle
    }

    /// Styles the Settings picker offers; Chips only while its flag allows it.
    var availableTimelineStyles: [SessionChatTimelineStyle] {
        SessionChatTimelineStyle.allCases.filter { $0 != .chips || chipTimelineAvailable }
    }

    /// One-time Timeline Style picker (#805): nil until this version settles it,
    /// false while an updated install still has to pick, true once answered.
    private(set) var timelineStylePickerDone: Bool?

    /// True while an updated install has not answered the Timeline Style picker.
    var timelineStylePickerPending: Bool {
        !isSandboxed && timelineStylePickerDone == false
    }

    private(set) var followUpDelivery: FollowUpDelivery

    init(defaults: UserDefaults = .standard, featureFlags: FeatureFlags = .shared) {
        self.defaults = defaults
        self.featureFlags = featureFlags
        if defaults.object(forKey: StorageKeys.collapseConsecutiveToolCalls) == nil {
            collapseConsecutiveToolCalls = Self.defaultCollapseConsecutiveToolCalls
        } else {
            collapseConsecutiveToolCalls = defaults.bool(forKey: StorageKeys.collapseConsecutiveToolCalls)
        }
        if let stored = SessionChatTimelineStyle.fromWire(defaults.string(forKey: StorageKeys.timelineStyle)) {
            timelineStyle = stored
        } else if defaults.object(forKey: StorageKeys.chipTimeline) != nil {
            // Migrate the old Chip timeline boolean once; the old key stays.
            let migrated: SessionChatTimelineStyle =
                defaults.bool(forKey: StorageKeys.chipTimeline) ? .chips : .standard
            timelineStyle = migrated
            defaults.set(migrated.wireValue, forKey: StorageKeys.timelineStyle)
        } else {
            timelineStyle = Self.defaultTimelineStyle
        }
        followUpDelivery = FollowUpDelivery.fromWire(defaults.string(forKey: StorageKeys.followUpDelivery))
            ?? Self.defaultFollowUpDelivery
        chipTimelineIntroDone = defaults.bool(forKey: StorageKeys.chipTimelineIntroDone)
        chipTimelineIntroSnoozedUntilMs =
            (defaults.object(forKey: StorageKeys.chipTimelineIntroSnoozedUntilMs) as? NSNumber)?.int64Value
        timelineStylePickerDone = (defaults.object(forKey: StorageKeys.timelineStylePickerDone) as? NSNumber)?.boolValue
    }

    func setCollapseConsecutiveToolCalls(_ value: Bool) {
        collapseConsecutiveToolCalls = value
        persist { $0.set(value, forKey: StorageKeys.collapseConsecutiveToolCalls) }
    }

    /// Intro sheet shim: true picks Chips, false Standard. Ends the intro.
    func setChipTimeline(_ value: Bool) {
        setTimelineStyle(value ? .chips : .standard)
    }

    /// Any explicit choice, from Settings, the picker, or the intro sheet, also
    /// ends the Chip intro and the Timeline Style picker.
    func setTimelineStyle(_ style: SessionChatTimelineStyle) {
        storeTimelineStyle(style)
        finishChipTimelineIntro()
        storeTimelineStylePickerDone(true)
    }

    /// Picker closed without a choice: the update's default applies.
    func dismissTimelineStylePicker() {
        setTimelineStyle(.activityCard)
    }

    private func storeTimelineStylePickerDone(_ done: Bool) {
        timelineStylePickerDone = done
        persist { $0.set(done, forKey: StorageKeys.timelineStylePickerDone) }
    }

    private func storeTimelineStyle(_ style: SessionChatTimelineStyle) {
        timelineStyle = style
        persist { defaults in
            defaults.set(style.wireValue, forKey: StorageKeys.timelineStyle)
            // Mirror the legacy key so an older build reading it agrees on Chips.
            defaults.set(style == .chips, forKey: StorageKeys.chipTimeline)
        }
    }

    /// Settles the starting Timeline Style and the Timeline Style picker once
    /// per install (#805). `existingInstall` is true when the app opens past App
    /// onboarding. A new install starts on Activity Card and never sees the
    /// picker; an existing one keeps its style and is offered the picker. Stored
    /// values, from an earlier launch or a Settings choice, always win.
    func resolveTimelineForInstall(existingInstall: Bool) {
        if defaults.object(forKey: StorageKeys.timelineStyle) == nil,
           defaults.object(forKey: StorageKeys.chipTimeline) == nil
        {
            storeTimelineStyle(existingInstall ? .standard : .activityCard)
        }
        if timelineStylePickerDone == nil {
            storeTimelineStylePickerDone(!existingInstall)
        }
    }

    /// Intro sheet "Don't show again", or a second Later.
    func finishChipTimelineIntro() {
        chipTimelineIntroDone = true
        persist { $0.set(true, forKey: StorageKeys.chipTimelineIntroDone) }
    }

    /// Intro sheet Later: snooze the first time, end the intro the second time.
    func postponeChipTimelineIntro(nowMs: Int64) {
        guard let until = ChipTimelineIntro.laterSnoozeUntil(
            snoozedUntilMs: chipTimelineIntroSnoozedUntilMs,
            nowMs: nowMs
        ) else {
            finishChipTimelineIntro()
            return
        }
        chipTimelineIntroSnoozedUntilMs = until
        persist { $0.set(NSNumber(value: until), forKey: StorageKeys.chipTimelineIntroSnoozedUntilMs) }
    }

    /// Whether a finished turn with `turnToolCalls` tool calls should offer the intro sheet.
    func shouldOfferChipTimelineIntro(turnToolCalls: Int, nowMs: Int64) -> Bool {
        // The Timeline Style picker replaces the intro (#805); never pitch Chips before it.
        guard !isSandboxed, !timelineStylePickerPending else { return false }
        return ChipTimelineIntro.shouldShow(
            chipTimelineAvailable: chipTimelineAvailable,
            // Step Rail and Activity Card are explicit style choices too: never pitch Chips over them.
            chipTimelineOn: timelineStyle != .standard,
            introDone: chipTimelineIntroDone,
            snoozedUntilMs: chipTimelineIntroSnoozedUntilMs,
            nowMs: nowMs,
            turnToolCalls: turnToolCalls
        )
    }

    func setFollowUpDelivery(_ value: FollowUpDelivery) {
        followUpDelivery = value
        persist { $0.set(value.wireValue, forKey: StorageKeys.followUpDelivery) }
    }

    /// Keeps every change in memory until the returned handle ends, then restores the values
    /// from before. While a sandbox is open the one-time Timeline Style picker and Chip intro
    /// are not offered. The Session Chat Playground holds one: nothing it does may change the
    /// real preferences.
    func beginSandbox() -> Sandbox {
        if sandboxDepth == 0 {
            sandboxSnapshot = Snapshot(
                collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
                timelineStyle: timelineStyle,
                chipTimelineIntroDone: chipTimelineIntroDone,
                chipTimelineIntroSnoozedUntilMs: chipTimelineIntroSnoozedUntilMs,
                timelineStylePickerDone: timelineStylePickerDone,
                followUpDelivery: followUpDelivery
            )
        }
        sandboxDepth += 1
        return Sandbox(preferences: self)
    }

    private func endSandbox() {
        sandboxDepth -= 1
        guard sandboxDepth == 0, let snapshot = sandboxSnapshot else { return }
        sandboxSnapshot = nil
        collapseConsecutiveToolCalls = snapshot.collapseConsecutiveToolCalls
        timelineStyle = snapshot.timelineStyle
        chipTimelineIntroDone = snapshot.chipTimelineIntroDone
        chipTimelineIntroSnoozedUntilMs = snapshot.chipTimelineIntroSnoozedUntilMs
        timelineStylePickerDone = snapshot.timelineStylePickerDone
        followUpDelivery = snapshot.followUpDelivery
    }

    /// An open sandbox; `end()` restores the preferences once, later calls do nothing.
    final class Sandbox {
        private var preferences: SessionChatDisplayPreferences?

        fileprivate init(preferences: SessionChatDisplayPreferences) {
            self.preferences = preferences
        }

        func end() {
            preferences?.endSandbox()
            preferences = nil
        }
    }

    private func persist(_ write: (UserDefaults) -> Void) {
        guard !isSandboxed else { return }
        write(defaults)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.collapseConsecutiveToolCalls)
        defaults.removeObject(forKey: StorageKeys.chipTimeline)
        defaults.removeObject(forKey: StorageKeys.timelineStyle)
        defaults.removeObject(forKey: StorageKeys.followUpDelivery)
        defaults.removeObject(forKey: StorageKeys.chipTimelineIntroDone)
        defaults.removeObject(forKey: StorageKeys.chipTimelineIntroSnoozedUntilMs)
        defaults.removeObject(forKey: StorageKeys.timelineStylePickerDone)
        collapseConsecutiveToolCalls = Self.defaultCollapseConsecutiveToolCalls
        timelineStyle = Self.defaultTimelineStyle
        followUpDelivery = Self.defaultFollowUpDelivery
        chipTimelineIntroDone = false
        chipTimelineIntroSnoozedUntilMs = nil
        timelineStylePickerDone = nil
    }
}
