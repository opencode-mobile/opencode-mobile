package dev.opencode.mobile.domain.filesystem

/**
 * File extension → TextMate grammar for the File Viewer. [grammar] is a canonical language name
 * from `shared/markdown-core/.../textmate-grammars/manifest.json`; null means plain text.
 */
data class ProjectFileLanguage(
    val grammar: String?,
    val displayName: String,
) {
    val isMarkdown: Boolean get() = grammar == MARKDOWN

    companion object {
        private const val MARKDOWN = "markdown"

        val PlainText = ProjectFileLanguage(grammar = null, displayName = "Plain text")

        private val languages: Map<String, ProjectFileLanguage> =
            listOf(
                lang("kotlin", "Kotlin", "kt", "kts"),
                lang("swift", "Swift", "swift"),
                lang("typescript", "TypeScript", "ts", "cts", "mts", "tsx"),
                lang("javascript", "JavaScript", "js", "cjs", "mjs", "jsx"),
                lang("json", "JSON", "json", "jsonc", "json5"),
                lang(MARKDOWN, "Markdown", "md", "markdown"),
                lang("shellscript", "Shell", "sh", "bash", "zsh", "shell", "command"),
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
                lang("ruby", "Ruby", "rb"),
                lang("php", "PHP", "php"),
                lang("sql", "SQL", "sql"),
                lang("toml", "TOML", "toml"),
                lang("xml", "XML", "xml", "plist", "svg", "xib", "storyboard"),
                lang("scala", "Scala", "scala", "sc"),
                lang("lua", "Lua", "lua"),
                lang("docker", "Dockerfile", "dockerfile"),
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
                lang("matlab", "MATLAB", "m"),
                lang("groovy", "Groovy", "groovy", "gradle"),
                lang("objective-c", "Objective-C", "mm"),
                lang("clojure", "Clojure", "clj", "cljs", "cljc", "edn"),
                lang("coffee", "CoffeeScript", "coffee"),
                lang("fortran-free-form", "Fortran (Free Form)", "f90", "f95", "f03", "f08", "f18"),
                lang("fortran-fixed-form", "Fortran (Fixed Form)", "f", "f77", "for"),
                lang("pascal", "Pascal", "pas", "dpr"),
                lang("vb", "Visual Basic", "vb", "vbs", "cmd", "bat"),
                lang("asm", "Assembly", "asm", "s"),
                lang("cobol", "COBOL", "cob", "cbl"),
                lang("julia", "Julia", "jl"),
                lang("sas", "SAS", "sas"),
                lang("abap", "ABAP", "abap"),
                lang("apex", "Apex", "cls", "trigger"),
                lang("crystal", "Crystal", "cr"),
                lang("fsharp", "F#", "fs", "fsi", "fsx"),
                lang("awk", "AWK", "awk"),
                lang("nim", "Nim", "nim"),
                lang("ada", "Ada", "adb", "ads"),
                lang("prolog", "Prolog", "pro"),
                lang("smalltalk", "Smalltalk", "st"),
                lang("ocaml", "OCaml", "ml", "mli"),
                lang("common-lisp", "Common Lisp", "lisp", "lsp"),
                lang("make", "Makefile", "mk", "mak"),
            ).flatMap { (language, extensions) -> extensions.map { it to language } }
                .toMap()

        private val fileNames: Map<String, ProjectFileLanguage> =
            mapOf(
                "dockerfile" to ProjectFileLanguage("docker", "Dockerfile"),
                "containerfile" to ProjectFileLanguage("docker", "Dockerfile"),
                "makefile" to ProjectFileLanguage("make", "Makefile"),
                "gnumakefile" to ProjectFileLanguage("make", "Makefile"),
                "gemfile" to ProjectFileLanguage("ruby", "Ruby"),
                "rakefile" to ProjectFileLanguage("ruby", "Ruby"),
                "podfile" to ProjectFileLanguage("ruby", "Ruby"),
                ".bashrc" to ProjectFileLanguage("shellscript", "Shell"),
                ".bash_profile" to ProjectFileLanguage("shellscript", "Shell"),
                ".zshrc" to ProjectFileLanguage("shellscript", "Shell"),
                ".profile" to ProjectFileLanguage("shellscript", "Shell"),
            )

        fun forPath(path: String): ProjectFileLanguage {
            val name = path.substringAfterLast('/').lowercase()
            fileNames[name]?.let { return it }
            if (name.startsWith("dockerfile.")) return ProjectFileLanguage("docker", "Dockerfile")
            val extension = name.substringAfterLast('.', "")
            if (extension.isEmpty() || extension == name.removePrefix(".")) {
                return PlainText
            }
            return languages[extension] ?: PlainText
        }

        private fun lang(
            grammar: String,
            displayName: String,
            vararg extensions: String,
        ): Pair<ProjectFileLanguage, List<String>> = ProjectFileLanguage(grammar, displayName) to extensions.toList()
    }
}
