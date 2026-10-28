import Foundation
import Testing
@testable import OpenCode_Mobile

struct FeedbackPayloadBuilderTests {
    @Test func report_matchesExampleKeysAndTypes() throws {
        let example = try Self.exampleReport()
        let report = FeedbackPayloadBuilder.report(Self.submission(), uid: "anon-uid")

        // createdAt is a server transform and contactEmail is optional (not collected).
        let expectedTopLevel = Set(example.keys).subtracting(["$comment", "createdAt", "contactEmail"])
        #expect(Set(report.keys) == expectedTopLevel)
        try Self.expectSameShape(report, example, path: "")
    }

    @Test func report_neverContainsServerAddressOrCredentials() throws {
        let manager = ActiveConnectionManager()
        manager.apply(
            ConnectionProfile(
                baseURL: "https://zebra-quokka-7731.example.test:8443/",
                serverURLs: ["http://10.99.88.77:4096/"],
                username: "walrus-admin-5521",
                password: "hunter2-pelican-9902",
                serverProtocol: .v2,
                id: "profile-flamingo-4411",
                name: "Narwhal Workstation 6630"
            ),
            serverProtocol: .v2
        )
        let provider = DefaultFeedbackMetadataProvider(activeConnectionManager: manager)
        let submission = Self.submission(metadata: provider.metadata(screen: .sessionChat))

        let json = String(
            data: try JSONSerialization.data(
                withJSONObject: FeedbackPayloadBuilder.report(submission, uid: "anon-uid")
            ),
            encoding: .utf8
        ) ?? ""
        let share = FeedbackSharePayloadBuilder.payload(for: submission).text

        for secret in [
            "zebra-quokka-7731", "example.test", "8443", "10.99.88.77",
            "walrus-admin-5521", "hunter2-pelican-9902", "profile-flamingo-4411", "Narwhal",
        ] {
            #expect(!json.contains(secret), "report leaks \(secret)")
            #expect(!share.contains(secret), "share text leaks \(secret)")
        }
        #expect(json.contains(#""serverProtocol":"v2""#))
    }

    @Test func report_attachmentNamesTypesAndUploadedSizes() throws {
        let submission = Self.submission(attachments: [
            .screenshot(Data(repeating: 1, count: 312)),
            .logs(Data(repeating: 2, count: 41)),
        ])

        let attachments = try #require(
            FeedbackPayloadBuilder.report(submission, uid: "u")["attachments"] as? [[String: Any]]
        )

