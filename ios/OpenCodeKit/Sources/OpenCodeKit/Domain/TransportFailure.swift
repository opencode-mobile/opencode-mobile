import Foundation

/// Transport text kept on mapped domain errors, plus the URL error identity
/// `mapError` would otherwise drop.
public nonisolated struct TransportFailure: Equatable, Sendable, ExpressibleByStringLiteral {
    public var message: String
    public var urlErrorDomain: String?
    public var urlErrorCode: Int?
    public var urlErrorMessage: String?

    public init(
        message: String,
        urlErrorDomain: String? = nil,
        urlErrorCode: Int? = nil,
        urlErrorMessage: String? = nil
    ) {
        self.message = message
        self.urlErrorDomain = urlErrorDomain
        self.urlErrorCode = urlErrorCode
        self.urlErrorMessage = urlErrorMessage
    }

    public init(stringLiteral value: String) {
        self.init(message: value)
    }

    public static func captured(from error: Error) -> TransportFailure {
        let nsError = error as NSError
        let message = error.localizedDescription
        if nsError.domain == NSURLErrorDomain {
            return TransportFailure(
                message: message,
                urlErrorDomain: nsError.domain,
                urlErrorCode: nsError.code,
                urlErrorMessage: message
            )
        }
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError,
           underlying.domain == NSURLErrorDomain {
            return TransportFailure(
                message: message,
                urlErrorDomain: underlying.domain,
                urlErrorCode: underlying.code,
                urlErrorMessage: underlying.localizedDescription
            )
        }
        return TransportFailure(message: message)
    }
}
