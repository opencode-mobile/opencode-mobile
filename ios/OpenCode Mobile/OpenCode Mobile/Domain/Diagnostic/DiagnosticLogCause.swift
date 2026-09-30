import Foundation

nonisolated enum DiagnosticLogCause: Sendable {
    private static let maxDepth = 4
    private static let maxFrameMessage = 160

    static func summarize(_ error: Error) -> String {
        var parts: [String] = []
        var current: Error? = error
        var seen: Set<ObjectIdentifier> = []
        while let unwrapped = current, parts.count < maxDepth {
            let identity = ObjectIdentifier(unwrapped as AnyObject)
            if seen.contains(identity) { break }
            seen.insert(identity)
            parts.append(frame(unwrapped))
            current = underlying(unwrapped)
        }
        return "cause=" + parts.joined(separator: " <- ")
    }

    static func appendTo(_ message: String, error: Error?) -> String {
        guard let error else { return message }
        let summary = summarize(error)
        if message.contains(summary) { return message }
        return "\(message) \(summary)"
    }

    private static func frame(_ error: Error) -> String {
        if let mapped = mappedFrame(error) {
            return labeled(mapped.name, mapped.message)
        }
        let nsError = error as NSError
        let message = firstLine(error.localizedDescription)
        if nsError.domain == NSURLErrorDomain {
            let code = "NSURLErrorDomain:\(nsError.code)"
            return message.isEmpty ? code : "\(code): \(message)"
        }
        let name = String(describing: type(of: error))
        return labeled(name, message)
    }

    private static func underlying(_ error: Error) -> Error? {
        if let mapped = mappedFrame(error),
           let domain = mapped.urlErrorDomain,
           let code = mapped.urlErrorCode {
            return NSError(
                domain: domain,
                code: code,
                userInfo: [NSLocalizedDescriptionKey: mapped.urlErrorMessage ?? mapped.message]
            )
        }
        let nsError = error as NSError
        return nsError.userInfo[NSUnderlyingErrorKey] as? Error
    }

    private static func labeled(_ name: String, _ message: String) -> String {
        let line = firstLine(message)
        if line.isEmpty || line == name {
            return name
        }
        return "\(name): \(line)"
    }

    private struct MappedFrame {
        var name: String
        var message: String
        var urlErrorDomain: String?
        var urlErrorCode: Int?
        var urlErrorMessage: String?
    }

    private static func mappedFrame(_ error: Error) -> MappedFrame? {
        if let messageError = error as? MessageError {
            return mappedFrame(name: "MessageError", error: messageError)
        }
        if let commandError = error as? CommandError {
            return mappedFrame(name: "CommandError", error: commandError)
        }
        return nil
    }

    private static func mappedFrame(name: String, error: MessageError) -> MappedFrame {
        switch error {
        case let .transport(detail):
            return transportFrame(name: name, detail: detail)
        case let .http(statusCode, message):
            return httpFrame(name: name, statusCode: statusCode, message: message)
        case let .decoding(message):
            return MappedFrame(name: name, message: message, urlErrorDomain: nil, urlErrorCode: nil, urlErrorMessage: nil)
        }
    }

    private static func mappedFrame(name: String, error: CommandError) -> MappedFrame {
        switch error {
        case let .transport(detail):
            return transportFrame(name: name, detail: detail)
        case let .http(statusCode, message):
            return httpFrame(name: name, statusCode: statusCode, message: message)
        case let .decoding(message):
            return MappedFrame(name: name, message: message, urlErrorDomain: nil, urlErrorCode: nil, urlErrorMessage: nil)
        }
    }

    private static func transportFrame(name: String, detail: TransportFailure) -> MappedFrame {
        MappedFrame(
            name: name,
            message: detail.message,
            urlErrorDomain: detail.urlErrorDomain,
            urlErrorCode: detail.urlErrorCode,
            urlErrorMessage: detail.urlErrorMessage
        )
    }

    private static func httpFrame(name: String, statusCode: Int, message: String?) -> MappedFrame {
        MappedFrame(
            name: name,
            message: httpMessage(statusCode: statusCode, message: message),
            urlErrorDomain: nil,
            urlErrorCode: nil,
            urlErrorMessage: nil
        )
    }

    private static func httpMessage(statusCode: Int, message: String?) -> String {
        guard let message, !message.isEmpty else { return "HTTP \(statusCode)" }
        return "HTTP \(statusCode): \(message)"
    }

    private static func firstLine(_ text: String) -> String {
        let line = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? text
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count <= maxFrameMessage {
            return trimmed
        }
        return String(trimmed.prefix(maxFrameMessage)) + "…"
    }
}
