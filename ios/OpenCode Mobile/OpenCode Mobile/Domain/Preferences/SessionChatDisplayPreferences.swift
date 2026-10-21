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
    }

    private let defaults: UserDefaults
    private let featureFlags: FeatureFlags

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
    }

    func setCollapseConsecutiveToolCalls(_ value: Bool) {
        collapseConsecutiveToolCalls = value
        defaults.set(value, forKey: StorageKeys.collapseConsecutiveToolCalls)
    }

    /// Intro sheet shim: true picks Chips, false Standard. Ends the intro.
    func setChipTimeline(_ value: Bool) {
        setTimelineStyle(value ? .chips : .standard)
    }

    /// Any explicit choice, from Settings or the intro sheet, also ends the intro.
    func setTimelineStyle(_ style: SessionChatTimelineStyle) {
        storeTimelineStyle(style)
        finishChipTimelineIntro()
    }

    private func storeTimelineStyle(_ style: SessionChatTimelineStyle) {
        timelineStyle = style
        defaults.set(style.wireValue, forKey: StorageKeys.timelineStyle)
        // Mirror the legacy key so an older build reading it agrees on Chips.
        defaults.set(style == .chips, forKey: StorageKeys.chipTimeline)
    }

    /// Settles the starting Chip timeline value once per install (OPE-270).
    /// `existingInstall` is true when the app opens past App onboarding. A
    /// stored value, from an earlier launch or a Settings choice, always wins.
    func resolveChipTimelineForInstall(existingInstall: Bool) {
        guard defaults.object(forKey: StorageKeys.timelineStyle) == nil,
              defaults.object(forKey: StorageKeys.chipTimeline) == nil
        else { return }
        let value = ChipTimelineIntro.startingChipTimeline(existingInstall: existingInstall)
        storeTimelineStyle(value ? .chips : .standard)
    }

    /// Intro sheet "Don't show again", or a second Later.
    func finishChipTimelineIntro() {
        chipTimelineIntroDone = true
        defaults.set(true, forKey: StorageKeys.chipTimelineIntroDone)
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
        defaults.set(NSNumber(value: until), forKey: StorageKeys.chipTimelineIntroSnoozedUntilMs)
    }

    /// Whether a finished turn with `turnToolCalls` tool calls should offer the intro sheet.
    func shouldOfferChipTimelineIntro(turnToolCalls: Int, nowMs: Int64) -> Bool {
        ChipTimelineIntro.shouldShow(
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
        defaults.set(value.wireValue, forKey: StorageKeys.followUpDelivery)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.collapseConsecutiveToolCalls)
        defaults.removeObject(forKey: StorageKeys.chipTimeline)
        defaults.removeObject(forKey: StorageKeys.timelineStyle)
        defaults.removeObject(forKey: StorageKeys.followUpDelivery)
        defaults.removeObject(forKey: StorageKeys.chipTimelineIntroDone)
        defaults.removeObject(forKey: StorageKeys.chipTimelineIntroSnoozedUntilMs)
        collapseConsecutiveToolCalls = Self.defaultCollapseConsecutiveToolCalls
        timelineStyle = Self.defaultTimelineStyle
        followUpDelivery = Self.defaultFollowUpDelivery
        chipTimelineIntroDone = false
        chipTimelineIntroSnoozedUntilMs = nil
    }
}
