import Foundation

/// Pure byte → `ProjectFileContent` rules shared by both protocols.
nonisolated enum ProjectFileContentDecoder {
    /// Raw file bytes (v2 read body). `isTruncated` when the server holds more than `data`.
    static func content(
        fromBytes data: Data,
        isTruncated: Bool,
        totalBytes: Int?,
        mimeType: String?
    ) -> ProjectFileContent {
        if data.isEmpty, !isTruncated { return .empty }
        let bytes = [UInt8](data.prefix(ProjectFileLimits.maxTextBytes + 1))
        let knownSize = totalBytes ?? (isTruncated ? nil : data.count)
        if bytes.prefix(ProjectFileLimits.binarySniffBytes).contains(0) {
            return .binary(mimeType: mimeType, byteCount: knownSize)
        }
        let cut = isTruncated || bytes.count > ProjectFileLimits.maxTextBytes
        let kept = cut ? truncatedText(bytes, limit: ProjectFileLimits.maxTextBytes) : bytes[...]
        guard let text = strictUTF8(kept) else {
            return .binary(mimeType: mimeType, byteCount: knownSize)
        }
        return .text(text, isTruncated: cut, totalBytes: knownSize)
    }

    /// v1 `file/content` body.
    static func content(fromV1 dto: FileContentDTO, fileName: String) -> ProjectFileContent {
        let mimeType = normalizedMimeType(dto.mimeType)
        if dto.encoding == "base64" || dto.type == "binary" {
            guard let data = Data(base64Encoded: dto.content, options: .ignoreUnknownCharacters) else {
                return .binary(mimeType: mimeType, byteCount: nil)
            }
            if data.isEmpty { return .empty }
            let isImage = mimeType?.hasPrefix("image/") == true || ProjectPaths.isImage(name: fileName)
            if isImage, data.count <= ProjectFileLimits.maxImageBytes {
                return .image(data, mimeType: mimeType ?? guessedImageMimeType(fileName))
            }
            return .binary(mimeType: mimeType, byteCount: data.count)
        }
        if dto.content.isEmpty { return .empty }
        let data = Data(dto.content.utf8)
        guard data.count > ProjectFileLimits.maxTextBytes else {
            return .text(dto.content, isTruncated: false, totalBytes: data.count)
        }
        let kept = truncatedText([UInt8](data.prefix(ProjectFileLimits.maxTextBytes + 1)), limit: ProjectFileLimits.maxTextBytes)
        guard let text = strictUTF8(kept) else {
            return .binary(mimeType: mimeType, byteCount: data.count)
        }
        return .text(text, isTruncated: true, totalBytes: data.count)
    }

    /// v2 image body read without Range. Over the image cap → binary placeholder.
    static func image(fromBytes data: Data, exceededLimit: Bool, mimeType: String?, fileName: String) -> ProjectFileContent {
        if exceededLimit || data.count > ProjectFileLimits.maxImageBytes {
            return .binary(mimeType: mimeType, byteCount: exceededLimit ? nil : data.count)
        }
        if data.isEmpty { return .empty }
        let resolved = mimeType.flatMap { $0.hasPrefix("image/") ? $0 : nil } ?? guessedImageMimeType(fileName)
        return .image(data, mimeType: resolved)
    }

    /// Keeps at most `limit` bytes, never splits a UTF-8 sequence, and drops the trailing
    /// partial line when an earlier newline exists.
    static func truncatedText(_ bytes: [UInt8], limit: Int) -> ArraySlice<UInt8> {
        var end = min(limit, bytes.count)
        guard end > 0 else { return bytes[0..<0] }
        var lead = end - 1
        while lead > 0, bytes[lead] & 0xC0 == 0x80 { lead -= 1 }
        if lead + utf8SequenceLength(bytes[lead]) > end { end = lead }
        if let newline = bytes[0..<end].lastIndex(of: 0x0A) {
            end = newline
        }
        return bytes[0..<end]
    }

    /// `bytes 0-1048575/12345` → 12345; unknown (`*`) → nil.
    static func totalBytes(fromContentRange header: String?) -> Int? {
        guard let header, let slash = header.lastIndex(of: "/") else { return nil }
        return Int(header[header.index(after: slash)...].trimmingCharacters(in: .whitespaces))
    }

    /// `text/plain; charset=utf-8` → `text/plain`.
    static func normalizedMimeType(_ value: String?) -> String? {
        guard let base = value?.split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased(),
              !base.isEmpty
        else { return nil }
        return base
    }

    private static func strictUTF8(_ bytes: ArraySlice<UInt8>) -> String? {
        String(bytes: bytes, encoding: .utf8)
    }

    private static func utf8SequenceLength(_ lead: UInt8) -> Int {
        switch lead {
        case 0xF0...: 4
        case 0xE0...: 3
        case 0xC0...: 2
        default: 1
        }
    }

    private static func guessedImageMimeType(_ fileName: String) -> String {
        switch ProjectPaths.fileExtension(of: fileName) {
        case "jpg", "jpeg": "image/jpeg"
        case "gif": "image/gif"
        case "webp": "image/webp"
        default: "image/png"
        }
    }
}
