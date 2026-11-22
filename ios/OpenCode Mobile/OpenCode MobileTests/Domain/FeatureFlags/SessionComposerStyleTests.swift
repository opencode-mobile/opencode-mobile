import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct SessionComposerStyleTests {
    @Test(arguments: [
        (false, false, SessionComposerStyle.floating),
        (true, false, SessionComposerStyle.outlineFade),
        (false, true, SessionComposerStyle.docked),
        (true, true, SessionComposerStyle.docked),
    ])
    func resolvesFromFlags(outlineFade: Bool, docked: Bool, expected: SessionComposerStyle) {
        #expect(SessionComposerStyle.resolve(outlineFadeEnabled: outlineFade, dockedEnabled: docked) == expected)
    }

    @Test func flagsShareKeysWithAndroidAndDefaultOff() {
        #expect(FeatureFlag.sessionComposerOutlineFade.key == "session_composer_outline_fade")
        #expect(FeatureFlag.sessionComposerDocked.key == "session_composer_docked")
        #expect(FeatureFlag.sessionComposerOutlineFade.defaultValue == false)
        #expect(FeatureFlag.sessionComposerDocked.defaultValue == false)
    }
}
