import Foundation

/// A TextMate grammar language for the File Viewer. `id` is the canonical language name the
/// shared engine resolves (`shared/markdown-core/.../textmate-grammars/manifest.json`).
nonisolated struct ProjectFileLanguage: Equatable, Hashable, Sendable {
    let id: String
    let displayName: String

    static let plainTextName = "Plain text"

    /// File name → language. Unknown → nil (plain text).
    static func forFileName(_ fileName: String) -> ProjectFileLanguage? {
        let lowered = fileName.lowercased()
        if let byName = fileNames[lowered] { return byName }
        let ext = ProjectPaths.fileExtension(of: fileName)
        guard !ext.isEmpty else { return nil }
        return extensions[ext]
    }

    private struct Entry {
        let id: String
        let displayName: String
        let extensions: [String]
    }

    /// Manifest languages and their file-extension aliases (non-extension aliases such as
    /// `c#` or `shell` are left out), plus a few common extensions the manifest names only
    /// as embedded grammars (`h`, `hpp`, `jsx`, `tsx`, `htm`).
    private static let entries: [Entry] = [
        Entry(id: "kotlin", displayName: "Kotlin", extensions: ["kt", "kts"]),
        Entry(id: "swift", displayName: "Swift", extensions: ["swift"]),
        Entry(id: "typescript", displayName: "TypeScript", extensions: ["ts", "cts", "mts", "tsx"]),
        Entry(id: "javascript", displayName: "JavaScript", extensions: ["js", "cjs", "mjs", "jsx"]),
        Entry(id: "json", displayName: "JSON", extensions: ["json"]),
        Entry(id: "markdown", displayName: "Markdown", extensions: ["md", "markdown"]),
        Entry(id: "shellscript", displayName: "Shell", extensions: ["sh", "bash", "zsh"]),
        Entry(id: "yaml", displayName: "YAML", extensions: ["yaml", "yml"]),
        Entry(id: "css", displayName: "CSS", extensions: ["css"]),
        Entry(id: "html", displayName: "HTML", extensions: ["html", "htm"]),
        Entry(id: "python", displayName: "Python", extensions: ["py"]),
        Entry(id: "rust", displayName: "Rust", extensions: ["rs"]),
        Entry(id: "go", displayName: "Go", extensions: ["go"]),
        Entry(id: "diff", displayName: "Diff", extensions: ["diff", "patch"]),
        Entry(id: "java", displayName: "Java", extensions: ["java"]),
        Entry(id: "csharp", displayName: "C#", extensions: ["cs"]),
        Entry(id: "c", displayName: "C", extensions: ["c", "h"]),
        Entry(id: "cpp", displayName: "C++", extensions: ["cpp", "cc", "cxx", "hpp", "hh", "hxx"]),
        Entry(id: "ruby", displayName: "Ruby", extensions: ["rb"]),
        Entry(id: "php", displayName: "PHP", extensions: ["php"]),
        Entry(id: "sql", displayName: "SQL", extensions: ["sql"]),
        Entry(id: "toml", displayName: "TOML", extensions: ["toml"]),
        Entry(id: "xml", displayName: "XML", extensions: ["xml"]),
        Entry(id: "scala", displayName: "Scala", extensions: ["scala"]),
        Entry(id: "lua", displayName: "Lua", extensions: ["lua"]),
        Entry(id: "docker", displayName: "Dockerfile", extensions: ["dockerfile"]),
        Entry(id: "vue", displayName: "Vue", extensions: ["vue"]),
        Entry(id: "svelte", displayName: "Svelte", extensions: ["svelte"]),
        Entry(id: "graphql", displayName: "GraphQL", extensions: ["graphql", "gql"]),
        Entry(id: "powershell", displayName: "PowerShell", extensions: ["ps1"]),
        Entry(id: "perl", displayName: "Perl", extensions: ["pl", "pm"]),
        Entry(id: "haskell", displayName: "Haskell", extensions: ["hs"]),
        Entry(id: "elixir", displayName: "Elixir", extensions: ["ex", "exs"]),
        Entry(id: "erlang", displayName: "Erlang", extensions: ["erl"]),
        Entry(id: "r", displayName: "R", extensions: ["r"]),
        Entry(id: "zig", displayName: "Zig", extensions: ["zig"]),
        Entry(id: "dart", displayName: "Dart", extensions: ["dart"]),
        Entry(id: "groovy", displayName: "Groovy", extensions: ["groovy", "gradle"]),
        Entry(id: "objective-c", displayName: "Objective-C", extensions: ["m"]),
        Entry(id: "clojure", displayName: "Clojure", extensions: ["clj", "cljs"]),
        Entry(id: "coffee", displayName: "CoffeeScript", extensions: ["coffee"]),
        Entry(id: "fortran-free-form", displayName: "Fortran (Free Form)", extensions: ["f90", "f95", "f03", "f08", "f18"]),
        Entry(id: "fortran-fixed-form", displayName: "Fortran (Fixed Form)", extensions: ["f", "f77", "for"]),
        Entry(id: "pascal", displayName: "Pascal", extensions: ["pas"]),
        Entry(id: "vb", displayName: "Visual Basic", extensions: ["vb"]),
        Entry(id: "asm", displayName: "Assembly", extensions: ["asm", "s"]),
        Entry(id: "cobol", displayName: "COBOL", extensions: ["cob", "cbl"]),
        Entry(id: "julia", displayName: "Julia", extensions: ["jl"]),
        Entry(id: "sas", displayName: "SAS", extensions: ["sas"]),
        Entry(id: "abap", displayName: "ABAP", extensions: ["abap"]),
        Entry(id: "apex", displayName: "Apex", extensions: ["cls", "apex"]),
        Entry(id: "crystal", displayName: "Crystal", extensions: ["cr"]),
        Entry(id: "fsharp", displayName: "F#", extensions: ["fs", "fsx"]),
        Entry(id: "awk", displayName: "AWK", extensions: ["awk"]),
        Entry(id: "nim", displayName: "Nim", extensions: ["nim"]),
        Entry(id: "ada", displayName: "Ada", extensions: ["ada", "adb", "ads"]),
        Entry(id: "ocaml", displayName: "OCaml", extensions: ["ml", "mli"]),
        Entry(id: "common-lisp", displayName: "Common Lisp", extensions: ["lisp"]),
        Entry(id: "make", displayName: "Makefile", extensions: ["mk"]),
    ]

    private static let extensions: [String: ProjectFileLanguage] = {
        var map: [String: ProjectFileLanguage] = [:]
        for entry in entries {
            let language = ProjectFileLanguage(id: entry.id, displayName: entry.displayName)
            for ext in entry.extensions where map[ext] == nil {
                map[ext] = language
            }
        }
        return map
    }()

    private static let fileNames: [String: ProjectFileLanguage] = [
        "dockerfile": ProjectFileLanguage(id: "docker", displayName: "Dockerfile"),
        "makefile": ProjectFileLanguage(id: "make", displayName: "Makefile"),
        "gnumakefile": ProjectFileLanguage(id: "make", displayName: "Makefile"),
    ]
}
