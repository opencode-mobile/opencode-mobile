import XCTest
@testable import OpenCode_Mobile

final class CrashlyticsVerificationPolicyTests: XCTestCase {
    func testDisabledBuildsNeverRecordVerification() {
        XCTAssertFalse(
            CrashlyticsVerificationPolicy.shouldRecord(
                enabled: false,
                alreadyRecorded: false
            )
        )
    }

    func testEnabledBuildsRecordVerificationOnlyBeforeMarkerExists() {
        XCTAssertTrue(
            CrashlyticsVerificationPolicy.shouldRecord(
                enabled: true,
                alreadyRecorded: false
            )
        )
        XCTAssertFalse(
            CrashlyticsVerificationPolicy.shouldRecord(
                enabled: true,
                alreadyRecorded: true
            )
        )
    }

    func testMarkerIsStableAndBuildScoped() {
        XCTAssertEqual(
            CrashlyticsVerificationPolicy.markerKey(buildNumber: "42"),
            "crashlytics_verified_42"
        )
    }
}
