import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct SessionComposerStyleTests {
    @Test func outlineFadeIsTheStandardStyle() {
        #expect(SessionComposerStyle.resolve(dockedEnabled: false) == .outlineFade)
    }

    @Test func dockedFlagSelectsDocked() {
        #expect(SessionComposerStyle.resolve(dockedEnabled: true) == .docked)
    }

    @Test func onlyDockedStaysBehindAFlag() {
        #expect(FeatureFlag.sessionComposerDocked.key == "session_composer_docked")
        #expect(FeatureFlag.sessionComposerDocked.defaultValue == false)
        #expect(FeatureFlag(rawValue: "session_composer_outline_fade") == nil)
    }

    @MainActor @Test func fadeFitsTheRestingGapAndGrowsAwayFromTheEnd() {
        #expect(OpenCodeSessionComposerScrim.fadeHeight(awayFromEnd: false) == OpenCodeBottomFade.overhang)
        #expect(OpenCodeSessionComposerScrim.fadeHeight(awayFromEnd: true) == 46)
    }
}
