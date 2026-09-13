import Foundation
import Testing
@testable import OpenCode_Mobile

struct OpenCodeURLSessionsTests {
    @Test func restSessionUsesShortTimeoutsWithoutWaitingForConnectivity() {
        let configuration = OpenCodeURLSessions.rest.configuration
        #expect(configuration.timeoutIntervalForRequest == 15)
        #expect(configuration.timeoutIntervalForResource == 60)
        #expect(configuration.waitsForConnectivity == false)
    }
}
