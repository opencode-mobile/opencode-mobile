import Foundation
import Testing
@testable import OpenCode_Mobile

struct DiagnosticLogCauseTests {
    @Test func summarizeIncludesTypeAndMessage() {
        let error = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorCannotConnectToHost,
            userInfo: [NSLocalizedDescriptionKey: "Could not connect to the server."]
        )
        let summary = DiagnosticLogCause.summarize(error)
        #expect(summary.contains("cause="))
        #expect(summary.contains("NSURLErrorDomain:\(NSURLErrorCannotConnectToHost)"))
        #expect(summary.contains("Could not connect to the server."))
    }

    @Test func summarizeWalksUnderlyingError() {
        let root = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorNetworkConnectionLost,
            userInfo: [NSLocalizedDescriptionKey: "The network connection was lost."]
        )
        let wrapper = NSError(
            domain: "OpenCodeTests",
            code: 1,
            userInfo: [
                NSLocalizedDescriptionKey: "Transport",
                NSUnderlyingErrorKey: root,
            ]
        )
        let summary = DiagnosticLogCause.summarize(wrapper)
        #expect(summary.contains("Transport"))
        #expect(summary.contains("NSURLErrorDomain:\(NSURLErrorNetworkConnectionLost)"))
        #expect(summary.contains("The network connection was lost."))
        #expect(summary.contains(" <- "))
    }

    @Test func summarizeUsesFirstLineOfMultilineMessage() {
        let error = NSError(
            domain: "OpenCodeTests",
            code: 2,
            userInfo: [NSLocalizedDescriptionKey: "first line\nsecond line"]
        )
        let summary = DiagnosticLogCause.summarize(error)
        #expect(summary.contains("first line"))
        #expect(!summary.contains("second line"))
    }

    @Test func appendToLeavesMessageAloneWithoutError() {
        #expect(DiagnosticLogCause.appendTo("sendPrompt failed", error: nil) == "sendPrompt failed")
    }

    @Test func appendToAddsCauseSuffix() {
        let error = NSError(
            domain: "OpenCodeTests",
            code: 3,
            userInfo: [NSLocalizedDescriptionKey: "Failed to connect"]
        )
        let combined = DiagnosticLogCause.appendTo("sendPrompt failed", error: error)
        #expect(combined.hasPrefix("sendPrompt failed cause="))
        #expect(combined.contains("Failed to connect"))
    }

    @Test func summarizeReadsMappedTransportText() {
        let message: Error = MessageError.transport("connection refused")
        let command: Error = CommandError.transport("connection refused")

        #expect(DiagnosticLogCause.summarize(message) == "cause=MessageError: connection refused")
        #expect(DiagnosticLogCause.summarize(command) == "cause=CommandError: connection refused")
        #expect(!DiagnosticLogCause.summarize(message).contains("couldn"))
    }

    @Test func summarizeReadsMappedHttpAndDecodingText() {
        let http: Error = MessageError.http(statusCode: 503, message: "busy")
        let httpWithoutMessage: Error = CommandError.http(statusCode: 404, message: nil)
        let decoding: Error = CommandError.decoding("bad json")

        #expect(DiagnosticLogCause.summarize(http) == "cause=MessageError: HTTP 503: busy")
        #expect(DiagnosticLogCause.summarize(httpWithoutMessage) == "cause=CommandError: HTTP 404")
        #expect(DiagnosticLogCause.summarize(decoding) == "cause=CommandError: bad json")
    }

    @Test func summarizeKeepsURLErrorCodeAfterDomainMapping() {
        let urlError = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorCannotConnectToHost,
            userInfo: [NSLocalizedDescriptionKey: "Could not connect to the server."]
        )
        let messageError = MessageDomainAdapter().mapError(urlError)
        let commandError = CommandDomainAdapter().mapError(urlError)

        #expect(messageError.localizedDescription == "Could not connect to the server.")
        for error in [messageError as Error, commandError as Error] {
            let summary = DiagnosticLogCause.summarize(error)
            #expect(summary.contains("Could not connect to the server."))
            #expect(summary.contains("NSURLErrorDomain:\(NSURLErrorCannotConnectToHost)"))
            #expect(summary.contains(" <- "))
        }
    }

    @Test func summarizeKeepsUnderlyingURLErrorCodeAfterDomainMapping() {
        let root = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorNetworkConnectionLost,
            userInfo: [NSLocalizedDescriptionKey: "The network connection was lost."]
        )
        let wrapper = NSError(
            domain: "OpenCode",
            code: 1,
            userInfo: [
                NSLocalizedDescriptionKey: "Transport",
                NSUnderlyingErrorKey: root,
            ]
        )
        let summary = DiagnosticLogCause.summarize(MessageDomainAdapter().mapError(wrapper))

        #expect(summary.contains("MessageError: Transport"))
        #expect(summary.contains("NSURLErrorDomain:\(NSURLErrorNetworkConnectionLost)"))
        #expect(summary.contains("The network connection was lost."))
    }

    @Test func appendToDoesNotDuplicateExistingSummary() {
        let error = NSError(
            domain: "OpenCodeTests",
            code: 4,
            userInfo: [NSLocalizedDescriptionKey: "Failed to connect"]
        )
        let once = DiagnosticLogCause.appendTo("sendPrompt failed", error: error)
        #expect(DiagnosticLogCause.appendTo(once, error: error) == once)
    }
}
