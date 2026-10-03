import Foundation
import Observation

/// Session Chat display preferences (OPE-144).
/// Device-local, default on, cleared by Clear All Data.
@Observable
nonisolated final class SessionChatDisplayPreferences {
    static let shared = SessionChatDisplayPreferences()
    static let defaultCollapseConsecutiveToolCalls = true
    static let defaultChipTimeline = false
    static let defaultFollowUpDelivery = FollowUpDelivery.steer

    private enum StorageKeys {
        static let collapseConsecutiveToolCalls = "collapseConsecutiveToolCalls"
        static let chipTimeline = "chipTimeline"
        static let followUpDelivery = "followUpDelivery"
        static let chipTimelineIntroDone = "chipTimelineIntroDone"
        static let chipTimelineIntroSnoozedUntilMs = "chipTimelineIntroSnoozedUntilMs"
    }

    private let defaults: UserDefaults
    private let featureFlags: FeatureFlags

    /// When true, a run of two or more consecutive visible tool parts in one
    /// assistant message collapses into one summary tool group.
    private(set) var collapseConsecutiveToolCalls: Bool

    /// Stored "Chip timeline" preference, independent of the remote flag.
    private(set) var chipTimelinePreference: Bool

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
        chipTimelinePreference && chipTimelineAvailable
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
        if defaults.object(forKey: StorageKeys.chipTimeline) == nil {
            chipTimelinePreference = Self.defaultChipTimeline
        } else {
            chipTimelinePreference = defaults.bool(forKey: StorageKeys.chipTimeline)
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

    /// Any explicit choice, from Settings or the intro sheet, also ends the intro.
    func setChipTimeline(_ value: Bool) {
        chipTimelinePreference = value
        defaults.set(value, forKey: StorageKeys.chipTimeline)
        finishChipTimelineIntro()
    }

    /// Settles the starting Chip timeline value once per install (OPE-270).
    /// `existingInstall` is true when the app opens past App onboarding. A
    /// stored value, from an earlier launch or a Settings choice, always wins.
    func resolveChipTimelineForInstall(existingInstall: Bool) {
        guard defaults.object(forKey: StorageKeys.chipTimeline) == nil else { return }
        let value = ChipTimelineIntro.startingChipTimeline(existingInstall: existingInstall)
        chipTimelinePreference = value
        defaults.set(value, forKey: StorageKeys.chipTimeline)
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
            chipTimelineOn: chipTimelinePreference,
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
        defaults.removeObject(forKey: StorageKeys.followUpDelivery)
        defaults.removeObject(forKey: StorageKeys.chipTimelineIntroDone)
        defaults.removeObject(forKey: StorageKeys.chipTimelineIntroSnoozedUntilMs)
        collapseConsecutiveToolCalls = Self.defaultCollapseConsecutiveToolCalls
        chipTimelinePreference = Self.defaultChipTimeline
        followUpDelivery = Self.defaultFollowUpDelivery
        chipTimelineIntroDone = false
        chipTimelineIntroSnoozedUntilMs = nil
    }
}
