import Foundation
import Testing
@testable import OpenCode_Mobile

struct FeedbackLogRedactorTests {
    private let redactor = FeedbackLogRedactor()

    @Test func masksURLs() {
        #expect(
            redactor.redact("GET https://mac.tail1234.ts.net:4096/global/event opened")
                == "GET [REDACTED-URL] opened"
        )
    }

    @Test func masksIPv4WithAndWithoutPorts() {
        #expect(
            redactor.redact("Failed to connect to /10.0.2.2:4403 from /10.0.2.17")
                == "Failed to connect to /[REDACTED-HOST] from /[REDACTED-HOST]"
        )
    }

    @Test func masksIPv6() {
        #expect(redactor.redact("dial [fe80::1%en0]:4096 refused").contains("fe80") == false)
        #expect(redactor.redact("peer [::1]:4096") == "peer [REDACTED-HOST]")
        #expect(redactor.redact("peer 2001:db8:85a3::8a2e:370:7334 up") == "peer [REDACTED-HOST] up")
        #expect(redactor.redact("peer fd7a:115c:a1e0:ab12:4843:cd96:6258:b240") == "peer [REDACTED-HOST]")
    }

    @Test func masksHostPortPairsAndPortNumbers() {
        #expect(redactor.redact("connect localhost:4096 ok") == "connect [REDACTED-HOST] ok")
        #expect(redactor.redact("connect studio.local:4096 ok") == "connect [REDACTED-HOST] ok")
        #expect(redactor.redact("listening on port 4096") == "listening on port [REDACTED]")
    }

    @Test func keepsOrdinaryLogLines() {
        let lines = [
            "API response GET /global/health status=200 durationMs=24",
            "probe /api/info failed code=DecodingError",
            "stream idle at 18:02:30 after 45 s",
            "bindProfile profile=B905112A-3732-4F4F-A85B-23F7F8C7B1B7 ready=0",
        ]
        for line in lines {
            #expect(redactor.redact(line) == line)
        }
    }

    @Test func masksTheActiveProfileHostUsernameAndName() {
        let redactor = FeedbackLogRedactor(
            profile: ConnectionProfile(
                baseURL: "http://studio-mac.example:4096/",
                serverURLs: ["https://studio.tailnet.ts.net/"],
                username: "walrus",
                password: "secret",
                name: "Studio Mac"
            )
        )

        let line = redactor.redact("profile Studio Mac user walrus host studio-mac.example via studio.tailnet.ts.net")

        #expect(line == "profile [REDACTED] user [REDACTED] host [REDACTED] via [REDACTED]")
    }

    @Test func keepsTheDefaultUsername() {
        let redactor = FeedbackLogRedactor(
            profile: ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "")
        )

        #expect(redactor.redact("auth user opencode") == "auth user opencode")
    }

    @Test func uploadedLogIsRedactedButSettingsExportIsNot() throws {
        let buffer = DiagnosticLogBuffer(persistDelayMilliseconds: nil)
        buffer.append(area: .connection, level: .warning, message: "Failed to connect to /10.0.2.2:4403")

        let settingsExport = String(decoding: try buffer.exportJSONL(window: .fifteenMinutes), as: UTF8.self)
        let gzipped = try #require(FeedbackLogEncoder.encode(buffer: buffer, window: .fifteenMinutes))
        let uploaded = try #require(
            FeedbackAttachmentEncoderTests.inflate(gzipped.dropFirst(10).dropLast(8), capacity: 64_000)
        )
        let uploadedText = String(decoding: uploaded, as: UTF8.self)

        #expect(settingsExport.contains("10.0.2.2:4403"))
        #expect(!uploadedText.contains("10.0.2.2"))
        #expect(uploadedText.contains("[REDACTED-HOST]"))
    }
}
