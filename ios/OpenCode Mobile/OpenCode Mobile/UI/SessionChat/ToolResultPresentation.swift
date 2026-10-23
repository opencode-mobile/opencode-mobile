import Foundation

/// Typed body for the tool-result sheet. Feature code classifies a part into one
/// of these shapes. The sheet renders the shape; it does not re-parse tool ids.
///
/// Classification matches the Android `ToolResultInspector` contract.
nonisolated enum ToolResultPresentation: Equatable, Sendable {
    nonisolated struct FileRow: Equatable, Sendable {
        var letter: String
        var tone: StatusTone
        var name: String
        var directory: String?
        var fullPath: String
    }

    nonisolated enum StatusTone: Equatable, Sendable {
        case modified
        case added
        case deleted
        case other
    }

    nonisolated struct Count: Equatable, Sendable {
        var label: String
        var tone: StatusTone
    }

    nonisolated struct FileStatus: Equatable, Sendable {
        var command: String?
        var exitCode: Int?
        var durationLabel: String?
        var files: [FileRow]
        var counts: [Count]
        var raw: String

        var eyebrow: String { "Shell" }
        var title: String { countTitle(files.count, singular: "file", plural: "files") }
    }

    nonisolated struct PathRow: Equatable, Sendable {
        var name: String
        var directory: String?
        var fullPath: String
    }

    nonisolated struct PathList: Equatable, Sendable {
        var eyebrow: String
        var command: String?
        var paths: [PathRow]
        var raw: String

        var title: String { countTitle(paths.count, singular: "path", plural: "paths") }
    }

    nonisolated struct PullRequest: Equatable, Sendable {
        var command: String?
        var exitCode: Int?
        var durationLabel: String?
        var number: Int
        var url: String
        var branch: String?
        var pullTitle: String?
        var raw: String

        var eyebrow: String { "Shell" }
        var title: String { "Pull request opened" }
    }

    nonisolated struct Thought: Equatable, Sendable {
        var durationLabel: String?
        var callout: String
        var paragraphs: [String]
        var raw: String

        var eyebrow: String { "Thought" }
        var title: String { "Thought" }
    }

    nonisolated enum SourceKind: Equatable, Sendable {
        case read
        case write
    }

    nonisolated struct SourceLine: Equatable, Sendable {
        var number: Int
        var text: String
    }

    nonisolated struct Source: Equatable, Sendable {
        var kind: SourceKind
        var filename: String
        var language: String?
        var highlightLanguage: String?
        var rangeLabel: String?
        var lines: [SourceLine]
        var raw: String

        var eyebrow: String { kind == .read ? "Read" : "Write" }
        var title: String { filename }
    }

    nonisolated struct GrepHit: Equatable, Sendable {
        var line: Int
        var text: String
    }

    nonisolated struct GrepFile: Equatable, Sendable {
        var name: String
        var directory: String?
        var hits: [GrepHit]
    }

    nonisolated struct Grep: Equatable, Sendable {
        var pattern: String
        var searchPath: String?
        var matchCount: Int
        var groups: [GrepFile]
        var raw: String

        var eyebrow: String { "Grep" }
        var title: String { pattern }
    }

    nonisolated struct Edit: Equatable, Sendable {
        var eyebrow: String
        var filename: String
        var additions: Int
        var deletions: Int
        var patch: String
        var raw: String

        var title: String { filename }
    }

    nonisolated struct Unrecognized: Equatable, Sendable {
        var title: String
        var error: String?
        var raw: String

        var eyebrow: String? { nil }
    }

    case fileStatus(FileStatus)
    case pathList(PathList)
    case pullRequest(PullRequest)
    case thought(Thought)
    case source(Source)
    case grep(Grep)
    case edit(Edit)
    case unrecognized(Unrecognized)

    var title: String {
        switch self {
        case .fileStatus(let value): value.title
        case .pathList(let value): value.title
        case .pullRequest(let value): value.title
        case .thought(let value): value.title
        case .source(let value): value.title
        case .grep(let value): value.title
        case .edit(let value): value.title
        case .unrecognized(let value): value.title
        }
    }

    var raw: String {
        switch self {
        case .fileStatus(let value): value.raw
        case .pathList(let value): value.raw
        case .pullRequest(let value): value.raw
        case .thought(let value): value.raw
        case .source(let value): value.raw
        case .grep(let value): value.raw
        case .edit(let value): value.raw
        case .unrecognized(let value): value.raw
        }
    }

    var eyebrow: String? {
        switch self {
        case .fileStatus(let value): value.eyebrow
        case .pathList(let value): value.eyebrow
        case .pullRequest(let value): value.eyebrow
        case .thought(let value): value.eyebrow
        case .source(let value): value.eyebrow
        case .grep(let value): value.eyebrow
        case .edit(let value): value.eyebrow
        case .unrecognized(let value): value.eyebrow
        }
    }

    var copiesOriginal: Bool {
        switch self {
        case .fileStatus, .pathList, .pullRequest, .thought, .unrecognized:
            true
        case .source, .grep, .edit:
            false
        }
    }

    var isFullBleed: Bool {
        if case .source = self { return true }
        return false
    }

    static func countTitle(_ count: Int, singular: String, plural: String) -> String {
        count == 1 ? "1 \(singular)" : "\(count) \(plural)"
    }
}

