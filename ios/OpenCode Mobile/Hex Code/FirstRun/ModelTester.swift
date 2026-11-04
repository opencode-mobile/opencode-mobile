import Foundation
import OpenCodeKit

/// Sends the one-line test prompt through OpenCode's stateless generate route, so a model that
/// cannot answer (no API key, provider off) is caught before the first real prompt.
protocol ModelTesting: Sendable {
    func test(_ choice: ModelChoice) async -> ModelTestState
}

struct ModelTester: ModelTesting {
    static let prompt = "Reply with OK"

    let connection: ServerConnection
    var urlSession: URLSession = .shared

    func test(_ choice: ModelChoice) async -> ModelTestState {
        var request = connection.authorizedRequest(path: "api/experimental/generate", method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // The model test can wait on a slow provider; the shared REST pool gives up sooner.
        request.timeoutInterval = 60
        request.httpBody = try? JSONEncoder().encode(Self.body(for: choice))
        let started = Date()
        do {
            let (data, response) = try await urlSession.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            return Self.result(status: status, data: data, seconds: Date().timeIntervalSince(started))
        } catch is CancellationError {
            return .notRun
        } catch {
            return .failed(ModelTestMessages.unreachable)
        }
    }

    struct Body: Encodable, Equatable {
        struct Model: Encodable, Equatable {
            let id: String
            let providerID: String
            let variant: String?
        }

        let prompt: String
        let model: Model
    }

    static func body(for choice: ModelChoice) -> Body {
        Body(prompt: prompt, model: .init(id: choice.modelID, providerID: choice.providerID, variant: choice.variant))
    }

    /// 200 with any text passes: the model answered. Anything else fails with the server's own
    /// message when it sent one, since that names the cause (for example a missing API key).
    static func result(status: Int, data: Data, seconds: Double) -> ModelTestState {
        struct Success: Decodable {
            struct Payload: Decodable { let text: String }
            let data: Payload
        }
        struct Failure: Decodable {
            let message: String?
            let data: Inner?
            struct Inner: Decodable { let message: String? }
        }
        if (200 ..< 300).contains(status) {
            guard let reply = try? JSONDecoder().decode(Success.self, from: data),
                  !reply.data.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else { return .failed(ModelTestMessages.emptyReply) }
            return .passed(seconds: seconds)
        }
        let failure = try? JSONDecoder().decode(Failure.self, from: data)
        if let message = (failure?.message ?? failure?.data?.message).map(firstLine), !message.isEmpty {
            return .failed(message)
        }
        if status == 401 || status == 403 { return .failed(ModelTestMessages.signedOut) }
        return .failed(ModelTestMessages.httpStatus(status))
    }

    private static func firstLine(_ text: String) -> String {
        let line = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? text
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.count > 200 ? String(trimmed.prefix(200)) + "…" : trimmed
    }
}

enum ModelTestMessages {
    static var unreachable: String {
        String(localized: "model.test.unreachable", defaultValue: "Couldn’t reach your computer. Check it’s on and try again.")
    }

    static var emptyReply: String {
        String(localized: "model.test.empty_reply", defaultValue: "The model answered with nothing. Try again or pick another model.")
    }

    static var signedOut: String {
        String(localized: "model.test.signed_out", defaultValue: "Your computer turned the phone away. Pair again.")
    }

    static func httpStatus(_ status: Int) -> String {
        String(localized: "model.test.http_status", defaultValue: "The test failed (HTTP \(status)). Try again or pick another model.")
    }
}
