package dev.opencode.mobile.domain.filesystem

/**
 * Path rules for the opencode server's filesystem, which may be POSIX or Windows. Paths are
 * kept in the server's native form: `/Users/me`, `C:\Users\me`, or `\\server\share\dir`.
 * Drive roots (`C:\`), UNC share roots (`\\server\share`) and `/` have no parent. Never use
 * the phone's own path rules (`java.io.File`) on a server path.
 */
object ServerPathPolicy {
    private val driveRegex = Regex("^([A-Za-z]):")
    private val repeatedSlashRegex = Regex("/{2,}")

    /** Parent directory, or null at a root or for a relative path. */
    fun parentPath(path: String): String? {
        val parsed = parse(path) ?: return null
        if (parsed.root.isEmpty() || parsed.segments.isEmpty()) return null
        return parsed.copy(segments = parsed.segments.dropLast(1)).format()
    }

    /** Last component; a root names itself. */
    fun name(path: String): String {
        val parsed = parse(path) ?: return path
        return parsed.segments.lastOrNull() ?: parsed.format()
    }

    fun isAbsolute(path: String): Boolean = parse(path)?.root?.isNotEmpty() == true

    /** Canonical absolute form: native separators, no trailing separator, `.` and `..` resolved. */
    fun canonical(path: String): String = parse(path)?.format() ?: path

    /**
     * Resolves [child] (relative, or absolute such as a v2 cross-drive entry) against [base],
     * splitting on the separators of [base]'s platform.
     */
    fun resolve(
        base: String,
        child: String,
    ): String {
        val parsedBase = parse(base) ?: return canonical(child)
        // A POSIX server can name a folder `C:x`; only `/` makes its entries absolute.
        val childIsAbsolute = if (parsedBase.windows) isAbsolute(child) else child.startsWith("/")
        if (childIsAbsolute) return canonical(child)
        val childSegments = splitSegments(child, parsedBase.windows)
        return parsedBase.copy(segments = resolveSegments(parsedBase.segments + childSegments)).format()
    }

    /**
     * Go to path input: an absolute path, `~` (the server's home), a bare drive (`D:`), a UNC
     * share (`\\server\share` or `//server/share`), or a path relative to [currentPath].
     * Surrounding quotes from a Windows "Copy as path" are dropped. Null when nothing usable.
     */
    fun resolveInput(
        input: String,
        currentPath: String,
        homePath: String,
    ): String? {
        var value = input.trim()
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value = value.substring(1, value.length - 1).trim()
        }
        if (value.isEmpty()) return null
        if (value == "~" || value.startsWith("~/") || value.startsWith("~\\")) {
            val home = homePath.ifBlank { currentPath }
            if (home.isBlank()) return null
            return resolve(home, value.substring(1).trimStart('/', '\\'))
        }
        if (isAbsolute(value)) return canonical(value)
        if (!isAbsolute(currentPath)) return null
        return resolve(currentPath, value)
    }

    /**
     * The root of [path]: `/`, a drive root such as `C:\`, or a UNC share root such as
     * `\\server\share`. Empty for a relative path.
     */
    fun root(path: String): String = parse(path)?.root.orEmpty()

    /** True when [path] has Windows shape (a drive or UNC root). */
    fun isWindows(path: String): Boolean = parse(path)?.windows == true

    /**
     * Path field text for [folder]: the folder plus its native separator, so the field lists its
     * children. Roots are returned unchanged.
     */
    fun fieldText(folder: String): String {
        val parsed = parse(folder) ?: return folder
        val formatted = parsed.format()
        if (parsed.segments.isEmpty() && parsed.root.isNotEmpty()) return formatted
        return formatted + if (parsed.windows) "\\" else "/"
    }

    /**
     * Splits path field [text] into the folder to list and the name prefix that filters it.
     * Text ending in a separator, or naming a root, lists that folder with no filter; otherwise
     * the last component filters its parent. `~` expands to [home]; relative text resolves
     * against [home]. On a POSIX server (a home that is not Windows-shaped) repeated slashes
     * collapse, so `//Volumes` is `/Volumes` rather than a UNC share. Null directory when
     * nothing usable was typed.
     */
    fun fieldParts(
        text: String,
        home: String,
    ): FieldParts {
        val input = if (isWindows(home)) text else text.replace(repeatedSlashRegex, "/")
        val resolved = resolveInput(input, home, home) ?: return FieldParts(directory = null, filter = "")
        var trimmed = input.trim()
        if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            trimmed = trimmed.substring(1, trimmed.length - 1).trim()
        }
        val windows = isWindows(resolved)
        val endsWithSeparator = trimmed.endsWith("/") || (windows && trimmed.endsWith("\\"))
        // A lone "." starts a hidden-folder filter in the folder it follows.
        val lastSegment = trimmed.substringAfterLast('/').let { if (windows) it.substringAfterLast('\\') else it }
        if (!endsWithSeparator && lastSegment == ".") return FieldParts(directory = resolved, filter = ".")
        if (endsWithSeparator || resolved == root(resolved)) {
            return FieldParts(directory = resolved, filter = "")
        }
        val parent = parentPath(resolved) ?: return FieldParts(directory = resolved, filter = "")
        return FieldParts(directory = parent, filter = name(resolved))
    }

    data class FieldParts(
        val directory: String?,
        val filter: String,
    )

    private data class Parsed(
        val root: String,
        val segments: List<String>,
        val windows: Boolean,
    ) {
        fun format(): String {
            if (segments.isEmpty()) return root
            val separator = if (windows) "\\" else "/"
            val prefix = if (root.endsWith(separator) || root.isEmpty()) root else root + separator
            return prefix + segments.joinToString(separator)
        }
    }

    private fun parse(path: String): Parsed? {
        if (path.isEmpty()) return null
        if (isUnc(path)) {
            val parts = splitSegments(path, windows = true)
            val shareRoot = parts.take(2)
            val root = "\\\\" + shareRoot.joinToString("\\")
            return Parsed(root = root, segments = resolveSegments(parts.drop(2)), windows = true)
        }
        driveRegex.find(path)?.let { match ->
            val root = match.groupValues[1].uppercase() + ":\\"
            val rest = path.substring(match.range.last + 1)
            return Parsed(root = root, segments = resolveSegments(splitSegments(rest, windows = true)), windows = true)
        }
        if (path.startsWith("/")) {
            return Parsed(root = "/", segments = resolveSegments(splitSegments(path, windows = false)), windows = false)
        }
        return Parsed(root = "", segments = splitSegments(path, windows = false), windows = false)
    }

    private fun isUnc(path: String): Boolean =
        (path.startsWith("\\\\") || path.startsWith("//")) && path.getOrNull(2)?.let { it != '/' && it != '\\' } == true

    private fun splitSegments(
        path: String,
        windows: Boolean,
    ): List<String> {
        val separators = if (windows) charArrayOf('/', '\\') else charArrayOf('/')
        return path.split(*separators).filter { it.isNotEmpty() }
    }

    private fun resolveSegments(segments: List<String>): List<String> {
        val resolved = ArrayList<String>(segments.size)
        for (segment in segments) {
            when (segment) {
                "." -> Unit
                ".." -> resolved.removeLastOrNull()
                else -> resolved += segment
            }
        }
        return resolved
    }
}
