import Testing
@testable import OpenCode_Mobile

@Suite struct SettingsDetailTests {
    private let all: [SettingsDetail] = [
        .servers, .appearance, .componentGallery, .textMateLanguages,
        .textMateLogbook, .featureFlags, .diagnosticLogs,
    ]

    @Test func resolved_unlocked_keepsEveryDetail() {
        for detail in all {
            #expect(detail.resolved(developerToolsUnlocked: true) == detail)
        }
    }

    @Test func resolved_locked_fallsBackToServersForDeveloperTools() {
        let developerTools: [SettingsDetail] = [.componentGallery, .textMateLanguages, .textMateLogbook, .featureFlags]
        for detail in developerTools {
            #expect(detail.resolved(developerToolsUnlocked: false) == .servers)
        }
    }

    @Test func resolved_locked_keepsRegularDetails() {
        for detail in [SettingsDetail.servers, .appearance, .diagnosticLogs] {
            #expect(detail.resolved(developerToolsUnlocked: false) == detail)
        }
    }
}

#if HIGHLIGHT_LAB
@Suite struct SettingsDetailHighlightLabTests {
    @Test func highlightLabIsADeveloperTool() {
        #expect(SettingsDetail.highlightLab.resolved(developerToolsUnlocked: true) == .highlightLab)
        #expect(SettingsDetail.highlightLab.resolved(developerToolsUnlocked: false) == .servers)
    }
}
#endif
