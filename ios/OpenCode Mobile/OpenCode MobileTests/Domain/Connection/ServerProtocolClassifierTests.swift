import Foundation
import Testing
@testable import OpenCode_Mobile

struct ServerProtocolClassifierTests {
    @Test func prefersLegacyHealthWhenBothGenerationsExist() {
        let detected = ServerProtocolClassifier.classify(
            globalHealth: HealthDTO(healthy: true, version: "1.18.4", pid: nil),
            apiInfo: HealthDTO(healthy: true, version: "2.0.0", pid: 123)
        )
        #expect(detected == .v1)
    }

    @Test func recognizesV2HealthByProcessIdentifier() {
        let detected = ServerProtocolClassifier.classify(
            globalHealth: nil,
            apiInfo: HealthDTO(healthy: true, version: "2.0.0", pid: 123)
        )
        #expect(detected == .v2)
    }

    @Test func recognizesV2FromApiHealthPidWhenApiInfoIsMissing() {
        let detected = ServerProtocolClassifier.classify(
            globalHealth: nil,
            apiHealth: HealthDTO(healthy: true, version: "2.0.1", pid: 2605231)
        )
        #expect(detected == .v2)
    }

    @Test func prefersLegacyHealthOverApiHealth() {
        let detected = ServerProtocolClassifier.classify(
            globalHealth: HealthDTO(healthy: true, version: "1.18.4", pid: nil),
            apiHealth: HealthDTO(healthy: true, version: "2.0.1", pid: 12)
        )
        #expect(detected == .v1)
    }

    @Test func recognizesV2WhenPidPresentAndHealthyMissing() {
        let fromApiInfo = ServerProtocolClassifier.classify(
            globalHealth: nil,
            apiInfo: HealthDTO(healthy: nil, version: "2.0.7", pid: 1481731)
        )
        #expect(fromApiInfo == .v2)
    }

    @Test func returnsNilWhenNeitherHealthEndpointIsUsable() {
        #expect(ServerProtocolClassifier.classify(globalHealth: nil) == nil)
        #expect(
            ServerProtocolClassifier.classify(
                globalHealth: HealthDTO(healthy: false, version: nil, pid: nil),
                apiInfo: HealthDTO(healthy: false, version: nil, pid: nil)
            ) == nil
        )
    }

    @Test func capabilitiesHideSessionMessageDiffOnV2() {
        #expect(ServerCapabilities.forProtocol(.v1).supportsSessionMessageDiff == true)
        #expect(ServerCapabilities.forProtocol(.v2).supportsSessionMessageDiff == false)
        #expect(ServerCapabilities.forProtocol(.v1).supportsSessionArchive == true)
        #expect(ServerCapabilities.forProtocol(.v2).supportsSessionArchive == false)
        #expect(ServerCapabilities.forProtocol(.v2, v2SessionArchive: true).supportsSessionArchive == true)
        #expect(ServerCapabilities.forProtocol(.v1, v2SessionArchive: false).supportsSessionArchive == true)
    }
}
