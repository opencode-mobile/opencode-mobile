import Foundation
import OpenCodeKit

/// A file going with the draft (app plan, "Attach files (M7)"): from Files, or a video from Photos
/// and videos. Its exact bytes are uploaded to the computer's temporary folder as soon as it is
/// picked, and the agent gets its path. `path` is set once the upload is done.
nonisolated struct DraftFile: Identifiable, Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case file
        /// From the photo picker: shows `play` in place of `doc`.
        case video
    }

    let id: UUID
    /// The name on the computer, already made safe (`AttachedFiles.fileName`).
    let name: String
    /// As the picker reports it, or `application/octet-stream`.
    let mime: String
    let kind: Kind
    var sentBytes: Int64 = 0
    var totalBytes: Int64 = 0
    /// Where the server holds it (`data.path` of the upload); nil while uploading.
    var path: String?

    init(id: UUID = UUID(), name: String, mime: String, kind: Kind = .file, path: String? = nil) {
        self.id = id
        self.name = name
        self.mime = mime
        self.kind = kind
        self.path = path
    }

    var isUploading: Bool { path == nil }
    /// 0 to 1 while uploading; 1 once uploaded.
    var progress: Double {
        guard isUploading else { return 1 }
        guard totalBytes > 0 else { return 0 }
        return min(1, Double(sentBytes) / Double(totalBytes))
    }

    /// The tile's extension: "PDF", "ZIP", "CSV".
    var extensionLabel: String { AttachedFiles.extensionLabel(name) }
}

/// A file going with a message: already on the server.
nonisolated struct OutgoingFile: Equatable, Sendable {
    let name: String
    let mime: String
    let path: String
    var kind: DraftFile.Kind = .file

    var extensionLabel: String { AttachedFiles.extensionLabel(name) }

    /// The draft's uploaded files, in draft order.
    static func from(_ files: [DraftFile]) -> [OutgoingFile] {
        files.compactMap { file in
            file.path.map { OutgoingFile(name: file.name, mime: file.mime, path: $0, kind: file.kind) }
        }
    }
}

