package dev.opencode.mobile.domain.filesystem

/**
 * File extension or well-known file name → TextMate grammar for the File Viewer. [grammar] is a
 * canonical language name from `shared/markdown-core/.../textmate-grammars/manifest.json`; null
 * means plain text. iOS implements the same table; both are tested against
 * `shared/file-languages/file-language-cases.json`, so change all three together.
 */
data class ProjectFileLanguage(
    val grammar: String?,
    val displayName: String,
) {
    val isMarkdown: Boolean get() = grammar == MARKDOWN

    companion object {
        private const val MARKDOWN = "markdown"

        val PlainText = ProjectFileLanguage(grammar = null, displayName = "Plain text")

        private val Docker = ProjectFileLanguage("docker", "Dockerfile")
        private val Make = ProjectFileLanguage("make", "Makefile")
        private val Ruby = ProjectFileLanguage("ruby", "Ruby")
        private val Shell = ProjectFileLanguage("shellscript", "Shell")

        /** Lowercased extension → language. `.m` is Objective-C (mobile projects), not MATLAB. */
        internal val extensions: Map<String, ProjectFileLanguage> =
            listOf(
                lang("kotlin", "Kotlin", "kt", "kts"),
                lang("swift", "Swift", "swift"),
                lang("typescript", "TypeScript", "ts", "cts", "mts", "tsx"),
                lang("javascript", "JavaScript", "js", "cjs", "mjs", "jsx"),
                lang("json", "JSON", "json", "jsonc", "json5", "xcstrings"),
                lang(MARKDOWN, "Markdown", "md", "markdown"),
                Shell to listOf("sh", "bash", "zsh", "command"),
                lang("yaml", "YAML", "yaml", "yml"),
                lang("css", "CSS", "css"),
                lang("html", "HTML", "html", "htm"),
                lang("python", "Python", "py", "pyi"),
                lang("rust", "Rust", "rs"),
                lang("go", "Go", "go"),
                lang("diff", "Diff", "diff", "patch"),
                lang("java", "Java", "java"),
                lang("csharp", "C#", "cs"),
                lang("c", "C", "c", "h"),
                lang("cpp", "C++", "cpp", "cc", "cxx", "hpp", "hh", "hxx"),
                lang("objective-c", "Objective-C", "m", "mm"),
                Ruby to listOf("rb"),
                lang("php", "PHP", "php"),
                lang("sql", "SQL", "sql"),
                lang("toml", "TOML", "toml"),
                lang("xml", "XML", "xml", "plist", "svg", "xib", "storyboard", "entitlements", "xcprivacy", "xcscheme"),
                lang("scala", "Scala", "scala", "sc"),
                lang("lua", "Lua", "lua"),
                Docker to listOf("dockerfile"),
                lang("vue", "Vue", "vue"),
                lang("svelte", "Svelte", "svelte"),
                lang("graphql", "GraphQL", "graphql", "gql"),
                lang("powershell", "PowerShell", "ps1", "psm1", "psd1"),
                lang("perl", "Perl", "pl", "pm"),
                lang("haskell", "Haskell", "hs"),
                lang("elixir", "Elixir", "ex", "exs"),
                lang("erlang", "Erlang", "erl", "hrl"),
                lang("r", "R", "r"),
                lang("zig", "Zig", "zig"),
                lang("dart", "Dart", "dart"),
                lang("groovy", "Groovy", "groovy", "gradle"),
                lang("clojure", "Clojure", "clj", "cljs", "cljc", "edn"),
                lang("coffee", "CoffeeScript", "coffee"),
                lang("fortran-free-form", "Fortran (Free Form)", "f90", "f95", "f03", "f08", "f18"),
                lang("fortran-fixed-form", "Fortran (Fixed Form)", "f", "f77", "for"),
                lang("pascal", "Pascal", "pas", "dpr"),
                lang("vb", "Visual Basic", "vb", "vbs"),
                lang("asm", "Assembly", "asm", "s"),
                lang("cobol", "COBOL", "cob", "cbl"),
                lang("julia", "Julia", "jl"),
                lang("sas", "SAS", "sas"),
                lang("abap", "ABAP", "abap"),
                lang("apex", "Apex", "cls", "trigger", "apex"),
                lang("crystal", "Crystal", "cr"),
                lang("fsharp", "F#", "fs", "fsi", "fsx"),
                lang("awk", "AWK", "awk"),
                lang("nim", "Nim", "nim"),
                lang("ada", "Ada", "ada", "adb", "ads"),
                lang("smalltalk", "Smalltalk", "st"),
                lang("ocaml", "OCaml", "ml", "mli"),
                lang("common-lisp", "Common Lisp", "lisp", "lsp"),
                Make to listOf("mk", "mak"),
            ).flatMap { (language, extensions) -> extensions.map { it to language } }
                .toMap()

        /** Lowercased whole file name → language, checked before the extension. */
        internal val fileNames: Map<String, ProjectFileLanguage> =
            listOf(
                Docker to listOf("dockerfile", "containerfile"),
                Make to listOf("makefile", "gnumakefile"),
                Ruby to listOf("gemfile", "rakefile", "podfile", "fastfile", "appfile", "brewfile"),
                Shell to listOf(".bashrc", ".bash_profile", ".zshrc", ".zprofile", ".zshenv", ".profile", "gradlew"),
            ).flatMap { (language, names) -> names.map { it to language } }
                .toMap()

        fun forPath(path: String): ProjectFileLanguage {
            val name = path.substringAfterLast('/').lowercase()
            fileNames[name]?.let { return it }
            if (name.startsWith("dockerfile.") || name.startsWith("containerfile.")) return Docker
            val extension = name.substringAfterLast('.', "")
            if (extension.isEmpty() || extension == name.removePrefix(".")) {
                return PlainText
            }
            return extensions[extension] ?: PlainText
        }

        private fun lang(
            grammar: String,
            displayName: String,
            vararg extensions: String,
        ): Pair<ProjectFileLanguage, List<String>> = ProjectFileLanguage(grammar, displayName) to extensions.toList()
    }
}
