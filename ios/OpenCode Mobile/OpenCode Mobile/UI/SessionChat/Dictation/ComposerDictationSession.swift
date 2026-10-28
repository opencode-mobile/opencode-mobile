import Foundation

enum DictationSupport: Equatable {
    case hidden
    case ready(languageLabel: String, onDevice: Bool)
    case permissionDenied
    case modelNotInstalled(languageLabel: String)
    /// iOS is already fetching the locale's model.
    case modelDownloading(languageLabel: String)

    /// The mic stays available for permission-denied and model-not-installed so
    /// a tap can open the card in that state. Only an unsupported OS or locale hides it.
    var showsMic: Bool {
        if case .hidden = self { return false }
        return true
    }
}

protocol ComposerDictationSession: AnyObject {
    func probe() async -> DictationSupport
    func start(
        onPartial: @escaping @MainActor (String, String) -> Void,
        onLevel: @escaping @MainActor (Double) -> Void,
        onError: @escaping @MainActor (String) -> Void
    )
    func stop()
    /// Downloads the speech model, reporting 0...1 progress. Returns false when iOS fails it.
    func downloadModel(onProgress: @escaping @MainActor @Sendable (Double) -> Void) async -> Bool
}

enum ComposerDictationSessions {
    static func make() -> any ComposerDictationSession {
        if #available(iOS 26, *) {
            return IOS26ComposerDictationSession()
        }
        return HiddenComposerDictationSession()
    }
}

/// Below iOS 26 there is no SpeechTranscriber / DictationTranscriber. The mic stays hidden.
private final class HiddenComposerDictationSession: ComposerDictationSession {
    func probe() async -> DictationSupport { .hidden }

    func start(
        onPartial: @escaping @MainActor (String, String) -> Void,
        onLevel: @escaping @MainActor (Double) -> Void,
        onError: @escaping @MainActor (String) -> Void
    ) {}

    func stop() {}

    func downloadModel(onProgress: @escaping @MainActor @Sendable (Double) -> Void) async -> Bool { false }
}
