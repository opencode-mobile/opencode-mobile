import Testing
@testable import OpenCode_Mobile

struct DictationUnavailableCopyTests {
    private let modelReasons: [OpenCodeDictationUnavailableReason] = [
        .modelNotInstalled,
        .modelDownloading(progress: nil),
        .modelDownloading(progress: 0.4),
        .modelDownloadFailed,
    ]

    @Test func modelStates_nameIOSAndTheLanguage() {
        for reason in modelReasons {
            let title = reason.title(languageLabel: "English")
            #expect(title.hasPrefix("iOS"))
            #expect(title.contains("English speech model"))
            #expect(reason.detail != nil)
        }
    }

    @Test func blankLanguage_fallsBackToSpeechModel() {
        #expect(OpenCodeDictationUnavailableReason.modelNotInstalled.title(languageLabel: "") == "iOS needs its speech model")
    }

    @Test func downloading_disablesTheAction() {
        #expect(OpenCodeDictationUnavailableReason.modelDownloading(progress: 0.5).actionEnabled == false)
        #expect(OpenCodeDictationUnavailableReason.modelDownloadFailed.actionEnabled)
        #expect(OpenCodeDictationUnavailableReason.modelDownloadFailed.actionTitle == "Try Again")
    }

    @Test func recognizerError_usesTheMessageOrDefault() {
        #expect(OpenCodeDictationUnavailableReason.recognizerError("Busy").title(languageLabel: "English") == "Busy")
        #expect(OpenCodeDictationUnavailableReason.recognizerError(" ").title(languageLabel: "English") == "Dictation stopped")
    }
}
