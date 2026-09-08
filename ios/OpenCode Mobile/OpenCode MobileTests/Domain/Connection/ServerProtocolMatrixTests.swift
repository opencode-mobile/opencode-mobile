import Testing
@testable import OpenCode_Mobile

struct ServerProtocolMatrixTests {
    @Test func capabilitiesAndLastTurnAvailabilityMatchProtocol() {
        let rows: [(ServerProtocol, Bool, Bool, Bool)] = [
            (.v1, true, true, false),
            (.v2, false, false, true),
        ]
        for (serverProtocol, supportsSessionMessageDiff, supportsSessionArchive, supportsFollowUpDelivery) in rows {
            #expect(ServerCapabilities.forProtocol(serverProtocol).supportsSessionMessageDiff == supportsSessionMessageDiff)
            #expect(ServerCapabilities.forProtocol(serverProtocol).supportsSessionArchive == supportsSessionArchive)
            #expect(ServerCapabilities.forProtocol(serverProtocol).supportsFollowUpDelivery == supportsFollowUpDelivery)
        }
    }
}
