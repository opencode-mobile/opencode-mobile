import Testing
@testable import OpenCode_Mobile

struct ServerProtocolMatrixTests {
    @Test func capabilitiesAndLastTurnAvailabilityMatchProtocol() {
        let rows: [(ServerProtocol, Bool, Bool)] = [
            (.v1, true, true),
            (.v2, false, false),
        ]
        for (serverProtocol, supportsSessionMessageDiff, supportsSessionArchive) in rows {
            #expect(ServerCapabilities.forProtocol(serverProtocol).supportsSessionMessageDiff == supportsSessionMessageDiff)
            #expect(ServerCapabilities.forProtocol(serverProtocol).supportsSessionArchive == supportsSessionArchive)
        }
    }
}
