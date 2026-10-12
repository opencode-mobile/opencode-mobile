import Foundation
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

    @Test func t3CapabilitiesIgnoreTheV1ProtocolFallback() {
        let t3 = ServerCapabilities.forEngine(.t3, protocol: .v1)
        #expect(t3.supportsSessionMessageDiff == false)
        #expect(t3.supportsSessionArchive == true)
        #expect(t3.supportsFollowUpDelivery == false)
        #expect(t3.supportsTerminal == false)
        #expect(t3.supportsRevert == false)
        #expect(t3.supportsMentions == false)
        #expect(t3.supportsSlashCommands == false)
        #expect(t3.supportsDocumentAttachments == false)
        let connection = ServerConnection(
            baseURL: URL(string: "http://t3.test")!,
            password: nil,
            engine: .t3
        )
        #expect(connection.capabilities == ServerCapabilities.t3)
        #expect(ServerCapabilities.forProtocol(.v1).supportsTerminal == true)
        #expect(ServerCapabilities.forProtocol(.v2).supportsDocumentAttachments == true)
        #expect(ServerCapabilities.forEngine(.opencode, protocol: nil).supportsMentions == true)
    }
}
