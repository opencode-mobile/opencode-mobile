import Foundation

/// Thumbnails for image file parts that arrive as data URLs with no preview
/// (the server echo of a sent photo, history reloads). Warmed off-main by the
/// timeline projection; block building only reads, so main-thread paths never
/// decode a full image.
nonisolated final class AttachmentPreviewCache: @unchecked Sendable {
    static let shared = AttachmentPreviewCache()

    /// Callers warm only the newest messages, so a long image-heavy history
    /// does not hold back first paint.
    static let warmMessageCount = 6

    private struct Key: Hashable {
        let id: String
        let dataURLLength: Int
    }

    private enum Entry {
        case preview(Data)
        case unavailable
    }

    private let lock = NSLock()
    private let maxEntries: Int
    private var entries: [Key: Entry] = [:]
    private var order: [Key] = []

    init(maxEntries: Int = 48) {
        self.maxEntries = maxEntries
    }

    /// `file` with a cached preview attached, or `file` unchanged.
    func resolved(_ file: FilePart) -> FilePart {
        guard file.previewData == nil, let key = key(for: file) else { return file }
        lock.lock()
        let entry = entries[key]
        lock.unlock()
        guard case .preview(let data) = entry else { return file }
        return FilePart(
            id: file.id,
            filename: file.filename,
            mime: file.mime,
            kind: file.kind,
            dataURL: file.dataURL,
            previewData: data,
            serverPath: file.serverPath
        )
    }

    /// Decodes missing previews. Call off the main actor.
    func warm(_ messages: [Message]) {
        for message in messages {
            for case .file(let file) in message.parts {
                guard file.previewData == nil, let key = key(for: file), !contains(key) else { continue }
                let preview = AttachmentDataURL.decodedData(from: file.dataURL, expectedMime: file.mime)
                    .flatMap(AttachmentPreview.make(data:))
                store(preview.map(Entry.preview) ?? .unavailable, for: key)
            }
        }
    }

    func removeAll() {
        lock.lock()
        defer { lock.unlock() }
        entries.removeAll()
        order.removeAll()
    }

    private func key(for file: FilePart) -> Key? {
        guard file.kind == .image, !file.dataURL.isEmpty else { return nil }
        return Key(id: file.id, dataURLLength: file.dataURL.utf8.count)
    }

    private func contains(_ key: Key) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return entries[key] != nil
    }

    private func store(_ entry: Entry, for key: Key) {
        lock.lock()
        defer { lock.unlock() }
        if entries.updateValue(entry, forKey: key) == nil {
            order.append(key)
        }
        while order.count > maxEntries {
            entries.removeValue(forKey: order.removeFirst())
        }
    }
}
