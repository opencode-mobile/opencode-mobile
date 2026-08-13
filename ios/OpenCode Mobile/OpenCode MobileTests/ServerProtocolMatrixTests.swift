import Testing
@testable import OpenCode_Mobile

struct ServerProtocolMatrixTests {
    @Test func capabilitiesAndLastTurnAvailabilityMatchProtocol() {
        let rows: [(ServerProtocol, Bool)] = [
            (.v1, true),
            (.v2, false),
        ]
        for (serverProtocol, supportsSessionMessageDiff) in rows {
            #expect(ServerCapabilities.forProtocol(serverProtocol).supportsSessionMessageDiff == supportsSessionMessageDiff)
        }
    }
}
