import Foundation
import OpenCodeKit

extension MessageError {
    /// "Request failed": the app's own text for an HTTP error the server sent no readable message for.
    nonisolated static var requestFailedText: String {
        String(localized: "session_chat.activity.request_failed", defaultValue: "Request failed")
    }

    /// The text the app shows for this error. Server and transport text stays verbatim;
    /// an HTTP error without a message reads the keyed "Request failed".
    nonisolated var displayMessage: String {
        switch self {
        case let .transport(detail):
            detail.message
        case let .http(_, message):
            message ?? Self.requestFailedText
        case let .decoding(message):
            message
        }
    }
}
