import Foundation

/// Merge rules for the pictures a message carries: tool-returned images (`ToolPart.images`)
/// and file parts. The session cache keeps only metadata and a small thumbnail, so a cached
/// copy and a server copy of the same message differ only in their media. These rules pick
/// the richer copy of each field and never trade a full picture for a thumbnail-only one.
nonisolated enum MessageMedia {
    /// The picture rides inline (a data URL from the server). The cache blanks it on disk.
    static func hasFullData(_ file: FilePart) -> Bool {
        !file.dataURL.isEmpty
    }

    /// `next` wins field by field, except that an empty field never replaces a filled one:
    /// a full data URL beats a thumbnail-only copy, and a thumbnail is kept when `next` has none.
    static func merge(prior: FilePart, next: FilePart) -> FilePart {
        let merged = FilePart(
            id: next.id,
            filename: next.filename.isEmpty ? prior.filename : next.filename,
            mime: next.mime.isEmpty ? prior.mime : next.mime,
            kind: next.kind,
            dataURL: next.dataURL.isEmpty ? prior.dataURL : next.dataURL,
            previewData: next.previewData ?? prior.previewData,
            serverPath: next.serverPath ?? prior.serverPath
        )
        return merged == prior ? prior : merged
    }

    /// The image list of `next` (the newer copy), each enriched from the prior image with the
    /// same id. An empty `next` keeps `prior`: images arrive once, with the settled call.
    static func mergeImages(prior: [FilePart], next: [FilePart]) -> [FilePart] {
        guard !next.isEmpty else { return prior }
        guard !prior.isEmpty else { return next }
        let priorByID = Dictionary(prior.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let merged = next.map { image in priorByID[image.id].map { merge(prior: $0, next: image) } ?? image }
        return merged == prior ? prior : merged
    }

    /// `existing` with only its media filled in from `source`, the same part from another copy
    /// of the message. Nil when nothing changes (no new media, or a different part).
    static func hydrated(_ existing: MessagePart, from source: MessagePart) -> MessagePart? {
        switch (existing, source) {
        case let (.tool(tool), .tool(fetched)):
            guard tool.id != nil, tool.id == fetched.id, !fetched.images.isEmpty else { return nil }
            let images = hydratedImages(tool.images, from: fetched.images)
            return images == tool.images ? nil : .tool(tool.withImages(images))
        case let (.file(file), .file(fetched)):
            guard file.id == fetched.id else { return nil }
            let merged = hydratedFile(file, from: fetched)
            return merged == file ? nil : .file(merged)
        default:
            return nil
        }
    }

    /// `message` with each part's media filled in from the part with the same id in `source`.
    /// Nil when nothing changes. Text, tool state, and order stay as they are.
    static func hydrated(_ message: Message, from source: Message) -> Message? {
        guard message.id == source.id else { return nil }
        var sourceByID: [String: MessagePart] = [:]
        for part in source.parts {
            guard let id = part.mediaPartID else { continue }
            sourceByID[id] = part
        }
        guard !sourceByID.isEmpty else { return nil }
        var changed = false
        let parts = message.parts.map { part -> MessagePart in
            guard let id = part.mediaPartID, let fetched = sourceByID[id],
                  let next = hydrated(part, from: fetched)
            else { return part }
            changed = true
            return next
        }
        return changed ? message.withParts(parts) : nil
    }

    /// Hydration keeps the cached list (and its order) when it already names the images; it
    /// adopts the fetched list when the cached copy has none or a different set.
    private static func hydratedImages(_ cached: [FilePart], from fetched: [FilePart]) -> [FilePart] {
        guard cached.map(\.id) == fetched.map(\.id) else { return mergeImages(prior: cached, next: fetched) }
        return zip(cached, fetched).map { hydratedFile($0, from: $1) }
    }

    /// Only the media of `fetched` moves over; a fetched copy never blanks what `cached` has.
    private static func hydratedFile(_ cached: FilePart, from fetched: FilePart) -> FilePart {
        let dataURL = cached.dataURL.isEmpty ? fetched.dataURL : cached.dataURL
        let previewData = cached.previewData ?? fetched.previewData
        let serverPath = cached.serverPath ?? fetched.serverPath
        guard dataURL != cached.dataURL || previewData != cached.previewData || serverPath != cached.serverPath else {
            return cached
        }
        return FilePart(
            id: cached.id,
            filename: cached.filename.isEmpty ? fetched.filename : cached.filename,
            mime: cached.mime.isEmpty ? fetched.mime : cached.mime,
            kind: cached.kind,
            dataURL: dataURL,
            previewData: previewData,
            serverPath: serverPath
        )
    }
}

nonisolated extension MessagePart {
    /// The id media hydration matches parts by: tool and file parts only.
    var mediaPartID: String? {
        switch self {
        case .tool(let tool): tool.id
        case .file(let file): file.id
        default: nil
        }
    }

    /// Every tool image the part returned.
    var toolImages: [FilePart] {
        if case .tool(let tool) = self { return tool.images }
        return []
    }
}

nonisolated extension Message {
    /// Tool images across the message's parts, in part order.
    var toolImages: [FilePart] { parts.flatMap(\.toolImages) }
}
