import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionCacheSizeFormatterTests {
    private let en = Locale(identifier: "en_US")
    private let de = Locale(identifier: "de_DE")

    @Test func emptyForZeroOrNegative() {
        #expect(SessionCacheSizeFormatter.format(bytes: 0, locale: en) == "Empty")
        #expect(SessionCacheSizeFormatter.format(bytes: -1, locale: en) == "Empty")
    }

    @Test func formatsBytesAndKilobytes() {
        #expect(SessionCacheSizeFormatter.format(bytes: 512, locale: en) == "512 B")
        #expect(SessionCacheSizeFormatter.format(bytes: 1024, locale: en) == "1 KB")
        #expect(SessionCacheSizeFormatter.format(bytes: 2048, locale: en) == "2 KB")
    }

    @Test func formatsMegabytesAndGigabytes() {
        #expect(SessionCacheSizeFormatter.format(bytes: Int64(1.5 * 1024 * 1024), locale: en) == "1.5 MB")
        #expect(SessionCacheSizeFormatter.format(bytes: 1024 * 1024 * 1024, locale: en) == "1.0 GB")
        #expect(SessionCacheSizeFormatter.format(bytes: 1023 * 1024 * 1024 * 1024, locale: en) == "1,023.0 GB")
        #expect(SessionCacheSizeFormatter.format(bytes: 2 * 1024 * 1024 * 1024 * 1024, locale: en) == "2.0 TB")
    }

    @Test func numbersFollowTheLocale() {
        #expect(SessionCacheSizeFormatter.format(bytes: Int64(1.5 * 1024 * 1024), locale: de) == "1,5 MB")
        #expect(SessionCacheSizeFormatter.format(bytes: 1023 * 1024 * 1024 * 1024, locale: de) == "1.023,0 GB")
    }
}