/// Naming, the prompt's path lines and their stripping (app plan, "Attach files (M7)").
nonisolated enum AttachedFiles {
    /// A name longer than this is cut, keeping its extension.
    static let maxNameLength = 120
    static let fallbackName = "File"
    static let fallbackMime = "application/octet-stream"
    /// Letters of the extension a tile shows at most.
    static let maxExtensionLetters = 4

    // MARK: Naming

    /// The file's own display name made safe for the computer: `/`, `\` and control characters
    /// become `_`, it is trimmed and cut to 120 characters keeping the extension, and a blank name
    /// (or `.` / `..`, which would leave its folder) becomes "File".
    static func fileName(_ raw: String) -> String {
        let replaced = String(String.UnicodeScalarView(raw.unicodeScalars.map { scalar in
            scalar == "/" || scalar == "\\" || CharacterSet.controlCharacters.contains(scalar) ? "_" : scalar
        }))
        var name = replaced.trimmingCharacters(in: .whitespaces)
        if name.count > maxNameLength {
            let ext = (name as NSString).pathExtension
            if !ext.isEmpty, ext.count < maxNameLength / 2 {
                let base = String(name.prefix(maxNameLength - ext.count - 1)).trimmingCharacters(in: .whitespaces)
                name = base + "." + ext
            } else {
                name = String(name.prefix(maxNameLength)).trimmingCharacters(in: .whitespaces)
            }
        }
        if name.isEmpty || name == "." || name == ".." { return fallbackName }
        return name
    }

    /// `<tmp>/uploads/<lowercase uuid>/<name>`: a folder per file, so two files with the same name
    /// never collide and nothing is ever overwritten. A Windows tmp (backslashes, no slash) keeps
    /// its separator.
    static func serverPath(tmp: String, name: String, uuid: UUID = UUID()) -> String {
        let separator = tmp.contains("\\") && !tmp.contains("/") ? "\\" : "/"
        var base = tmp
        while base.count > 1, base.hasSuffix(separator) { base.removeLast() }
        return [base, "uploads", uuid.uuidString.lowercased(), fileName(name)].joined(separator: separator)
    }

    /// "PDF", "ZIP", "CSV": the extension in capitals, at most 4 letters; "" for none.
    static func extensionLabel(_ name: String) -> String {
        String((name as NSString).pathExtension.uppercased().prefix(maxExtensionLetters))
    }

    // MARK: The prompt

    /// The line that names an uploaded file, so the agent opens it with its tools.
    static func promptLine(path: String) -> String {
        "Attached file: `\(path)`"
    }

    /// The text, then `\n` and one line per file; only the lines when there is no text. A Now's
    /// `[Correction] ` stays at the very start.
    static func promptText(_ text: String, paths: [String]) -> String {
        guard !paths.isEmpty else { return text }
        let block = paths.map(promptLine(path:)).joined(separator: "\n")
        return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? block : text + "\n" + block
    }

    /// `metadata` for a prompt with files: OpenCode Mobile and OpenCode's desktop app show them as
    /// chips (the desktop needs `displayText` and `comments`). Nil with no files.
    static func metadata(displayText: String, files: [OutgoingFile]) -> SessionStateAPIClient.PromptMetadata? {
        guard !files.isEmpty else { return nil }
        return SessionStateAPIClient.PromptMetadata(
            displayText: displayText,
            comments: [],
            attachments: files.map { PromptAttachment(name: $0.name, mime: $0.mime.isEmpty ? fallbackMime : $0.mime, path: $0.path) }
        )
    }

    // MARK: Showing the text

    /// OpenCode's own rule (`voice-delegation.ts`): trailing ``Attached file: `…` `` lines, with
    /// the spaces around them.
    private static var trailingLines: Regex<(Substring, Substring)> { #/(\s*\nAttached file: `[^\n]*`)+\s*$/# }
    private static var line: Regex<(Substring, Substring)> { #/Attached file: `([^\n]*)`/# }

    /// The text without its trailing path lines, and the paths they named, in order. A text that
    /// is only path lines comes back empty.
    static func strip(_ text: String) -> (text: String, paths: [String]) {
        // A leading line break, so a first line that is a path line is stripped too.
        let padded = "\n" + text
        guard let match = padded.firstMatch(of: trailingLines) else { return (text, []) }
        let paths = padded[match.range].matches(of: line).map { String($0.output.1) }
        let kept = String(padded[padded.startIndex ..< match.range.lowerBound].dropFirst())
        return (kept, paths)
    }

    /// The names of a message's uploaded files: from `metadata.attachments` when it has them,
    /// else the last part of each path line's path.
    static func names(text: String, attachments: [PromptAttachment]) -> [String] {
        let named = attachments.map { $0.name.isEmpty ? lastPart($0.path) : $0.name }
        if !named.isEmpty { return named }
        return strip(text).paths.map(lastPart)
    }

    static func lastPart(_ path: String) -> String {
        path.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? path
    }
}

/// What the app says about attachments: the draft's caption, the counts, the You line.
enum AttachmentText {
    /// "2 images", "1 file", "2 images, 1 file"; "" for none.
    static func count(images: Int, files: Int) -> String {
        switch (images, files) {
        case (0, 0): ""
        case (_, 0): String(localized: "home.waiting.images", defaultValue: "\(images) images")
        case (0, _): String(localized: "home.waiting.files", defaultValue: "\(files) files")
        default: "\(String(localized: "home.attach.count.images", defaultValue: "\(images) image")), \(String(localized: "home.attach.count.files", defaultValue: "\(files) file"))"
        }
    }

    /// The draft's caption: "<name> · goes with your next message" for one file, "2 images, 1
    /// file · go with your next message" for a mix; "Uploading · 45%" while uploads run.
    static func caption(images: Int, files: [DraftFile], uploadPercent: Int?) -> String {
        if let uploadPercent {
            return String(localized: "home.attach.uploading", defaultValue: "Uploading · \(uploadPercent)%")
        }
        switch (images, files.count) {
        case (_, 0):
            return String(localized: "home.attach.caption", defaultValue: "\(images) images · go with your next message")
        case (0, 1):
            return String(localized: "home.attach.caption.file", defaultValue: "\(files[0].name) · goes with your next message")
        case (0, _):
            return String(localized: "home.attach.caption.files", defaultValue: "\(files.count) files · go with your next message")
        default:
            let imageCount = String(localized: "home.attach.count.images", defaultValue: "\(images) image")
            let fileCount = String(localized: "home.attach.count.files", defaultValue: "\(files.count) file")
            return String(localized: "home.attach.caption.mixed", defaultValue: "\(imageCount), \(fileCount) · go with your next message")
        }
    }

    /// The You line's "With report.pdf" or "With 3 attachments"; nil with none.
    static func with(_ names: [String]) -> String? {
        switch names.count {
        case 0: nil
        case 1: String(localized: "home.you.with_one", defaultValue: "With \(names[0])")
        default: String(localized: "home.you.with_many", defaultValue: "With \(names.count) attachments")
        }
    }
}
