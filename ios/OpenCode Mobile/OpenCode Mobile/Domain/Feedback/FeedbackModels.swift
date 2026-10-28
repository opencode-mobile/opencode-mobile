import CoreGraphics
import Foundation

/// What the user is reporting (#721). The wire value is the report `kind`.
nonisolated enum FeedbackKind: String, CaseIterable, Sendable {
    case bug
    case feedback

    var label: String {
        switch self {
        case .bug: "Bug"
        case .feedback: "Idea"
        }
    }

    var placeholder: String {
        switch self {
        case .bug: "What happened? What did you expect?"
        case .feedback: "What would make OpenCode better for you?"
        }
    }

    var sentBody: String {
        switch self {
        case .bug: "The team will look into it."
        case .feedback: "Thanks for the idea."
        }
    }
}

/// Screen the report was opened from. The wire value is `context.screen`.
nonisolated enum FeedbackScreen: String, Sendable {
    case settings
    case sessionChat = "session-chat"
}

/// A screenshot attached to a draft. `imageData` is the image as captured or
/// picked; `hiddenRects` are the covered areas in unit coordinates of the image
/// (0...1 on both axes) and are burned into the pixels when the report is sent.
nonisolated struct FeedbackScreenshot: Equatable, Sendable {
    let imageData: Data
    var hiddenRects: [CGRect] = []
}

nonisolated struct FeedbackDraft: Equatable, Sendable {
    static let messageLimit = 5_000

    var kind: FeedbackKind = .bug
    var message: String = ""
    var screenshot: FeedbackScreenshot?
    var includeLogs = true
    var logWindow: DiagnosticLogExportWindow = .default
    var screen: FeedbackScreen

    var trimmedMessage: String {
        message.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Device, app and server context sent with a report. Never carries the server
/// address, username, password or profile name.
nonisolated struct FeedbackMetadata: Equatable, Sendable {
    static let platform = "ios"
    static let engine = "opencode"
    /// Rules cap every `app` and `context` string at 64 characters.
    static let maxFieldLength = 64

    var version: String
    var build: String
    var osVersion: String
    var device: String
    var locale: String
    var screen: FeedbackScreen
    var serverProtocol: ServerProtocol?
    var serverVersion: String?
}

/// One uploaded file. `size` is the uploaded byte count.
nonisolated struct FeedbackAttachmentFile: Equatable, Sendable {
    static let screenshotName = "screenshot.jpg"
    static let screenshotContentType = "image/jpeg"
    static let logsName = "logs.jsonl.gz"
    static let logsContentType = "application/gzip"
    /// Storage rules reject files over 1 MB.
    static let maxBytes = 1_000_000

    let name: String
    let contentType: String
    let data: Data

    var size: Int { data.count }

    static func screenshot(_ data: Data) -> FeedbackAttachmentFile {
        FeedbackAttachmentFile(name: screenshotName, contentType: screenshotContentType, data: data)
    }

    static func logs(_ data: Data) -> FeedbackAttachmentFile {
        FeedbackAttachmentFile(name: logsName, contentType: logsContentType, data: data)
    }
}

/// A ready-to-send report: the user's words, context and encoded files.
nonisolated struct FeedbackSubmission: Equatable, Sendable {
    /// Lowercase UUID v4; also the Storage folder name.
    let reportID: String
    let kind: FeedbackKind
    let message: String
    let metadata: FeedbackMetadata
    let attachments: [FeedbackAttachmentFile]

    var reference: String { FeedbackReference.make(reportID: reportID) }
}

nonisolated enum FeedbackReference {
    static func newReportID() -> String {
        UUID().uuidString.lowercased()
    }

    /// First 6 hex characters of the report ID, uppercased (`1F0DE9`).
    static func make(reportID: String) -> String {
        String(reportID.filter(\.isHexDigit).prefix(6)).uppercased()
    }
}

/// What the share sheet gets when a report cannot go to the backend.
nonisolated struct FeedbackSharePayload: Equatable, Sendable {
    let text: String
    let files: [FeedbackAttachmentFile]
}

nonisolated enum FeedbackSubmitResult: Equatable, Sendable {
    case sent(reference: String)
    case offline
    case rateLimited(retryAfterSeconds: Int)
    case unverified
    case failed(message: String)
    /// Debug builds (and builds without Firebase) hand the report to the share sheet.
    case shareInstead(FeedbackSharePayload)
}

/// Domain seam for sending a report. Feature code never imports Firebase.
nonisolated protocol FeedbackSubmitter: Sendable {
    func submit(_ submission: FeedbackSubmission) async -> FeedbackSubmitResult
}

/// Debug submitter: never touches the network; the screen opens the share sheet.
nonisolated struct ShareFeedbackSubmitter: FeedbackSubmitter {
    func submit(_ submission: FeedbackSubmission) async -> FeedbackSubmitResult {
        .shareInstead(FeedbackSharePayloadBuilder.payload(for: submission))
    }
}
