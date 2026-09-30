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
        let nsError = error as NSError
        let message = firstLine(error.localizedDescription)
        if nsError.domain == NSURLErrorDomain {
            let code = "NSURLErrorDomain:\(nsError.code)"
            return message.isEmpty ? code : "\(code): \(message)"
        }
        let name = String(describing: type(of: error))
        if message.isEmpty || message == name {
            return name
        }
        return "\(name): \(message)"
    }

    private static func underlying(_ error: Error) -> Error? {
        let nsError = error as NSError
        return nsError.userInfo[NSUnderlyingErrorKey] as? Error
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
