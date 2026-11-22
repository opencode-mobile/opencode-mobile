import Foundation

nonisolated enum MessageImageError: Error, Equatable {
    /// No message to ask the server for.
    case notFound
    /// The server's copy of the message has no picture for the image.
    case unavailable
}

/// Finds the full picture behind a timeline image (a tool-returned image, `<toolPartID>:file:<n>`,
/// or a file part): from the in-memory message when it still has the data, otherwise from the
/// server's copy of that one message, which is then hydrated back into the store.
nonisolated struct MessageImageLoader: Sendable {
    /// In-memory messages to look in: the one with `messageID`, or all when it is unknown.
    let cachedMessages: @Sendable (_ messageID: String?) -> [Message]
    let fetchMessage: @Sendable (_ messageID: String) async throws -> Message
    let hydrate: @Sendable (_ message: Message) -> Void

    /// `messageID` is a hint: a merged timeline row names its first message, while the image can
    /// belong to a later one, so the message actually holding the image wins.
    func fullImage(messageID: String?, imageID: String) async throws -> FilePart {
        let holdsImage: (Message) -> Bool = { Self.image(in: $0, id: imageID) != nil }
        let owner = cachedMessages(messageID).first { (messageID == nil || $0.id == messageID) && holdsImage($0) }
            ?? (messageID == nil ? nil : cachedMessages(nil).first(where: holdsImage))
        let cached = owner.flatMap { Self.image(in: $0, id: imageID) }
        if let cached, Self.isFull(cached) { return cached }
        guard let id = owner?.id ?? messageID else { throw MessageImageError.notFound }
        let fetched = try await fetchMessage(id)
        guard let image = Self.image(in: fetched, id: imageID), Self.isFull(image) else {
            throw MessageImageError.unavailable
        }
        hydrate(fetched)
        return cached.map { MessageMedia.merge(prior: $0, next: image) } ?? image
    }

    /// The image with `id` among the message's tool images and file parts.
    static func image(in message: Message, id: String) -> FilePart? {
        for part in message.parts {
            switch part {
            case .tool(let tool):
                if let image = tool.images.first(where: { $0.id == id }) { return image }
            case .file(let file) where file.id == id:
                return file
            default:
                continue
            }
        }
        return nil
    }

    /// The picture itself is reachable: inline data or a server file.
    static func isFull(_ image: FilePart) -> Bool {
        MessageMedia.hasFullData(image) || image.serverPath != nil
    }
}
