import Foundation
import Observation

/// How Session Chat presents an assistant turn (#779). Wire values are stored.
nonisolated enum SessionChatTimelineStyle: String, CaseIterable, Sendable {
    /// Tool cards and tool groups between the agent's messages.
    case standard
    /// One short line per step on a vertical rail with live status.
    case stepRail = "step_rail"
    /// Running steps on one live card per segment that settles into a summary line.
    case activityCard = "activity_card"

    var wireValue: String { rawValue }

    /// Wire value of the removed Chips style (#1120); read as Standard.
    static let removedChipsWireValue = "chips"

    static func fromWire(_ value: String?) -> SessionChatTimelineStyle? {
        if value == removedChipsWireValue { return .standard }
        return value.flatMap(SessionChatTimelineStyle.init(rawValue:))
    }

    // Names and descriptions are app text, keyed in the UI (`settingsTitle`, `settingsSubtitle`).
}

/// Session Chat display preferences (OPE-144).
/// Device-local, default on, cleared by Clear All Data.
@Observable
nonisolated final class SessionChatDisplayPreferences {
    static let shared = SessionChatDisplayPreferences()
    static let defaultCollapseConsecutiveToolCalls = true
    static let defaultTimelineStyle = SessionChatTimelineStyle.standard
    static let defaultFollowUpDelivery = FollowUpDelivery.steer

    private enum StorageKeys {
        static let collapseConsecutiveToolCalls = "collapseConsecutiveToolCalls"
        /// Legacy Chip timeline boolean (OPE-260); the style is gone (#1120). Its
        /// presence still marks an existing install; Clear All Data removes it.
        static let chipTimeline = "chipTimeline"
        static let timelineStyle = "timelineStyle"
        static let followUpDelivery = "followUpDelivery"
        /// Legacy Chip timeline intro keys; removed by Clear All Data only.
        static let chipTimelineIntroDone = "chipTimelineIntroDone"
        static let chipTimelineIntroSnoozedUntilMs = "chipTimelineIntroSnoozedUntilMs"
        static let timelineStylePickerDone = "timelineStylePickerDone"
    }

    private let defaults: UserDefaults

    /// Values to put back when the last sandbox ends; set while one is open.
    @ObservationIgnored private var sandboxSnapshot: Snapshot?
    @ObservationIgnored private var sandboxDepth = 0

    private struct Snapshot {
        let collapseConsecutiveToolCalls: Bool
        let timelineStyle: SessionChatTimelineStyle
        let timelineStylePickerDone: Bool?
        let followUpDelivery: FollowUpDelivery
    }

    /// True while a `beginSandbox()` handle is open.
    var isSandboxed: Bool { sandboxDepth > 0 }

    /// When true, a run of two or more consecutive visible tool parts in one
    /// assistant message collapses into one summary tool group.
    private(set) var collapseConsecutiveToolCalls: Bool

    /// The Timeline Style Session Chat renders.
    private(set) var timelineStyle: SessionChatTimelineStyle

    /// When true, Session Chat shows an assistant turn as a step rail (#779).
    var stepRailTimeline: Bool {
        timelineStyle == .stepRail
    }

    /// When true, Session Chat shows each run of steps as one activity card (#779).
    var activityCardTimeline: Bool {
        timelineStyle == .activityCard
    }

    /// One-time Timeline Style picker (#805): nil until this version settles it,
    /// false while an updated install still has to pick, true once answered.
    private(set) var timelineStylePickerDone: Bool?

    /// True while an updated install has not answered the Timeline Style picker.
    var timelineStylePickerPending: Bool {
        !isSandboxed && timelineStylePickerDone == false
    }

    private(set) var followUpDelivery: FollowUpDelivery

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: StorageKeys.collapseConsecutiveToolCalls) == nil {
            collapseConsecutiveToolCalls = Self.defaultCollapseConsecutiveToolCalls
        } else {
            collapseConsecutiveToolCalls = defaults.bool(forKey: StorageKeys.collapseConsecutiveToolCalls)
        }
        let storedWire = defaults.string(forKey: StorageKeys.timelineStyle)
        if let stored = SessionChatTimelineStyle.fromWire(storedWire) {
            timelineStyle = stored
            if storedWire == SessionChatTimelineStyle.removedChipsWireValue {
                // Chips is gone (#1120): rewrite the stored choice as Standard.
                defaults.set(stored.wireValue, forKey: StorageKeys.timelineStyle)
            }
        } else if defaults.object(forKey: StorageKeys.chipTimeline) != nil {
            // Migrate the old Chip timeline boolean once, on or off, to Standard;
            // the old key stays so the install still reads as existing.
            timelineStyle = .standard
            defaults.set(SessionChatTimelineStyle.standard.wireValue, forKey: StorageKeys.timelineStyle)
        } else {
            timelineStyle = Self.defaultTimelineStyle
        }
        followUpDelivery = FollowUpDelivery.fromWire(defaults.string(forKey: StorageKeys.followUpDelivery))
            ?? Self.defaultFollowUpDelivery
        timelineStylePickerDone = (defaults.object(forKey: StorageKeys.timelineStylePickerDone) as? NSNumber)?.boolValue
    }

    func setCollapseConsecutiveToolCalls(_ value: Bool) {
        collapseConsecutiveToolCalls = value
        persist { $0.set(value, forKey: StorageKeys.collapseConsecutiveToolCalls) }
    }

    /// Any explicit choice, from Settings or the picker, also ends the Timeline Style picker.
    func setTimelineStyle(_ style: SessionChatTimelineStyle) {
        storeTimelineStyle(style)
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
        persist { $0.set(style.wireValue, forKey: StorageKeys.timelineStyle) }
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

    func setFollowUpDelivery(_ value: FollowUpDelivery) {
        followUpDelivery = value
        persist { $0.set(value.wireValue, forKey: StorageKeys.followUpDelivery) }
    }

    /// Keeps every change in memory until the returned handle ends, then restores the values
    /// from before. While a sandbox is open the one-time Timeline Style picker is not offered. The Session Chat Playground holds one: nothing it does may change the
    /// real preferences.
    func beginSandbox() -> Sandbox {
        if sandboxDepth == 0 {
            sandboxSnapshot = Snapshot(
                collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
                timelineStyle: timelineStyle,
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
        timelineStylePickerDone = nil
    }
}
