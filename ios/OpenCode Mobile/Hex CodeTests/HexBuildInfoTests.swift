import Testing
@testable import Hex_Code

/// The build line Settings › App › Version and Developer › Build show.
struct HexBuildInfoTests {
    @Test func buildInfoLabels() {
        let debug = HexBuildInfo(version: "0.1.0", isDebug: true, commit: "abc123def456")
        #expect(debug.versionLabel == "0.1.0 (debug)")
        #expect(debug.summary == "Hex Code 0.1.0 (debug) abc123def456")
        let release = HexBuildInfo(version: "0.1.0", isDebug: false, commit: nil)
        #expect(release.versionLabel == "0.1.0 (release)")
        #expect(release.summary == "Hex Code 0.1.0 (release)")
    }
}
