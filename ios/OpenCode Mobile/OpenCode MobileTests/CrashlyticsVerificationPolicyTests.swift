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

    func testInfoPlistGateDefaultsDisabledAndParsesBoolLikeValues() {
        XCTAssertFalse(CrashlyticsVerificationPolicy.isEnabled(infoValue: nil))
        XCTAssertFalse(CrashlyticsVerificationPolicy.isEnabled(infoValue: false))
        XCTAssertFalse(CrashlyticsVerificationPolicy.isEnabled(infoValue: "no"))
        XCTAssertTrue(CrashlyticsVerificationPolicy.isEnabled(infoValue: true))
        XCTAssertTrue(CrashlyticsVerificationPolicy.isEnabled(infoValue: NSNumber(value: true)))
        XCTAssertTrue(CrashlyticsVerificationPolicy.isEnabled(infoValue: "YES"))
        XCTAssertTrue(CrashlyticsVerificationPolicy.isEnabled(infoValue: "true"))
    }
}
