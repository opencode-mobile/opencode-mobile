import Foundation
import Testing
@testable import OpenCode_Mobile

struct ClientMessageIDTests {
    @Test func next_matchesServerAscendingIDFormat() {
        let id = ClientMessageID.next()

        #expect(id.wholeMatch(of: /msg_[0-9a-f]{12}[0-9A-Za-z]{14}/) != nil)
    }

    @Test func next_encodesLow48BitsOfMillisTimesFourKPlusCounter() {
        var generator = SystemRandomNumberGenerator()
        let nowMs: Int64 = 1_761_216_000_123
        let first = ClientMessageID.next(nowMs: nowMs, using: &generator)
        let second = ClientMessageID.next(nowMs: nowMs, using: &generator)

        // Like the server, only the low 48 bits of the value are kept.
        let mask: UInt64 = 0xFFFF_FFFF_FFFF
        #expect(UInt64(first.dropFirst(4).prefix(12), radix: 16) == (UInt64(nowMs) * 0x1000 + 1) & mask)
        #expect(UInt64(second.dropFirst(4).prefix(12), radix: 16) == (UInt64(nowMs) * 0x1000 + 2) & mask)
        #expect(first < second)
    }

    @Test func isClientID_onlyForMessageIDs() {
        #expect(ClientMessageID.isClientID(ClientMessageID.next()))
        #expect(!ClientMessageID.isClientID("pending-0E7A"))
    }
}
