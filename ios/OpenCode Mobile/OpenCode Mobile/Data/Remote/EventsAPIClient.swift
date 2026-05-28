import Foundation

final class EventsAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?

    init(connectionProvider: @escaping @Sendable () -> ServerConnection?) {
        self.connectionProvider = connectionProvider
    }

    func streamGlobalEvents(onDataLine: @escaping @Sendable (String) async -> Void) async throws {
        guard let connection = connectionProvider() else {
            throw EventsAPIError.noActiveConnection
        }
        var request = connection.authorizedRequest(path: "global/event")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")

        let (bytes, response) = try await URLSession.shared.bytes(for: request)
        guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
            throw EventsAPIError.invalidResponse
        }

        var dataBuffer = ""
        for try await line in bytes.lines {
            if line.isEmpty {
                if !dataBuffer.isEmpty {
                    await onDataLine(dataBuffer)
                    dataBuffer = ""
                }
                continue
            }
            if line.hasPrefix("data:") {
                let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                if !dataBuffer.isEmpty {
                    dataBuffer.append("\n")
                }
                dataBuffer.append(payload)
            }
        }
        if !dataBuffer.isEmpty {
            await onDataLine(dataBuffer)
        }
    }
}

enum EventsAPIError: Error {
    case invalidResponse
    case noActiveConnection
}