        #expect(attachments.count == 2)
        #expect(attachments[0]["name"] as? String == "screenshot.jpg")
        #expect(attachments[0]["contentType"] as? String == "image/jpeg")
        #expect(attachments[0]["size"] as? Int == 312)
        #expect(attachments[1]["name"] as? String == "logs.jsonl.gz")
        #expect(attachments[1]["contentType"] as? String == "application/gzip")
        #expect(attachments[1]["size"] as? Int == 41)
    }

    @Test func context_omitsUnknownServerAndAlwaysSendsEngine() {
        var metadata = Self.metadata
        metadata.serverProtocol = nil
        metadata.serverVersion = nil
        metadata.screen = .settings

        let context = FeedbackPayloadBuilder.context(metadata)

        #expect(context == ["screen": "settings", "engine": "opencode"])
    }

    @Test func app_clipsFieldsTo64Characters() {
        var metadata = Self.metadata
        metadata.device = String(repeating: "x", count: 80)

        #expect(FeedbackPayloadBuilder.app(metadata)["device"]?.count == 64)
    }

    @Test func firestoreFields_useTypedValues() throws {
        let fields = FeedbackPayloadBuilder.firestoreFields(
            Self.submission(attachments: [.logs(Data(count: 7))]),
            uid: "anon-uid"
        )

        #expect((fields["uid"] as? [String: String]) == ["stringValue": "anon-uid"])
        let app = try #require((fields["app"] as? [String: Any])?["mapValue"] as? [String: Any])
        let appFields = try #require(app["fields"] as? [String: Any])
        #expect((appFields["platform"] as? [String: String]) == ["stringValue": "ios"])
        let list = try #require((fields["attachments"] as? [String: Any])?["arrayValue"] as? [String: Any])
        let first = try #require((list["values"] as? [[String: Any]])?.first?["mapValue"] as? [String: Any])
        let size = try #require((first["fields"] as? [String: Any])?["size"] as? [String: String])
        #expect(size == ["integerValue": "7"])
        #expect(fields["createdAt"] == nil)
    }

    @Test func reference_isFirstSixHexCharactersUppercased() {
        #expect(FeedbackReference.make(reportID: "1f0de9c2-7a41-4b8e-9d3f-0c5e6a7b8c9d") == "1F0DE9")
        let generated = FeedbackReference.newReportID()
        #expect(generated == generated.lowercased())
        #expect(UUID(uuidString: generated) != nil)
    }

    @Test func sharePayload_carriesMessageReferenceAndFiles() {
        let submission = Self.submission(attachments: [.logs(Data(count: 3))])

        let payload = FeedbackSharePayloadBuilder.payload(for: submission)

        #expect(payload.text.contains("Ref 1F0DE9"))
        #expect(payload.text.contains(submission.message))
        #expect(payload.files == submission.attachments)
    }

    // MARK: - Fixtures

    static let metadata = FeedbackMetadata(
        version: "1.0.1",
        build: "214",
        osVersion: "iOS 26.1",
        device: "iPhone17,1",
        locale: "en-GB",
        screen: .sessionChat,
        serverProtocol: .v2,
        serverVersion: "1.3.2"
    )

    static func submission(
        metadata: FeedbackMetadata = metadata,
        attachments: [FeedbackAttachmentFile] = [
            .screenshot(Data(count: 10)),
            .logs(Data(count: 5)),
        ]
    ) -> FeedbackSubmission {
        FeedbackSubmission(
            reportID: "1f0de9c2-7a41-4b8e-9d3f-0c5e6a7b8c9d",
            kind: .bug,
            message: "The session list still shows Running after unlock.",
            metadata: metadata,
            attachments: attachments
        )
    }

    /// `shared/feedback/report.example.json`, the report contract.
    static func exampleReport() throws -> [String: Any] {
        var url = URL(fileURLWithPath: #filePath)
        while url.pathComponents.count > 1, !FileManager.default.fileExists(
            atPath: url.appendingPathComponent("shared/feedback/report.example.json").path
        ) {
            url.deleteLastPathComponent()
        }
        let data = try Data(contentsOf: url.appendingPathComponent("shared/feedback/report.example.json"))
        return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    /// Same keys (for maps) and the same JSON types, recursively.
    static func expectSameShape(_ actual: Any, _ expected: Any, path: String) throws {
        switch expected {
        case let expectedMap as [String: Any]:
            let actualMap = try #require(actual as? [String: Any], "\(path) is not a map")
            for (key, value) in actualMap {
                let expectedValue = try #require(expectedMap[key], "\(path).\(key) is not in the example")
                try expectSameShape(value, expectedValue, path: "\(path).\(key)")
            }
            if !path.isEmpty {
                #expect(Set(actualMap.keys) == Set(expectedMap.keys), "\(path) keys differ")
            }
        case let expectedList as [Any]:
            let actualList = try #require(actual as? [Any], "\(path) is not a list")
            if let template = expectedList.first {
                for (index, element) in actualList.enumerated() {
                    try expectSameShape(element, template, path: "\(path)[\(index)]")
                }
            }
        case is String:
            #expect(actual is String, "\(path) is not a string")
        case let number as NSNumber:
            #expect(actual is Int, "\(path) is not an integer")
            #expect(CFNumberIsFloatType(number) == false)
        default:
            Issue.record("Unexpected example type at \(path)")
        }
    }
}
