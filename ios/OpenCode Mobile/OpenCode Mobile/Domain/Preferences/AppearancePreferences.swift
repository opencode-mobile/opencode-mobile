import Foundation
import Observation

/// Light / dark override for the whole app (#1044). Wire values are stored.
nonisolated enum AppearanceMode: String, CaseIterable, Sendable {
    /// Follows the phone's light or dark setting.
    case system
    case light
    case dark

    static let defaultMode: AppearanceMode = .system

    var wireValue: String { rawValue }

    static func fromWire(_ value: String?) -> AppearanceMode? {
        value.flatMap(AppearanceMode.init(rawValue:))
    }
}

/// One step of an app-level text size (#1044). The factor multiplies the phone's own text size.
nonisolated enum AppTextSize: String, CaseIterable, Sendable {
    case extraSmall = "extra_small"
    case small
    case `default`
    case large
    case extraLarge = "extra_large"

    static let defaultSize: AppTextSize = .default

    var factor: Double {
        switch self {
        case .extraSmall: 0.85
        case .small: 0.92
        case .default: 1.0
        case .large: 1.12
        case .extraLarge: 1.26
        }
    }

    /// Position on the five-step slider, 0 ... 4; Default is the middle step.
    var stepIndex: Int { Self.allCases.firstIndex(of: self) ?? 2 }

    static func fromStepIndex(_ index: Int) -> AppTextSize {
        allCases[min(max(index, 0), allCases.count - 1)]
    }

    var wireValue: String { rawValue }

    static func fromWire(_ value: String?) -> AppTextSize? {
        value.flatMap(AppTextSize.init(rawValue:))
    }
}

/// The three regions that carry their own text size (#1044).
nonisolated enum AppTextRegion: String, CaseIterable, Sendable {
    /// Home session rows and the lists that reuse them: archived sessions, session search.
    case homeList = "home_list"
    /// Session Chat timeline and composer.
    case sessionChat = "session_chat"
    /// Terminal, Changes, the file viewer and tool output sheets.
    case code
}

/// Settings › Appearance mode and text sizes (#1044).
/// Device-local, not per Connection Profile, cleared by Clear All Data.
@Observable
final class AppearancePreferences {
    static let shared = AppearancePreferences()

    private enum StorageKeys {
        static let mode = "appearanceMode"
        static func textSize(_ region: AppTextRegion) -> String {
            switch region {
            case .homeList: "homeListTextSize"
            case .sessionChat: "sessionChatTextSize"
            case .code: "codeTextSize"
            }
        }
    }

    private let defaults: UserDefaults

    private(set) var mode: AppearanceMode
    private(set) var homeListTextSize: AppTextSize
    private(set) var sessionChatTextSize: AppTextSize
    private(set) var codeTextSize: AppTextSize

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        mode = AppearanceMode.fromWire(defaults.string(forKey: StorageKeys.mode)) ?? AppearanceMode.defaultMode
        homeListTextSize = Self.loadSize(.homeList, defaults: defaults)
        sessionChatTextSize = Self.loadSize(.sessionChat, defaults: defaults)
        codeTextSize = Self.loadSize(.code, defaults: defaults)
    }

    func textSize(for region: AppTextRegion) -> AppTextSize {
        switch region {
        case .homeList: homeListTextSize
        case .sessionChat: sessionChatTextSize
        case .code: codeTextSize
        }
    }

    /// True when any text size differs from Default; "Reset text sizes" is enabled.
    var canResetTextSizes: Bool {
        AppTextRegion.allCases.contains { textSize(for: $0) != .defaultSize }
    }

    /// True when a text size or the mode differs from its default; "Reset all appearance" is enabled.
    var canResetAll: Bool {
        canResetTextSizes || mode != .defaultMode
    }

    func setMode(_ value: AppearanceMode) {
        mode = value
        defaults.set(value.wireValue, forKey: StorageKeys.mode)
    }

    func setTextSize(_ value: AppTextSize, for region: AppTextRegion) {
        switch region {
        case .homeList: homeListTextSize = value
        case .sessionChat: sessionChatTextSize = value
        case .code: codeTextSize = value
        }
        defaults.set(value.wireValue, forKey: StorageKeys.textSize(region))
    }

    /// All three sizes back to Default; the mode stays.
    func resetTextSizes() {
        for region in AppTextRegion.allCases {
            setTextSize(.defaultSize, for: region)
        }
    }

    /// Text sizes back to Default and the mode back to System.
    func resetAll() {
        resetTextSizes()
        setMode(.defaultMode)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.mode)
        for region in AppTextRegion.allCases {
            defaults.removeObject(forKey: StorageKeys.textSize(region))
        }
        mode = .defaultMode
        homeListTextSize = .defaultSize
        sessionChatTextSize = .defaultSize
        codeTextSize = .defaultSize
    }

    private static func loadSize(_ region: AppTextRegion, defaults: UserDefaults) -> AppTextSize {
        AppTextSize.fromWire(defaults.string(forKey: StorageKeys.textSize(region))) ?? .defaultSize
    }
}
