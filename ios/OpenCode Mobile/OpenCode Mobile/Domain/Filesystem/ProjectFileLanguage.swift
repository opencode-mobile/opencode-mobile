import Foundation

/// A TextMate grammar language for the File Viewer. `id` is the canonical language name the
/// shared engine resolves (`shared/markdown-core/.../textmate-grammars/manifest.json`).
/// Android implements the same table; both are tested against
/// `shared/file-languages/file-language-cases.json`, so change all three together.
nonisolated struct ProjectFileLanguage: Equatable, Hashable, Sendable {
    let id: String
    let displayName: String

    static let plainTextName = "Plain text"

    /// File name (or relative path) → language. Unknown → nil (plain text).
    static func forFileName(_ fileName: String) -> ProjectFileLanguage? {
        let name = fileName.split(separator: "/").last.map(String.init) ?? fileName
        let lowered = name.lowercased()
        if let byName = fileNames[lowered] { return byName }
        if lowered.hasPrefix("dockerfile.") || lowered.hasPrefix("containerfile.") { return docker }
        let ext = ProjectPaths.fileExtension(of: name)
        guard !ext.isEmpty else { return nil }
        return extensions[ext]
    }

    private static let docker = ProjectFileLanguage(id: "docker", displayName: "Dockerfile")
    private static let make = ProjectFileLanguage(id: "make", displayName: "Makefile")
    private static let ruby = ProjectFileLanguage(id: "ruby", displayName: "Ruby")
    private static let shell = ProjectFileLanguage(id: "shellscript", displayName: "Shell")

    private struct Entry {
        let language: ProjectFileLanguage
        let keys: [String]

        init(_ id: String, _ displayName: String, _ keys: [String]) {
            language = ProjectFileLanguage(id: id, displayName: displayName)
            self.keys = keys
        }

        init(_ language: ProjectFileLanguage, _ keys: [String]) {
            self.language = language
            self.keys = keys
        }
    }

    /// Lowercased extension → language. `.m` is Objective-C (mobile projects), not MATLAB.
    private static let extensionEntries: [Entry] = [
        Entry("kotlin", "Kotlin", ["kt", "kts"]),
        Entry("swift", "Swift", ["swift"]),
        Entry("typescript", "TypeScript", ["ts", "cts", "mts", "tsx"]),
        Entry("javascript", "JavaScript", ["js", "cjs", "mjs", "jsx"]),
        Entry("json", "JSON", ["json", "jsonc", "json5", "xcstrings"]),
        Entry("markdown", "Markdown", ["md", "markdown"]),
        Entry(shell, ["sh", "bash", "zsh", "command"]),
        Entry("yaml", "YAML", ["yaml", "yml"]),
        Entry("css", "CSS", ["css"]),
        Entry("html", "HTML", ["html", "htm"]),
        Entry("python", "Python", ["py", "pyi"]),
        Entry("rust", "Rust", ["rs"]),
        Entry("go", "Go", ["go"]),
        Entry("diff", "Diff", ["diff", "patch"]),
        Entry("java", "Java", ["java"]),
        Entry("csharp", "C#", ["cs"]),
        Entry("c", "C", ["c", "h"]),
        Entry("cpp", "C++", ["cpp", "cc", "cxx", "hpp", "hh", "hxx"]),
        Entry("objective-c", "Objective-C", ["m", "mm"]),
        Entry(ruby, ["rb"]),
        Entry("php", "PHP", ["php"]),
        Entry("sql", "SQL", ["sql"]),
        Entry("toml", "TOML", ["toml"]),
        Entry("xml", "XML", ["xml", "plist", "svg", "xib", "storyboard", "entitlements", "xcprivacy", "xcscheme"]),
        Entry("scala", "Scala", ["scala", "sc"]),
        Entry("lua", "Lua", ["lua"]),
        Entry(docker, ["dockerfile"]),
        Entry("vue", "Vue", ["vue"]),
        Entry("svelte", "Svelte", ["svelte"]),
        Entry("graphql", "GraphQL", ["graphql", "gql"]),
        Entry("powershell", "PowerShell", ["ps1", "psm1", "psd1"]),
        Entry("perl", "Perl", ["pl", "pm"]),
        Entry("haskell", "Haskell", ["hs"]),
        Entry("elixir", "Elixir", ["ex", "exs"]),
        Entry("erlang", "Erlang", ["erl", "hrl"]),
        Entry("r", "R", ["r"]),
        Entry("zig", "Zig", ["zig"]),
        Entry("dart", "Dart", ["dart"]),
        Entry("groovy", "Groovy", ["groovy", "gradle"]),
        Entry("clojure", "Clojure", ["clj", "cljs", "cljc", "edn"]),
        Entry("coffee", "CoffeeScript", ["coffee"]),
        Entry("fortran-free-form", "Fortran (Free Form)", ["f90", "f95", "f03", "f08", "f18"]),
        Entry("fortran-fixed-form", "Fortran (Fixed Form)", ["f", "f77", "for"]),
        Entry("pascal", "Pascal", ["pas", "dpr"]),
        Entry("vb", "Visual Basic", ["vb", "vbs"]),
        Entry("asm", "Assembly", ["asm", "s"]),
        Entry("cobol", "COBOL", ["cob", "cbl"]),
        Entry("julia", "Julia", ["jl"]),
        Entry("sas", "SAS", ["sas"]),
        Entry("abap", "ABAP", ["abap"]),
        Entry("apex", "Apex", ["cls", "trigger", "apex"]),
        Entry("crystal", "Crystal", ["cr"]),
        Entry("fsharp", "F#", ["fs", "fsi", "fsx"]),
        Entry("awk", "AWK", ["awk"]),
        Entry("nim", "Nim", ["nim"]),
        Entry("ada", "Ada", ["ada", "adb", "ads"]),
        Entry("smalltalk", "Smalltalk", ["st"]),
        Entry("ocaml", "OCaml", ["ml", "mli"]),
        Entry("common-lisp", "Common Lisp", ["lisp", "lsp"]),
        Entry(make, ["mk", "mak"]),
    ]

    /// Lowercased whole file name → language, checked before the extension.
    private static let fileNameEntries: [Entry] = [
        Entry(docker, ["dockerfile", "containerfile"]),
        Entry(make, ["makefile", "gnumakefile"]),
        Entry(ruby, ["gemfile", "rakefile", "podfile", "fastfile", "appfile", "brewfile"]),
        Entry(shell, [".bashrc", ".bash_profile", ".zshrc", ".zprofile", ".zshenv", ".profile", "gradlew"]),
    ]

    static let extensions: [String: ProjectFileLanguage] = index(extensionEntries)
    static let fileNames: [String: ProjectFileLanguage] = index(fileNameEntries)

    private static func index(_ entries: [Entry]) -> [String: ProjectFileLanguage] {
        var map: [String: ProjectFileLanguage] = [:]
        for entry in entries {
            for key in entry.keys where map[key] == nil {
                map[key] = entry.language
            }
        }
        return map
    }
}
