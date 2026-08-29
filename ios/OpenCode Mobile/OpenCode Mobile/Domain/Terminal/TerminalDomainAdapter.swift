import Foundation

struct TerminalDomainAdapter: Sendable {
    func session(from dto: PtyDTO) -> TerminalSession {
        TerminalSession(
            id: dto.id,
            title: dto.title,
            command: dto.command,
            cwd: dto.cwd,
            status: dto.status.lowercased() == "running" ? .running : .exited
        )
    }

    func mapError(_ error: Error) -> TerminalError {
        if let terminalError = error as? TerminalError {
            return terminalError
        }
        if let apiError = error as? PtyAPIError {
            switch apiError {
            case .invalidResponse, .noActiveConnection:
                return .transport("Invalid response")
            case let .httpStatus(code, message):
                return .http(statusCode: code, message: message)
            }
        }
        if error is DecodingError {
            return .decoding(error.localizedDescription)
        }
        return .transport(error.localizedDescription)
    }
}
