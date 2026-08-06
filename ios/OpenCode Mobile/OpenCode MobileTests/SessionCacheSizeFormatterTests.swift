import Testing
@testable import OpenCode_Mobile

struct SessionCacheSizeFormatterTests {
    @Test func emptyForZeroOrNegative() {
        #expect(SessionCacheSizeFormatter.format(bytes: 0) == "Empty")
        #expect(SessionCacheSizeFormatter.format(bytes: -1) == "Empty")
    }

    @Test func formatsBytesAndKilobytes() {
        #expect(SessionCacheSizeFormatter.format(bytes: 512) == "512 B")
        #expect(SessionCacheSizeFormatter.format(bytes: 1024) == "1 KB")
        #expect(SessionCacheSizeFormatter.format(bytes: 2048) == "2 KB")
    }

    @Test func formatsMegabytesAndGigabytes() {
        #expect(SessionCacheSizeFormatter.format(bytes: Int64(1.5 * 1024 * 1024)) == "1.5 MB")
        #expect(SessionCacheSizeFormatter.format(bytes: 1024 * 1024 * 1024) == "1.0 GB")
    }
}