/// Inputs the classifier needs. Callers extract these from a tool part.
nonisolated struct ToolResultRequest: Equatable, Sendable {
    var toolName: String
    var fallbackTitle: String
    var command: String? = nil
    var path: String? = nil
    var pattern: String? = nil
    var searchPath: String? = nil
    var offset: Int? = nil
    var limit: Int? = nil
    var content: String? = nil
    var output: String? = nil
    var error: String? = nil
    var exitCode: Int? = nil
    var durationLabel: String? = nil
    var patch: String? = nil
    var oldString: String? = nil
    var newString: String? = nil
}

nonisolated enum ToolResultInspector {
    static func thought(text: String, durationLabel: String?) -> ToolResultPresentation.Thought? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let split = splitThought(trimmed)
        let duration = durationLabel.flatMap { $0.isEmpty ? nil : $0 }
        return ToolResultPresentation.Thought(
            durationLabel: duration,
            callout: split.callout,
            paragraphs: split.paragraphs,
            raw: trimmed
        )
    }

    static func classify(_ request: ToolResultRequest) -> ToolResultPresentation? {
        let name = request.toolName.lowercased()
        let output = nonBlank(request.output)
        let error = nonBlank(request.error)
        let exit = request.exitCode ?? error.flatMap(exitCodeIn) ?? output.flatMap(exitCodeIn)
        let command = nonBlank(request.command)

        if let pullRequest = pullRequest(request, output: output, command: command, exit: exit) {
            return .pullRequest(pullRequest)
        }

        if isShell(name), let output, let files = parseFileStatus(output) {
            return .fileStatus(
                ToolResultPresentation.FileStatus(
                    command: command,
                    exitCode: exit,
                    durationLabel: request.durationLabel,
                    files: files,
                    counts: fileCounts(files),
                    raw: output
                )
            )
        }

        if let output, isPathList(name: name, command: command, output: output) {
            let rows = pathRows(output)
            if !rows.isEmpty {
                return .pathList(
                    ToolResultPresentation.PathList(
                        eyebrow: name == "glob" ? "Glob" : "Shell",
                        command: command ?? request.pattern,
                        paths: rows,
                        raw: output
                    )
                )
            }
        }

        if name == "read", let output {
            let path = nonBlank(request.path) ?? "File"
            return .source(source(kind: .read, path: path, body: output, request: request))
        }
        if name == "write" {
            let body = request.content.flatMap { $0.isEmpty ? nil : $0 } ?? output
            if let body, let path = nonBlank(request.path) {
                return .source(source(kind: .write, path: path, body: body, request: request))
            }
        }

        if name == "grep" {
            let pattern = nonBlank(request.pattern)
            let groups = (output != nil && pattern != nil) ? parseGrep(output ?? "", fallbackFile: request.path) : nil
            if let pattern, let groups {
                return .grep(
                    ToolResultPresentation.Grep(
                        pattern: pattern,
                        searchPath: request.searchPath,
                        matchCount: groups.reduce(0) { $0 + $1.hits.count },
                        groups: groups,
                        raw: output ?? ""
                    )
                )
            }
        }

        if name == "edit" || name == "apply_patch" || name == "patch" {
            let path = nonBlank(request.path) ?? "File"
            let patch = request.patch.flatMap { $0.contains("@@") ? $0 : nil }
                ?? syntheticPatch(path: path, old: request.oldString, new: request.newString)
            if let patch {
                let additions = request.patch.map { countPrefix($0, prefix: "+") } ?? request.newString.map(lineCount) ?? 0
                let deletions = request.patch.map { countPrefix($0, prefix: "-") } ?? request.oldString.map(lineCount) ?? 0
                return .edit(
                    ToolResultPresentation.Edit(
                        eyebrow: name == "edit" ? "Edit" : "Patch",
                        filename: fileName(path),
                        additions: additions,
                        deletions: deletions,
                        patch: patch,
                        raw: patch
                    )
                )
            }
        }

        guard let raw = output ?? error else { return nil }
        let shownError = error.flatMap { output != nil && $0 != output ? $0 : nil }
        let title = isBlank(request.fallbackTitle) ? toolLabel(name) : request.fallbackTitle
        return .unrecognized(
            ToolResultPresentation.Unrecognized(title: title, error: shownError, raw: raw)
        )
    }

    private static func pullRequest(
        _ request: ToolResultRequest,
        output: String?,
        command: String?,
        exit: Int?
    ) -> ToolResultPresentation.PullRequest? {
        guard isShell(request.toolName.lowercased()), let output else { return nil }
        guard let match = firstMatch(prUrl, in: output), let number = Int(match.groups[1]) else { return nil }
        let haystack = [output, command].compactMap { $0 }.joined(separator: "\n")
        return ToolResultPresentation.PullRequest(
            command: command,
            exitCode: exit,
            durationLabel: request.durationLabel,
            number: number,
            url: match.full,
            branch: branchIn(haystack),
            pullTitle: titleFlag(command),
            raw: output
        )
    }

    private static func source(
        kind: ToolResultPresentation.SourceKind,
        path: String,
        body: String,
        request: ToolResultRequest
    ) -> ToolResultPresentation.Source {
        let lines: [ToolResultPresentation.SourceLine]
        if let numbered = numberedLines(body) {
            lines = numbered
        } else {
            var split = kotlinLines(body)
            if body.hasSuffix("\n"), split.last?.isEmpty != false, !split.isEmpty {
                split.removeLast()
            }
            let start = request.offset ?? 1
            lines = split.enumerated().map { index, line in
                ToolResultPresentation.SourceLine(number: start + index, text: line)
            }
        }
        let end = lines.last?.number
        let range: String?
        if let offset = request.offset, let limit = request.limit {
            range = "lines \(offset)\u{2013}\(offset + limit - 1)"
        } else if let offset = request.offset, let end {
            range = "lines \(offset)\u{2013}\(end)"
        } else if let limit = request.limit {
            range = "first \(limit) lines"
        } else {
            range = nil
        }
        return ToolResultPresentation.Source(
            kind: kind,
            filename: fileName(path),
            language: languageName(path),
            highlightLanguage: highlightLanguage(path),
            rangeLabel: range,
            lines: lines,
            raw: body
        )
    }

    private static func parseFileStatus(_ output: String) -> [ToolResultPresentation.FileRow]? {
        let lines = nonEmptyLines(output)
        guard !lines.isEmpty else { return nil }
        var parsed: [(xy: String, path: String)] = []
        for line in lines {
            guard let match = wholeMatch(statusLine, in: line) else { return nil }
            parsed.append((match[1], match[2]))
        }
        return parsed.map { match in
            let path = unquote(renameDestination(match.path))
            let letter = displayLetter(match.xy)
            return ToolResultPresentation.FileRow(
                letter: String(letter),
                tone: tone(letter),
                name: fileName(path),
                directory: parentPath(path),
                fullPath: path
            )
        }
    }

    private static func isPathList(name: String, command: String?, output: String) -> Bool {
        let lines = nonEmptyLines(output)
        guard !lines.isEmpty, lines.allSatisfy(isPathLine) else { return false }
        return name == "glob" || isFindCommand(command) || isShell(name)
    }

    private static func pathRows(_ output: String) -> [ToolResultPresentation.PathRow] {
        nonEmptyLines(output).map { line in
            let path = unquote(line.trimmingCharacters(in: .whitespacesAndNewlines))
            return ToolResultPresentation.PathRow(
                name: fileName(path),
                directory: parentPath(path),
                fullPath: path
            )
        }
    }

    private static func parseGrep(_ output: String, fallbackFile: String?) -> [ToolResultPresentation.GrepFile]? {
        let lines = nonEmptyLines(output)
        let colon = lines.compactMap { wholeMatch(grepColon, in: $0) }
        if colon.count == lines.count, !colon.isEmpty {
            var order: [String] = []
            var grouped: [String: [ToolResultPresentation.GrepHit]] = [:]
            for match in colon {
                let path = unquote(match[1])
                guard let line = Int(match[2]) else { return nil }
                if grouped[path] == nil {
                    order.append(path)
                    grouped[path] = []
                }
                grouped[path, default: []].append(
                    ToolResultPresentation.GrepHit(line: line, text: match[3])
                )
            }
            return order.map { path in
                ToolResultPresentation.GrepFile(
                    name: fileName(path),
                    directory: parentPath(path),
                    hits: grouped[path] ?? []
                )
            }
        }
        guard let grouped = parseGrepGroups(output, fallbackFile: fallbackFile), !grouped.isEmpty else { return nil }
        let hits = grouped.reduce(0) { $0 + $1.hits.count }
        return hits > 0 ? grouped : nil
    }

    private static func parseGrepGroups(
        _ output: String,
        fallbackFile: String?
    ) -> [ToolResultPresentation.GrepFile]? {
        var files: [ToolResultPresentation.GrepFile] = []
        var currentPath = fallbackFile
        var hits: [ToolResultPresentation.GrepHit] = []
        func flush() {
            guard let path = currentPath, !hits.isEmpty else { return }
            files.append(
                ToolResultPresentation.GrepFile(
                    name: fileName(path),
                    directory: parentPath(path),
                    hits: hits
                )
            )
            hits = []
        }
        for line in kotlinLines(output) {
            if isBlank(line) { continue }
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if let hit = wholeMatch(grepHit, in: trimmed), let number = Int(hit[1]) {
                hits.append(ToolResultPresentation.GrepHit(line: number, text: hit[2]))
            } else if isPathLine(line) {
                flush()
                currentPath = unquote(trimmed)
            } else {
                return nil
            }
        }
        flush()
        return files.isEmpty ? nil : files
    }

    private static func syntheticPatch(path: String, old: String?, new: String?) -> String? {
        if old == nil, new == nil { return nil }
        let oldLines = splitLines(old ?? "")
        let newLines = splitLines(new ?? "")
        if oldLines == newLines { return nil }
        var lines = [
            "--- a/\(path)",
            "+++ b/\(path)",
            "@@ -1,\(oldLines.count) +1,\(newLines.count) @@",
        ]
        lines.append(contentsOf: oldLines.map { "-\($0)" })
        lines.append(contentsOf: newLines.map { "+\($0)" })
        return lines.joined(separator: "\n") + "\n"
    }

    private static func fileCounts(_ files: [ToolResultPresentation.FileRow]) -> [ToolResultPresentation.Count] {
        let modified = files.filter { $0.tone == .modified }.count
        let added = files.filter { $0.tone == .added }.count
        let deleted = files.filter { $0.tone == .deleted }.count
        return [
            modified > 0 ? ToolResultPresentation.Count(label: "\(modified) modified", tone: .modified) : nil,
            added > 0 ? ToolResultPresentation.Count(label: "\(added) added", tone: .added) : nil,
            deleted > 0 ? ToolResultPresentation.Count(label: "\(deleted) deleted", tone: .deleted) : nil,
        ].compactMap { $0 }
    }

    private static func displayLetter(_ xy: String) -> Character {
        let chars = Array(xy)
        guard chars.count >= 2 else { return chars.first ?? " " }
        let index = chars[0]
        let workTree = chars[1]
        if index != " ", workTree != " ", index != workTree { return workTree }
        if workTree != " " { return workTree }
        return index
    }

    private static func tone(_ letter: Character) -> ToolResultPresentation.StatusTone {
        switch letter {
        case "M": .modified
        case "A": .added
        case "D": .deleted
        default: .other
        }
    }

    private static func renameDestination(_ path: String) -> String {
        let arrow = " -> "
        guard let range = path.range(of: arrow, options: .backwards) else { return path }
        return String(path[range.upperBound...])
    }

    private static func unquote(_ path: String) -> String {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2, trimmed.hasPrefix("\""), trimmed.hasSuffix("\"") else { return trimmed }
        let inner = String(trimmed.dropFirst().dropLast())
        return inner.replacingOccurrences(of: "\\\"", with: "\"").replacingOccurrences(of: "\\\\", with: "\\")
    }

    private static func fileName(_ path: String) -> String {
        let normalized = trimmingTrailingSeparators(path)
        guard let slash = lastSeparatorIndex(in: normalized) else { return normalized }
        return String(normalized[normalized.index(after: slash)...])
    }

    private static func parentPath(_ path: String) -> String? {
        let normalized = trimmingTrailingSeparators(path)
        guard let slash = lastSeparatorIndex(in: normalized), slash > normalized.startIndex else { return nil }
        return String(normalized[..<slash])
    }

    private static func isPathLine(_ line: String) -> Bool {
        let text = unquote(line.trimmingCharacters(in: .whitespacesAndNewlines))
        if text.isEmpty || text.utf16.count > 2048 || text.contains("\t") { return false }
        if text.hasPrefix("http://") || text.hasPrefix("https://") { return false }
        if wholeMatch(statusLine, in: line.trimmingCharacters(in: .whitespaces)) != nil { return false }
        if text.contains("/") || text.contains("\\") || text.hasPrefix("~") || text.hasPrefix("./") { return true }
        return wholeMatch(fileNamePattern, in: text) != nil
    }

    private static func isFindCommand(_ command: String?) -> Bool {
        guard let trimmed = command?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !trimmed.isEmpty else {
            return false
        }
        return trimmed == "find" || trimmed.hasPrefix("find ")
    }

    private static func isShell(_ name: String) -> Bool {
        name == "bash" || name == "shell"
    }

    private static func numberedLines(_ body: String) -> [ToolResultPresentation.SourceLine]? {
        let lines = nonEmptyLines(body)
        guard !lines.isEmpty else { return nil }
        var parsed: [ToolResultPresentation.SourceLine] = []
        for line in lines {
            guard let match = wholeMatch(numbered, in: line), let number = Int(match[1]) else { return nil }
            parsed.append(ToolResultPresentation.SourceLine(number: number, text: match[2]))
        }
        return parsed
    }

    private static func splitThought(_ text: String) -> (callout: String, paragraphs: [String]) {
        guard let match = firstMatch(sentenceEnd, in: text),
              let range = Range(match.range, in: text)
        else { return (text, []) }
        let callout = String(text[..<range.upperBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        let rest = String(text[range.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        if rest.isEmpty { return (callout, []) }
        let paragraphs = split(rest, by: paragraphBreak)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return (callout, paragraphs)
    }

    private static func exitCodeIn(_ text: String) -> Int? {
        guard let match = firstMatch(exitCode, in: text) else { return nil }
        return Int(match.groups[1])
    }

    private static func branchIn(_ text: String) -> String? {
        if let quoted = firstMatch(branchQuoted, in: text) { return quoted.groups[1] }
        return firstMatch(headArrow, in: text)?.groups[1]
    }

    private static func titleFlag(_ command: String?) -> String? {
        guard let command, let match = firstMatch(titleFlagPattern, in: command) else { return nil }
        return unquote(match.groups[1])
    }

    private static func languageName(_ path: String) -> String? {
        switch fileExtension(path) {
        case "kt", "kts": "Kotlin"
        case "swift": "Swift"
        case "java": "Java"
        case "ts", "tsx": "TypeScript"
        case "js", "jsx": "JavaScript"
        case "py": "Python"
        case "md": "Markdown"
        case "json": "JSON"
        case "xml": "XML"
        case "sh", "bash", "zsh": "Shell"
        default: nil
        }
    }

    private static func highlightLanguage(_ path: String) -> String? {
        switch fileExtension(path) {
        case "kt", "kts": "kotlin"
        case "swift": "swift"
        case "java": "java"
        case "ts", "tsx": "typescript"
        case "js", "jsx": "javascript"
        case "py": "python"
        case "md": "markdown"
        case "json": "json"
        case "xml": "xml"
        case "sh", "bash", "zsh": "shell"
        default: nil
        }
    }

    private static func fileExtension(_ path: String) -> String {
        let name = fileName(path)
        guard let dot = name.lastIndex(of: "."), dot < name.index(before: name.endIndex) else { return "" }
        return String(name[name.index(after: dot)...]).lowercased()
    }

    private static func countPrefix(_ patch: String, prefix: Character) -> Int {
        kotlinLines(patch).filter { line in
            line.hasPrefix(String(prefix)) && !line.hasPrefix("---") && !line.hasPrefix("+++")
        }.count
    }

    private static func lineCount(_ text: String) -> Int {
        splitLines(text).count
    }

    private static func splitLines(_ text: String) -> [String] {
        if text.isEmpty { return [] }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        if lines.last?.isEmpty == true { return Array(lines.dropLast()) }
        return lines
    }

    private static func nonEmptyLines(_ text: String) -> [String] {
        kotlinLines(text).map(trimmingTrailingWhitespace).filter { !isBlank($0) }
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !isBlank(value) else { return nil }
        return value
    }

    private static func isBlank(_ value: String) -> Bool {
        value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private static func toolLabel(_ name: String) -> String {
        switch name {
        case "bash", "shell": return "Shell"
        case "grep": return "Grep"
        case "glob": return "Glob"
        case "read": return "Read"
        case "write": return "Write"
        case "edit": return "Edit"
        default:
            guard let first = name.first else { return name }
            return String(first).uppercased() + name.dropFirst()
        }
    }

    private static func trimmingTrailingSeparators(_ path: String) -> String {
        var normalized = path
        while normalized.hasSuffix("/") || normalized.hasSuffix("\\") {
            normalized.removeLast()
        }
        return normalized
    }

    private static func lastSeparatorIndex(in path: String) -> String.Index? {
        let slash = path.lastIndex(of: "/")
        let backslash = path.lastIndex(of: "\\")
        switch (slash, backslash) {
        case let (slash?, backslash?):
            return slash > backslash ? slash : backslash
        case let (slash?, nil):
            return slash
        case let (nil, backslash?):
            return backslash
        case (nil, nil):
            return nil
        }
    }

    private static func trimmingTrailingWhitespace(_ text: String) -> String {
        var end = text.endIndex
        while end > text.startIndex {
            let previous = text.index(before: end)
            if text[previous].isWhitespace { end = previous } else { break }
        }
        return String(text[..<end])
    }

    /// Kotlin `String.lines()`: split on LF, CR, and CRLF. A trailing break does not add an empty line.
    private static func kotlinLines(_ text: String) -> [String] {
        if text.isEmpty { return [""] }
        var result: [String] = []
        var start = text.startIndex
        var index = text.startIndex
        while index < text.endIndex {
            let character = text[index]
            if character == "\n" || character == "\r" {
                result.append(String(text[start..<index]))
                text.formIndex(after: &index)
                if character == "\r", index < text.endIndex, text[index] == "\n" {
                    text.formIndex(after: &index)
                }
                start = index
                continue
            }
            text.formIndex(after: &index)
        }
        if start < text.endIndex {
            result.append(String(text[start..<text.endIndex]))
        }
        return result
    }

    private static func wholeMatch(_ regex: NSRegularExpression, in text: String) -> [String]? {
        let ns = text as NSString
        let range = NSRange(location: 0, length: ns.length)
        guard let match = regex.firstMatch(in: text, range: range),
              match.range.location == 0,
              match.range.length == ns.length
        else { return nil }
        return groups(match, in: ns)
    }

    private static func firstMatch(_ regex: NSRegularExpression, in text: String) -> RegexMatch? {
        let ns = text as NSString
        let range = NSRange(location: 0, length: ns.length)
        guard let match = regex.firstMatch(in: text, range: range) else { return nil }
        let values = groups(match, in: ns)
        return RegexMatch(full: values[0], groups: values, range: match.range)
    }

    private static func groups(_ match: NSTextCheckingResult, in text: NSString) -> [String] {
        (0..<match.numberOfRanges).map { index in
            let range = match.range(at: index)
            guard range.location != NSNotFound else { return "" }
            return text.substring(with: range)
        }
    }

    private static func split(_ text: String, by regex: NSRegularExpression) -> [String] {
        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)
        let matches = regex.matches(in: text, range: full)
        var parts: [String] = []
        var cursor = 0
        for match in matches {
            let start = match.range.location
            if start >= cursor {
                parts.append(ns.substring(with: NSRange(location: cursor, length: start - cursor)))
            }
            cursor = start + match.range.length
        }
        if cursor <= ns.length {
            parts.append(ns.substring(from: cursor))
        }
        return parts
    }

    private struct RegexMatch {
        var full: String
        var groups: [String]
        var range: NSRange
    }

    private static let statusLine = regex(#"^([ MADRC?!]{2}) (.+)$"#)
    private static let prUrl = regex(#"https?://github\.com/[^/\s]+/[^/\s]+/pull/(\d+)"#)
    private static let branchQuoted = regex(#"branch '([^']+)'"#)
    private static let headArrow = regex(#"HEAD -> (\S+)"#)
    private static let titleFlagPattern = regex(#"(?:--title|-t)\s+("[^"]+"|\S+)"#)
    private static let grepColon = regex(#"^(.+):(\d+):(.*)$"#)
    private static let grepHit = regex(#"^(\d+):\s?(.*)$"#)
    private static let numbered = regex(#"^(\d+):\s?(.*)$"#)
    /// Punctuation ends a sentence only when whitespace or the end follows.
    private static let sentenceEnd = regex("[.!?](?=" + #"\s|$)"#)
    private static let paragraphBreak = regex(#"\n\s*\n"#)
    private static let exitCode = regex(#"(?:exited with code|exit)\s+(\d+)"#, options: .caseInsensitive)
    private static let fileNamePattern = regex(#"^[^/\s]+\.[A-Za-z0-9]{1,8}$"#)

    private static func regex(_ pattern: String, options: NSRegularExpression.Options = []) -> NSRegularExpression {
        do {
            return try NSRegularExpression(pattern: pattern, options: options)
        } catch {
            preconditionFailure("Invalid tool-result pattern \(pattern): \(error)")
        }
    }
}


