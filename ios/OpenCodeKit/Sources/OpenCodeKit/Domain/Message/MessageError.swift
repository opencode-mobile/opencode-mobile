import Foundation

public nonisolated enum MessageError: Error, Equatable {
    case transport(TransportFailure)
    case http(statusCode: Int, message: String?)
    case decoding(String)

    public var localizedDescription: String {
        switch self {
        case let .transport(detail):
            detail.message
        case let .http(_, message):
            message ?? "Request failed"
        case let .decoding(message):
            message
        }
    }
}
