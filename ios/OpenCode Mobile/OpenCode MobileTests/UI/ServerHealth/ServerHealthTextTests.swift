import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ServerHealthTextTests {
    @Test func quickSummaryPluralisesOnTheReconnectCount() {
        let uptime = "99.8%"
        let one = 1
        let two = 2
        #expect(
            String(localized: "server_health.quick_view_summary", defaultValue: "Uptime \(uptime) · \(one) reconnects today")
                == "Uptime 99.8% · 1 reconnect today"
        )
        #expect(
            String(localized: "server_health.quick_view_summary", defaultValue: "Uptime \(uptime) · \(two) reconnects today")
                == "Uptime 99.8% · 2 reconnects today"
        )
    }

    @Test func reconnectsSummaryPluralises() {
        let one = 1
        let three = 3
        #expect(String(localized: "server_health.reconnects_summary", defaultValue: "\(one) reconnects") == "1 reconnect")
        #expect(String(localized: "server_health.reconnects_summary", defaultValue: "\(three) reconnects") == "3 reconnects")
    }

    @Test @MainActor func statusCopyAndProtocolLabels() {
        #expect(ServerHealthText.status(.live).resolved == "Live")
        #expect(ServerHealthText.status(.authFailed).resolved == "Auth failed")
        #expect(ServerHealthText.status(.stopped).resolved == "Not measuring")
        #expect(ServerHealthText.protocolLabel(.v1) == "API v1")
        #expect(ServerHealthText.protocolLabel(.v2) == "API v2")
        #expect(ServerHealthText.protocolLabel(nil) == nil)
        #expect(ServerHealthText.milliseconds(23) == "23 ms")
        #expect(ServerHealthText.milliseconds(nil) == "—")
    }
}
