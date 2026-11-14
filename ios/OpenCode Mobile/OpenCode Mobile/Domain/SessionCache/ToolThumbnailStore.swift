import CryptoKit
import Foundation

/// How much disk tool-image thumbnails may take.
nonisolated struct ToolThumbnailLimits: Sendable, Equatable {
    /// Only the newest images of a session keep a thumbnail; older ones get none on disk.
    var perSessionCount = 24
    var perSessionBytes = 1_000_000
    /// Over `globalBytes`, least recently viewed files go until the folder is at `globalTargetBytes`.
    var globalBytes: Int64 = 50 * 1024 * 1024
    var globalTargetBytes: Int64 = 40 * 1024 * 1024
}

nonisolated struct ToolThumbnailCacheStats: Sendable, Equatable {
    let bytes: Int64
    let files: Int
    let evicted: Int
}

/// Thumbnails of tool-returned images, as JPEG files beside the session cache:
/// `<Caches>/tool-thumbs/<connection hash>/<session id>/<image id>.jpg`. The cached message JSON
/// keeps only image metadata and a flag; a missing file just means "no thumbnail". The full
/// picture never touches disk. "Viewed" is the file's modification date, touched on read.
actor ToolThumbnailStore {
    static let directoryName = "tool-thumbs"

    static func defaultRoot() -> URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent(directoryName, isDirectory: true)
    }

    private let root: URL
    private let limits: ToolThumbnailLimits
    private let evictionDelay: Duration
    private let makeThumbnail: @Sendable (FilePart) -> Data?
    private var evictionTask: Task<Void, Never>?
    /// Files whose image made no thumbnail (does not decode, too large): not retried each flush.
    private var unavailable: Set<String> = []

    init(
        root: URL,
        limits: ToolThumbnailLimits = ToolThumbnailLimits(),
        evictionDelay: Duration = .seconds(2),
        makeThumbnail: @escaping @Sendable (FilePart) -> Data? = { image in
            ToolThumbnail.make(dataURL: image.dataURL, mime: image.mime)
        }
    ) {
        self.root = root
        self.limits = limits
        self.evictionDelay = evictionDelay
        self.makeThumbnail = makeThumbnail
    }

    // MARK: - Paths

    /// A connection id is a profile id or a server URL; only its hash reaches a path.
    nonisolated static func connectionComponent(_ connectionID: String) -> String {
        hash(connectionID, length: 32)
    }

    /// Ids keep their safe characters; anything else (`:`, `/`) becomes `_`, and a short hash
    /// of the original keeps two ids that sanitise alike apart.
    nonisolated static func fileComponent(_ value: String) -> String {
        let safe = String(value.unicodeScalars.map { scalar -> Character in
            let isSafe = (scalar.isASCII && CharacterSet.alphanumerics.contains(scalar)) || scalar == "-" || scalar == "_"
            return isSafe ? Character(scalar) : "_"
        })
        guard safe != value || safe.isEmpty || safe.count > 100 else { return safe }
        return "\(safe.prefix(100))-\(hash(value, length: 8))"
    }

    nonisolated func sessionDirectory(connectionID: String, sessionID: String) -> URL {
        root
            .appendingPathComponent(Self.connectionComponent(connectionID), isDirectory: true)
            .appendingPathComponent(Self.fileComponent(sessionID), isDirectory: true)
    }

    nonisolated func fileURL(connectionID: String, sessionID: String, imageID: String) -> URL {
        sessionDirectory(connectionID: connectionID, sessionID: sessionID)
            .appendingPathComponent(Self.fileComponent(imageID) + ".jpg", isDirectory: false)
    }

    private nonisolated static func hash(_ value: String, length: Int) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined().prefix(length).description
    }

    // MARK: - Write and read

    /// Keeps thumbnails for the newest tool images of `messages` within the per-session budget
    /// (writing missing ones from the image's full data or in-memory thumbnail) and deletes the
    /// session's other files. Returns the image ids that have a thumbnail on disk.
    @discardableResult
    func persist(connectionID: String, sessionID: String, messages: [Message]) -> Set<String> {
        let fileManager = FileManager.default
        let directory = sessionDirectory(connectionID: connectionID, sessionID: sessionID)
        let newestFirst = messages.flatMap(\.toolImages).filter { $0.kind == .image }.reversed()
        var keptIDs = Set<String>()
        var keptNames = Set<String>()
        var bytes = 0
        var wrote = false
        for image in newestFirst {
            guard keptIDs.count < limits.perSessionCount else { break }
            guard !keptIDs.contains(image.id) else { continue }
            let url = fileURL(connectionID: connectionID, sessionID: sessionID, imageID: image.id)
            let size: Int
            if let existing = Self.fileSize(url) {
                size = existing
            } else {
                guard !unavailable.contains(url.path), let data = thumbnailData(for: image) else {
                    if MessageMedia.hasFullData(image) { unavailable.insert(url.path) }
                    continue
                }
                guard bytes + data.count <= limits.perSessionBytes else { break }
                do {
                    try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
                    try data.write(to: url, options: .atomic)
                } catch {
                    continue
                }
                size = data.count
                wrote = true
            }
            guard bytes + size <= limits.perSessionBytes else { break }
            bytes += size
            keptIDs.insert(image.id)
            keptNames.insert(url.lastPathComponent)
        }
        if let names = try? fileManager.contentsOfDirectory(atPath: directory.path) {
            for name in names where !keptNames.contains(name) {
                try? fileManager.removeItem(at: directory.appendingPathComponent(name))
            }
        }
        if keptIDs.isEmpty {
            try? fileManager.removeItem(at: directory)
        }
        if wrote { scheduleEviction() }
        return keptIDs
    }

    /// `messages` with the thumbnail of each tool image in `imageIDs` read from disk (and its
    /// file touched as viewed). An image whose file is gone keeps no thumbnail.
    func attach(connectionID: String, sessionID: String, messages: [Message], imageIDs: Set<String>) -> [Message] {
        guard !imageIDs.isEmpty else { return messages }
        return messages.map { message in
            var changed = false
            let parts = message.parts.map { part -> MessagePart in
                guard case .tool(let tool) = part, tool.images.contains(where: { imageIDs.contains($0.id) }) else {
                    return part
                }
                let images = tool.images.map { image -> FilePart in
                    guard image.previewData == nil, imageIDs.contains(image.id),
                          let data = read(fileURL(connectionID: connectionID, sessionID: sessionID, imageID: image.id))
                    else { return image }
                    changed = true
                    return FilePart(
                        id: image.id,
                        filename: image.filename,
                        mime: image.mime,
                        kind: image.kind,
                        dataURL: image.dataURL,
                        previewData: data,
                        serverPath: image.serverPath
                    )
                }
                return .tool(tool.withImages(images))
            }
            return changed ? message.withParts(parts) : message
        }
    }

    private func thumbnailData(for image: FilePart) -> Data? {
        if let preview = image.previewData, preview.count <= ToolThumbnail.maxBytes { return preview }
        guard MessageMedia.hasFullData(image) else { return nil }
        return makeThumbnail(image)
    }

    private func read(_ url: URL) -> Data? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
        return data
    }

    private nonisolated static func fileSize(_ url: URL) -> Int? {
        guard let values = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]),
              values.isRegularFile == true
        else { return nil }
        return values.fileSize
    }

    // MARK: - Purge, sweep, eviction

    func removeAll() {
        try? FileManager.default.removeItem(at: root)
        unavailable.removeAll()
    }

    func removeConnection(_ connectionID: String) {
        try? FileManager.default.removeItem(
            at: root.appendingPathComponent(Self.connectionComponent(connectionID), isDirectory: true)
        )
    }

    /// Deletes every session folder with no cached message timeline left. Returns the files removed.
    @discardableResult
    func sweepOrphans(keeping sessions: [(connectionID: String, sessionID: String)]) -> Int {
        let fileManager = FileManager.default
        var keep: [String: Set<String>] = [:]
        for session in sessions {
            keep[Self.connectionComponent(session.connectionID), default: []].insert(Self.fileComponent(session.sessionID))
        }
        var removed = 0
        for connection in Self.children(of: root) {
            guard Self.isDirectory(connection) else {
                try? fileManager.removeItem(at: connection)
                removed += 1
                continue
            }
            let kept = keep[connection.lastPathComponent] ?? []
            var remaining = 0
            for session in Self.children(of: connection) {
                if kept.contains(session.lastPathComponent), Self.isDirectory(session) {
                    remaining += 1
                    continue
                }
                removed += Self.isDirectory(session) ? Self.children(of: session).count : 1
                try? fileManager.removeItem(at: session)
            }
            if remaining == 0 { try? fileManager.removeItem(at: connection) }
        }
        return removed
    }

    /// Over `globalBytes`, deletes least recently viewed files down to `globalTargetBytes`.
    func evictIfNeeded() -> ToolThumbnailCacheStats {
        var files = Self.allFiles(under: root)
        var total = files.reduce(Int64(0)) { $0 + $1.bytes }
        var evicted = 0
        if total > limits.globalBytes {
            files.sort { $0.viewed < $1.viewed }
            var index = 0
            while total > limits.globalTargetBytes, index < files.count {
                try? FileManager.default.removeItem(at: files[index].url)
                total -= files[index].bytes
                evicted += 1
                index += 1
            }
            files.removeFirst(index)
            for directory in Set(files.map { $0.url.deletingLastPathComponent() }) where Self.children(of: directory).isEmpty {
                try? FileManager.default.removeItem(at: directory)
            }
        }
        return ToolThumbnailCacheStats(bytes: total, files: files.count, evicted: evicted)
    }

    /// Launch upkeep: orphan sweep, then eviction, then one log line.
    @discardableResult
    func maintain(keeping sessions: [(connectionID: String, sessionID: String)]?) -> ToolThumbnailCacheStats {
        let swept = sessions.map { sweepOrphans(keeping: $0) } ?? 0
        let stats = evictIfNeeded()
        let result = ToolThumbnailCacheStats(bytes: stats.bytes, files: stats.files, evicted: stats.evicted + swept)
        Self.log(result)
        return result
    }

    /// Eviction a moment after the last write, so a burst of writes runs it once.
    private func scheduleEviction() {
        evictionTask?.cancel()
        let delay = evictionDelay
        evictionTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            await self.runScheduledEviction()
        }
    }

    private func runScheduledEviction() {
        evictionTask = nil
        Self.log(evictIfNeeded())
    }

    /// Waits for a scheduled eviction (tests).
    func settle() async {
        await evictionTask?.value
    }

    private nonisolated static func log(_ stats: ToolThumbnailCacheStats) {
        DiagnosticLog.info(.session, "thumbCache bytes=\(stats.bytes) files=\(stats.files) evicted=\(stats.evicted)")
    }

    private nonisolated static func children(of directory: URL) -> [URL] {
        (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: []
        )) ?? []
    }

    private nonisolated static func isDirectory(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
    }

    private nonisolated static func allFiles(under root: URL) -> [(url: URL, bytes: Int64, viewed: Date)] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
        guard let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: keys) else {
            return []
        }
        var files: [(url: URL, bytes: Int64, viewed: Date)] = []
        for case let url as URL in enumerator {
            guard let values = try? url.resourceValues(forKeys: Set(keys)), values.isRegularFile == true else { continue }
            files.append((url, Int64(values.fileSize ?? 0), values.contentModificationDate ?? .distantPast))
        }
        return files
    }
}
