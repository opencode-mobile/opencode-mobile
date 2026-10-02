import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionCompactChromeTests {
    @Test func successMessage_formatsDeltaWhenBothFractionsPresent() {
        #expect(
            SessionCompactChrome.successMessage(beforeFraction: 0.72, afterFraction: 0.34)
                == "Context compacted · 72% → 34%"
        )
    }

    @Test func successMessage_fallsBackWhenEitherFractionMissing() {
        #expect(
            SessionCompactChrome.successMessage(beforeFraction: 0.72, afterFraction: nil)
                == SessionCompactChrome.fallbackSuccess
        )
        #expect(
            SessionCompactChrome.successMessage(beforeFraction: nil, afterFraction: 0.34)
                == SessionCompactChrome.fallbackSuccess
        )
    }

    @Test func successMessage_fallsBackWhenUsageIsUnknown() {
        #expect(
            SessionCompactChrome.successMessage(beforeFraction: 0, afterFraction: 0)
                == SessionCompactChrome.fallbackSuccess
        )
        #expect(
            SessionCompactChrome.successMessage(beforeFraction: 0.72, afterFraction: 0)
                == SessionCompactChrome.fallbackSuccess
        )
    }
}
