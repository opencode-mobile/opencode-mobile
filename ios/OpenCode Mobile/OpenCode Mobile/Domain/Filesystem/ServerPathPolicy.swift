import Foundation

/// Path rules for the opencode server's filesystem, which may be POSIX or Windows. Paths are
/// kept in the server's native form: `/Users/me`, `C:\Users\me`, or `\\server\share\dir`.
/// Drive roots (`C:\`), UNC share roots (`\\server\share`) and `/` have no parent. Never use
/// the phone's own path rules (`URL(fileURLWithPath:)`) on a server path.
nonisolated enum ServerPathPolicy {
    /// Parent directory, or nil at a root or for a relative path.
    static func parentPath(_ path: String) -> String? {
        guard var parsed = parse(path), !parsed.root.isEmpty, !parsed.segments.isEmpty else { return nil }
        parsed.segments.removeLast()
        return parsed.formatted
    }

    /// Last component; a root names itself.
    static func name(_ path: String) -> String {
        guard let parsed = parse(path) else { return path }
        return parsed.segments.last ?? parsed.formatted
    }

    static func isAbsolute(_ path: String) -> Bool {
        parse(path)?.root.isEmpty == false
    }

    /// Canonical absolute form: native separators, no trailing separator, `.` and `..` resolved.
    static func canonical(_ path: String) -> String {
        parse(path)?.formatted ?? path
    }

    /// Resolves `child` (relative, or absolute such as a v2 cross-drive entry) against `base`,
    /// splitting on the separators of `base`'s platform.
    static func resolve(base: String, child: String) -> String {
        guard var parsed = parse(base) else { return canonical(child) }
        // A POSIX server can name a folder `C:x`; only `/` makes its entries absolute.
        let childIsAbsolute = parsed.windows ? isAbsolute(child) : child.hasPrefix("/")
        if childIsAbsolute { return canonical(child) }
        parsed.segments = resolveSegments(parsed.segments + splitSegments(child, windows: parsed.windows))
        return parsed.formatted
    }

    /// Root of an absolute path: `/`, `C:\`, or `\\server\share`; empty for a relative path.
    static func root(_ path: String) -> String {
        parse(path)?.root ?? ""
    }

    /// True for `/`, a drive root, or a UNC share root.
    static func isRoot(_ path: String) -> Bool {
        guard let parsed = parse(path) else { return false }
        return !parsed.root.isEmpty && parsed.segments.isEmpty
    }

    /// True for drive-letter and UNC paths, whose names compare case-insensitively.
    static func isWindows(_ path: String) -> Bool {
        parse(path)?.windows == true
    }

    /// Path field text for a folder: the canonical folder plus its native separator, so typing
    /// continues inside it. Roots are returned as they format (`/`, `C:\`, `\\server\share`).
    static func fieldText(_ folder: String) -> String {
        guard let parsed = parse(folder), !parsed.root.isEmpty else { return folder }
        if parsed.segments.isEmpty { return parsed.formatted }
        return parsed.formatted + (parsed.windows ? "\\" : "/")
    }

    /// Splits Add project path field text into the folder to list and the name prefix typed
    /// after its last separator. `~` and relative text resolve against `home`. Text ending in a
    /// separator, or resolving to a root, lists that folder with no filter. A leading `//` is a
    /// UNC share only when `home` is Windows-shaped; on a POSIX home repeated slashes collapse.
    static func fieldParts(_ text: String, home: String) -> (directory: String?, filter: String) {
        var value = unquoted(text)
        if !isWindows(home) {
            value = value.replacingOccurrences(of: "/{2,}", with: "/", options: .regularExpression)
        }
        guard let resolved = resolveInput(value, currentPath: home, homePath: home) else { return (nil, "") }
        let endsWithSeparator = value.hasSuffix("/") || (isWindows(resolved) && value.hasSuffix("\\"))
        if endsWithSeparator || isRoot(resolved) {
            return (resolved, "")
        }
        return (parentPath(resolved), name(resolved))
    }

    /// Go to path input: an absolute path, `~` (the server's home), a bare drive (`D:`), a UNC
    /// share (`\\server\share` or `//server/share`), or a path relative to `currentPath`.
    /// Surrounding quotes from a Windows "Copy as path" are dropped. Nil when nothing usable.
    static func resolveInput(_ input: String, currentPath: String, homePath: String) -> String? {
        let value = unquoted(input)
        if value.isEmpty { return nil }
        if value == "~" || value.hasPrefix("~/") || value.hasPrefix("~\\") {
            let home = homePath.trimmingCharacters(in: .whitespaces).isEmpty ? currentPath : homePath
            if home.trimmingCharacters(in: .whitespaces).isEmpty { return nil }
            let rest = String(value.dropFirst()).trimmingCharacters(in: CharacterSet(charactersIn: "/\\"))
            return resolve(base: home, child: rest)
        }
        if isAbsolute(value) { return canonical(value) }
        guard isAbsolute(currentPath) else { return nil }
        return resolve(base: currentPath, child: value)
    }

    private static func unquoted(_ input: String) -> String {
        var value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.count >= 2, value.hasPrefix("\""), value.hasSuffix("\"") {
            value = String(value.dropFirst().dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return value
    }

    private struct Parsed {
        var root: String
        var segments: [String]
        var windows: Bool

        var formatted: String {
            if segments.isEmpty { return root }
            let separator = windows ? "\\" : "/"
            let prefix = root.isEmpty || root.hasSuffix(separator) ? root : root + separator
            return prefix + segments.joined(separator: separator)
        }
    }

    private static func parse(_ path: String) -> Parsed? {
        if path.isEmpty { return nil }
        if isUNC(path) {
            let parts = splitSegments(path, windows: true)
            let root = "\\\\" + parts.prefix(2).joined(separator: "\\")
            return Parsed(root: root, segments: resolveSegments(Array(parts.dropFirst(2))), windows: true)
        }
        if let drive = driveLetter(path) {
            let rest = String(path.dropFirst(2))
            return Parsed(
                root: drive.uppercased() + ":\\",
                segments: resolveSegments(splitSegments(rest, windows: true)),
                windows: true
            )
        }
        if path.hasPrefix("/") {
            return Parsed(root: "/", segments: resolveSegments(splitSegments(path, windows: false)), windows: false)
        }
        return Parsed(root: "", segments: splitSegments(path, windows: false), windows: false)
    }

    private static func isUNC(_ path: String) -> Bool {
        guard path.hasPrefix("\\\\") || path.hasPrefix("//") else { return false }
        let characters = Array(path)
        return characters.count > 2 && characters[2] != "/" && characters[2] != "\\"
    }

    private static func driveLetter(_ path: String) -> String? {
        let characters = Array(path.prefix(2))
        guard characters.count == 2, characters[1] == ":",
              characters[0].isASCII, characters[0].isLetter
        else { return nil }
        return String(characters[0])
    }

    private static func splitSegments(_ path: String, windows: Bool) -> [String] {
        path
            .split(whereSeparator: { $0 == "/" || (windows && $0 == "\\") })
            .map(String.init)
    }

    private static func resolveSegments(_ segments: [String]) -> [String] {
        var resolved: [String] = []
        for segment in segments {
            switch segment {
            case ".": continue
            case "..": _ = resolved.popLast()
            default: resolved.append(segment)
            }
        }
        return resolved
    }
}
