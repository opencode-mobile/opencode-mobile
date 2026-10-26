import Foundation

/// A file the prompt names in `metadata.attachments` (#644): its bytes live on the server.
nonisolated struct UploadedAttachmentRef: Equatable, Sendable {
    let name: String
    let mime: String
    let path: String

    var kind: AttachmentKind { AttachmentMime.kind(for: mime) }
}

nonisolated enum UploadedAttachmentMetadata {
    static func refs(from metadata: [String: AnyCodable]?) -> [UploadedAttachmentRef] {
        guard case let .array(entries)? = metadata?["attachments"] else { return [] }
        return entries.compactMap { entry in
            guard case let .dictionary(fields) = entry, case let .string(path)? = fields["path"], !path.isEmpty
            else { return nil }
            let name = fields["name"].flatMap { value -> String? in
                if case let .string(text) = value { return text }
                return nil
            }
            let mime = fields["mime"].flatMap { value -> String? in
                if case let .string(text) = value { return text }
                return nil
            }
            return makeRef(name: name, mime: mime, path: path)
        }
    }

    /// The same list from a live event payload (`prompt.metadata` or `item.payload.metadata`).
    static func refs(fromJSON metadata: Any?) -> [UploadedAttachmentRef] {
        guard let object = metadata as? [String: Any], let entries = object["attachments"] as? [Any] else { return [] }
        return entries.compactMap { entry in
            guard let fields = entry as? [String: Any], let path = fields["path"] as? String, !path.isEmpty
            else { return nil }
            return makeRef(name: fields["name"] as? String, mime: fields["mime"] as? String, path: path)
        }
    }

    private static func makeRef(name: String?, mime: String?, path: String) -> UploadedAttachmentRef {
        let fallbackName = path.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? "Attachment"
        let cleanedName = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanedMime = AttachmentMime.declared(mime, filename: cleanedName ?? fallbackName)
        return UploadedAttachmentRef(
            name: cleanedName?.isEmpty == false ? cleanedName ?? fallbackName : fallbackName,
            mime: cleanedMime ?? AttachmentMime.binaryFallback,
            path: path
        )
    }

    static func fileParts(_ refs: [UploadedAttachmentRef], messageID: String) -> [MessagePart] {
        refs.enumerated().map { index, ref in
            .file(
                FilePart(
                    id: "\(messageID):upload:\(index)",
                    filename: ref.name,
                    mime: ref.mime,
                    kind: ref.kind,
                    dataURL: "",
                    serverPath: ref.path
                )
            )
        }
    }

    /// The prompt text without the ``Attached file: `<path>` `` lines the chips already show.
    static func displayText(_ text: String, hiding refs: [UploadedAttachmentRef]) -> String {
        guard !refs.isEmpty else { return text }
        let hidden = Set(refs.map { "Attached file: `\($0.path)`" })
        let kept = text.components(separatedBy: "\n").filter { !hidden.contains($0.trimmingCharacters(in: .whitespaces)) }
        return kept.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
