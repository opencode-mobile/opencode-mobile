import Foundation
import Testing
@testable import OpenCode_Mobile

struct AppVersionTests {
    @Test func from_usesMarketingVersionAndBuild() {
        let appVersion = AppVersion.from(version: "0.1.0", build: "42")
        #expect(appVersion.version == "0.1.0")
        #expect(appVersion.build == "42")
    }

    @Test func from_fallsBackWhenVersionOrBuildMissingOrBlank() {
        #expect(AppVersion.from(version: nil, build: nil) == AppVersion(
            version: AppVersion.fallbackVersion,
            build: AppVersion.fallbackBuild
        ))
        #expect(AppVersion.from(version: "", build: "  ").version == AppVersion.fallbackVersion)
        #expect(AppVersion.from(version: "1.0", build: "").build == AppVersion.fallbackBuild)
    }

    @Test func from_trimsSuppliedValues() {
        let appVersion = AppVersion.from(version: " 1.2.3 ", build: " 7 ")
        #expect(appVersion.version == "1.2.3")
        #expect(appVersion.build == "7")
    }
}
