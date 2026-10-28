package dev.opencode.mobile.highlight.bench

import kotlin.math.exp
import kotlin.random.Random

/**
 * Text generators behind [HighlightBenchCatalog], in code so every platform runs identical
 * inputs: typical code per language repeated with varying identifiers, chat-sized fences, and
 * pathological inputs.
 */
object HighlightBenchCorpus {
    /** The languages the quick tier runs: the Minimum languages except javascript, unchanged since the first baseline. */
    val quickLanguages: List<String> = listOf("typescript", "kotlin", "swift", "python", "json", "diff")

    /** The Basic milestone's languages (#872), by their canonical language ids. */
    val basicLanguages: List<String> =
        listOf(
            "shellscript", "yaml", "markdown", "html", "css", "rust", "go", "java", "c", "cpp", "csharp",
            "ruby", "php", "sql", "toml", "docker", "xml",
        )

    /** The Full milestone's 38 remaining languages (#872), by their canonical language ids. */
    val fullLanguages: List<String> =
        listOf(
            "abap", "ada", "apex", "asm", "awk", "clojure", "cobol", "coffee", "common-lisp", "crystal", "dart",
            "elixir", "erlang", "fortran-fixed-form", "fortran-free-form", "fsharp", "graphql", "groovy", "haskell",
            "julia", "lua", "make", "matlab", "nim", "objective-c", "ocaml", "pascal", "perl", "powershell", "prolog",
            "r", "sas", "scala", "smalltalk", "svelte", "vb", "vue", "zig",
        )

    /** Every language with typical code and chat fences: [quickLanguages], javascript, [basicLanguages], then [fullLanguages]. */
    val languages: List<String> = quickLanguages + "javascript" + basicLanguages + fullLanguages

    const val ONE_MB: Int = 1_000_000

    /** Typical [language] code cut at a line end, at most [size] characters. */
    fun code(
        language: String,
        size: Int,
    ): String {
        require(language in languages) { "No typical code for $language" }
        if (language == "json") return json(size)
        val template =
            TEMPLATES[language]
                ?: HighlightBenchCorpusTemplates.basic[language]
                ?: HighlightBenchCorpusFullTemplates.full.getValue(language)
        val builder = StringBuilder(size + template.length)
        var index = 0
        while (builder.length < size) {
            builder.append(template.replace("{n}", index.toString()))
            index += 1
        }
        val cut = builder.lastIndexOf("\n", size - 1)
        return builder.substring(0, if (cut > 0) cut + 1 else size)
    }

    /**
     * [count] chat fences cut from typical code at line starts. Sizes come from a fixed
     * log-uniform draw (median ~900 characters, clipped to 80..8,000), the same for every language.
     */
    fun chatFences(
        language: String,
        count: Int,
    ): List<String> {
        val corpus = code(language, 64_000)
        val random = Random(872)
        return List(count) {
            val size = exp(6.8 + random.nextDouble(-1.0, 1.0) * 1.3).toInt().coerceIn(80, 8_000)
            val start = corpus.lastIndexOf('\n', random.nextInt(0, corpus.length - size)) + 1
            corpus.substring(start, minOf(corpus.length, start + size))
        }
    }

    /**
     * Pathological inputs, each generated on demand because several are megabytes; the Basic
     * languages add their own after the first 15, and the Full languages theirs after those.
     */
    internal fun worstCases(): List<HighlightBenchDataSet> =
        listOf(
            worst("minified-js-1mb", "Minified JS, one 1 MB line", "javascript", ONE_MB) {
                buildString { while (length < ONE_MB) append("function a${length}(b,c){return b+c*\"x\"}var d=[1,2,3],e={f:1,g:'h',i:/[a-z]+/g};") }
            },
            worst("typescript-line-100k", "TypeScript, one 100K line", "typescript", 100_000) {
                code("typescript", 120_000).replace('\n', ' ').take(100_000)
            },
            worst("unclosed-comment-1mb", "Unclosed block comment, 1 MB", "typescript", ONE_MB) { "/*\n" + code("typescript", ONE_MB) },
            worst("unclosed-template-1mb", "Unclosed template literal, 1 MB", "typescript", ONE_MB) { "const t = `\n" + code("typescript", ONE_MB) },
            worst("unclosed-string-100k", "Unclosed string, one 100K line", "python", 100_000) { "s = \"" + "abc \\\" ".repeat(14_300) },
            worst("open-parens-20k", "20K open parentheses, one line", "typescript", 20_000) { "const x = " + "(".repeat(20_000) + "a" },
            worst("nested-generics-10k", "10K nested generics, one line", "typescript", 40_000) {
                "let x: " + "A<".repeat(10_000) + "B" + ">".repeat(10_000) + ";"
            },
            worst("arrow-heads-10k", "10K arrow-function heads, one line", "typescript", 100_000) { "const f = " + "(a, b) => ".repeat(10_000) + "a;" },
            worst("json-deep-50k", "JSON nested 50K deep, one line", "json", 100_000) { "[".repeat(50_000) + "]".repeat(50_000) },
            worst("json-deep-2k-pretty", "JSON nested 2K deep, pretty", "json", 8_000_000) { nestedJson(2_000) },
            worst("binary-200k", "Binary bytes as text, 200K", "typescript", 200_000) {
                val random = Random(872)
                CharArray(200_000) { if (random.nextInt(80) == 0) '\n' else random.nextInt(0, 256).toChar() }.concatToString()
            },
            worst("unicode-soup-200k", "Surrogates, emoji, zero-width, 200K", "typescript", 200_000) { unicodeSoup(Random(873)) },
            worst("crlf-typescript-250k", "CRLF TypeScript, 250K", "typescript", 250_000) { code("typescript", 250_000).replace("\n", "\r\n") },
            worst("diff-long-line-1mb", "Diff with a 50K line, 1 MB", "diff", ONE_MB) { code("diff", 950_000) + "+" + "x".repeat(50_000) + "\n" },
            worst("markdown-fence-1mb", "Markdown fence of 1 MB TypeScript", "markdown", ONE_MB) { "# Title\n\n```ts\n" + code("typescript", ONE_MB) + "```\n" },
            worst("markdown-unclosed-fence-1mb", "Markdown fence never closed, 1 MB", "markdown", ONE_MB) { "# Title\n\n```kotlin\n" + code("kotlin", ONE_MB) },
            worst("shell-unclosed-heredoc-1mb", "Shell heredoc never closed, 1 MB", "shellscript", ONE_MB) { "cat <<NEVER_ENDS\n" + code("shellscript", ONE_MB) },
            worst("yaml-deep-1k", "YAML nested 1K deep, block style", "yaml", 1_100_000) { nestedYaml(1_000) },
            worst("html-unclosed-script-1mb", "HTML <script> never closed, 1 MB", "html", ONE_MB) {
                "<!DOCTYPE html>\n<html>\n<body>\n<script>\n" + code("javascript", ONE_MB)
            },
            worst("css-minified-1mb", "Minified CSS, one 1 MB line", "css", ONE_MB) {
                buildString {
                    while (length < ONE_MB) {
                        append(".a$length>.b:hover{color:#3b82f6;margin:0 auto;background:url(i.png)}")
                        append("@media(max-width:600px){.c$length{display:none!important}}")
                    }
                }
            },
            worst("rust-unclosed-raw-string-1mb", "Rust raw string never closed, 1 MB", "rust", ONE_MB) { "let s = r##\"\n" + code("rust", ONE_MB) },
            worst("cpp-unclosed-raw-string-1mb", "C++ raw string never closed, 1 MB", "cpp", ONE_MB) { "auto s = R\"never(\n" + code("cpp", ONE_MB) },
            worst("csharp-unclosed-raw-string-1mb", "C# raw string never closed, 1 MB", "csharp", ONE_MB) { "var s = \"\"\"\"\n" + code("csharp", ONE_MB) },
            worst("ruby-unclosed-heredoc-1mb", "Ruby heredoc never closed, 1 MB", "ruby", ONE_MB) { "text = <<~NEVER_ENDS\n" + code("ruby", ONE_MB) },
            worst("php-unclosed-heredoc-1mb", "PHP heredoc never closed, 1 MB", "php", ONE_MB) { "<?php\n${'$'}s = <<<NEVER_ENDS\n" + code("php", ONE_MB) },
            worst("sql-in-list-1mb", "SQL IN list, one 1 MB line", "sql", ONE_MB) {
                buildString {
                    append("SELECT * FROM sessions WHERE id IN (")
                    var index = 0
                    while (length < ONE_MB) append(if (index++ == 0) "" else ", ").append("'ses_").append(index).append('\'')
                    append(");\n")
                }
            },
            worst("c-byte-array-1mb", "C byte array initializer, 1 MB", "c", ONE_MB) { byteArrayC(Random(874)) },
            worst("xml-deep-50k", "XML nested 50K deep, one line", "xml", 350_000) { "<n>".repeat(50_000) + "</n>".repeat(50_000) },
            worst("lua-unclosed-long-string-1mb", "Lua long string never closed, 1 MB", "lua", ONE_MB) { "local s = [==[\n" + code("lua", ONE_MB) },
            worst("haskell-unclosed-nested-comment-1mb", "Haskell nested comment never closed, 1 MB", "haskell", ONE_MB) {
                "{- outer {- inner -}\n" + code("haskell", ONE_MB)
            },
            worst("ocaml-unclosed-nested-comment-1mb", "OCaml nested comment never closed, 1 MB", "ocaml", ONE_MB) {
                "(* outer (* inner *)\n" + code("ocaml", ONE_MB)
            },
            worst("perl-unclosed-heredoc-1mb", "Perl heredoc never closed, 1 MB", "perl", ONE_MB) { "print <<\"NEVER_ENDS\";\n" + code("perl", ONE_MB) },
            worst("vue-unclosed-script-1mb", "Vue <script> never closed, 1 MB", "vue", ONE_MB) {
                "<template>\n  <p>{{ title }}</p>\n</template>\n\n<script setup lang=\"ts\">\n" + code("typescript", ONE_MB)
            },
            worst("make-continued-line-1mb", "Make variable continued over 1 MB", "make", ONE_MB) { makeContinuedLine() },
            worst("clojure-deep-50k", "Clojure forms nested 50K deep, one line", "clojure", 100_000) {
                "(".repeat(50_000) + "x" + ")".repeat(50_000)
            },
        )

    private fun worst(
        id: String,
        displayName: String,
        language: String,
        approximateLength: Int,
        generate: () -> String,
    ) = HighlightBenchDataSet(
        id = "worst/$id",
        displayName = displayName,
        language = language,
        sizeClass = HighlightBenchSizeClass.of(approximateLength),
        kind = HighlightBenchDataKind.WorstCase,
    ) { listOf(generate()) }

    private fun json(size: Int): String {
        val builder = StringBuilder(size + JSON_ITEM.length).append("[\n")
        var index = 0
        while (builder.length < size - JSON_ITEM.length) {
            if (index > 0) builder.append(",\n")
            builder.append(JSON_ITEM.replace("{n}", index.toString()))
            index += 1
        }
        return builder.append("\n]\n").toString()
    }

    private fun nestedJson(depth: Int): String =
        buildString {
            repeat(depth) { level -> append("  ".repeat(level)).append("{\"k$level\": \"v\", \"child\":\n") }
            append("  ".repeat(depth)).append("null\n")
            repeat(depth) { level -> append("  ".repeat(depth - level - 1)).append("}\n") }
        }

    private fun nestedYaml(depth: Int): String =
        buildString {
            append("---\n")
            repeat(depth) { level -> append("  ".repeat(level)).append("k").append(level).append(": # level ").append(level).append('\n') }
            append("  ".repeat(depth)).append("leaf: |\n").append("  ".repeat(depth + 1)).append("block scalar at the bottom\n")
        }

    /** A generated source list: one logical line of about 1 MB, continued with backslashes. */
    private fun makeContinuedLine(): String =
        buildString {
            append("SOURCES := \\\n")
            var index = 0
            while (length < ONE_MB - 40) append("  src/session/module_").append(index++).append(".c \\\n")
            append("  src/main.c\n\nall: ${'$'}(SOURCES:.c=.o)\n")
        }

    /** `xxd -i` output: a 1 MB `unsigned char` array, twelve bytes per line. */
    private fun byteArrayC(random: Random): String =
        buildString {
            append("/* Generated by xxd -i */\nunsigned char image_png[] = {\n")
            var count = 0
            while (length < ONE_MB - 40) {
                append(if (count % 12 == 0) "  " else " ")
                append("0x").append(random.nextInt(256).toString(16).padStart(2, '0')).append(',')
                count += 1
                if (count % 12 == 0) append('\n')
            }
            append("\n};\nunsigned int image_png_len = ").append(count).append(";\n")
        }

    private fun unicodeSoup(random: Random): String {
        val pieces =
            listOf(
                "\uD83D", "\uDE00", "😀", "👩‍💻", "\u200B", "\u200D", "\uFEFF", "\u202E", "é", "ß",
                "const s = \"", "\";\n", "// ", "\n", "let x = 1;", "`", "${'$'}{", "}",
            )
        return buildString { while (length < 200_000) append(pieces[random.nextInt(pieces.size)]) }
    }

    private const val JSON_ITEM = """  {
    "id": "session-{n}",
    "title": "Refactor the \"session\" store #{n}",
    "active": true,
    "archived": false,
    "parent": null,
    "tokens": {"input": 12{n}, "output": 3{n}.5, "cache": [1, 2, 3e5, -4.25]},
    "tags": ["chat", "files", "v{n}"],
    "path": "C:\\Users\\dev\\project-{n}\\src\\index.ts",
    "unicode": "caf\u00e9 \ud83d\ude00"
  }"""

    private val TEMPLATES =
        mapOf(
            "typescript" to
                """
                |/**
                | * Session store {n}: keeps the latest revision per key.
                | */
                |import { EventEmitter } from "node:events";
                |import type { Session, Message } from "./types";
                |
                |export interface StoreOptions{n}<T extends Session = Session> {
                |  readonly capacity: number;
                |  onEvict?: (session: T) => void;
                |  filter: RegExp | null;
                |}
                |
                |enum Status{n} { Idle = "idle", Busy = "busy", Error = 3 }
                |
                |export class SessionStore{n}<T extends Session> extends EventEmitter {
                |  private readonly items = new Map<string, T>();
                |  #revision = 0;
                |
                |  constructor(private options: StoreOptions{n}<T> = { capacity: 64, filter: /^ses_[a-z0-9]+${'$'}/i }) {
                |    super();
                |  }
                |
                |  async load(id: string, signal?: AbortSignal): Promise<T | undefined> {
                |    const response = await fetch(`/session/${'$'}{encodeURIComponent(id)}?rev=${'$'}{this.#revision}`, { signal });
                |    if (!response.ok) throw new Error(`load failed: ${'$'}{response.status}`);
                |    const body = (await response.json()) as { session: T; messages: Message[] };
                |    this.items.set(id, body.session);
                |    return this.items.get(id) ?? undefined;
                |  }
                |
                |  get size(): number {
                |    return [...this.items.values()].filter((s) => s.status !== Status{n}.Error).length * 2 + 0x1f;
                |  }
                |}
                |
                |export const createStore{n} = <T extends Session>(capacity = 32): SessionStore{n}<T> =>
                |  new SessionStore{n}<T>({ capacity, filter: null, onEvict: (s) => console.log(s?.id, 1_000n) });
                |
                """.trimMargin(),
            "kotlin" to
                """
                |/** Session store {n}: keeps the latest revision per key. */
                |package dev.opencode.bench.store{n}
                |
                |import kotlinx.coroutines.flow.MutableStateFlow
                |import kotlinx.coroutines.flow.StateFlow
                |
                |sealed interface Status{n} {
                |    data object Idle : Status{n}
                |    data class Busy(val since: Long) : Status{n}
                |    data class Failed(val message: String, val code: Int = 0x1F) : Status{n}
                |}
                |
                |@Suppress("MagicNumber")
                |class SessionStore{n}<T : Any>(
                |    private val capacity: Int = 64,
                |    private val onEvict: (T) -> Unit = {},
                |) {
                |    private val items = linkedMapOf<String, T>()
                |    private val _status = MutableStateFlow<Status{n}>(Status{n}.Idle)
                |    val status: StateFlow<Status{n}> get() = _status
                |
                |    suspend fun load(id: String, fetch: suspend (String) -> T?): T? {
                |        _status.value = Status{n}.Busy(System.currentTimeMillis())
                |        val loaded = fetch("/session/${'$'}id?limit=${'$'}{capacity * 2}") ?: return null
                |        items[id] = loaded
                |        if (items.size > capacity) items.remove(items.keys.first())?.let(onEvict)
                |        // Regex check keeps ids well-formed.
                |        require(Regex("^ses_[a-z0-9]+${'$'}").matches(id)) { "bad id ${'$'}id" }
                |        return when (val current = _status.value) {
                |            is Status{n}.Busy -> loaded.also { _status.value = Status{n}.Idle }
                |            is Status{n}.Failed -> null
                |            Status{n}.Idle -> loaded
                |        }
                |    }
                |
                |    val size: Int get() = items.values.count { it.hashCode() % 2 == 0 } + 1_000
                |}
                |
                """.trimMargin(),
            "swift" to
                """
                |/// Session store {n}: keeps the latest revision per key.
                |import Foundation
                |
                |enum Status{n}: Equatable {
                |    case idle
                |    case busy(since: Date)
                |    case failed(message: String, code: Int = 0x1F)
                |}
                |
                |protocol SessionLoading{n} {
                |    func load(id: String) async throws -> Session?
                |}
                |
                |@MainActor
                |final class SessionStore{n}<T: Identifiable & Sendable>: ObservableObject where T.ID == String {
                |    @Published private(set) var status: Status{n} = .idle
                |    private var items: [String: T] = [:]
                |    private let capacity: Int
                |
                |    init(capacity: Int = 64) {
                |        self.capacity = capacity
                |    }
                |
                |    func load(_ id: String, using loader: (String) async throws -> T?) async rethrows -> T? {
                |        status = .busy(since: Date())
                |        guard let loaded = try await loader("/session/\(id)?limit=\(capacity * 2)") else { return nil }
                |        items[loaded.id] = loaded
                |        if items.count > capacity, let first = items.keys.first { items.removeValue(forKey: first) }
                |        // Multi-line strings and raw strings appear in real code.
                |        let note = #"id "\(id)" loaded"#
                |        switch status {
                |        case .busy: status = .idle
                |        case let .failed(message, code) where code > 1_000: print(message, note)
                |        default: break
                |        }
                |        return items[id].map { ${'$'}0 } ?? loaded
                |    }
                |}
                |
                """.trimMargin(),
            "python" to
                """
                |""${'"'}Session store {n}: keeps the latest revision per key.""${'"'}
                |from __future__ import annotations
                |
                |import asyncio
                |import re
                |from dataclasses import dataclass, field
                |from typing import Awaitable, Callable, Generic, TypeVar
                |
                |T = TypeVar("T")
                |SESSION_ID_{n} = re.compile(r"^ses_[a-z0-9]+${'$'}", re.IGNORECASE)
                |
                |
                |@dataclass
                |class SessionStore{n}(Generic[T]):
                |    capacity: int = 64
                |    items: dict[str, T] = field(default_factory=dict)
                |
                |    async def load(self, session_id: str, fetch: Callable[[str], Awaitable[T | None]]) -> T | None:
                |        if not SESSION_ID_{n}.match(session_id):
                |            raise ValueError(f"bad id {session_id!r} at {self.capacity * 2:>4}")
                |        loaded = await fetch(f"/session/{session_id}?limit={self.capacity}")
                |        if loaded is None:
                |            return None
                |        self.items[session_id] = loaded
                |        while len(self.items) > self.capacity:  # evict oldest
                |            self.items.pop(next(iter(self.items)))
                |        return loaded
                |
                |    @property
                |    def size(self) -> int:
                |        return sum(1 for item in self.items.values() if hash(item) % 2 == 0) + 0x1F
                |
                |
                |async def main_{n}() -> None:
                |    store = SessionStore{n}[str]()
                |    await asyncio.gather(*(store.load(f"ses_{i}", asyncio.sleep) for i in range(3)))
                |
                """.trimMargin(),
            "diff" to
                """
                |diff --git a/src/store{n}.ts b/src/store{n}.ts
                |index 3b18e51..a9c2f04 100644
                |--- a/src/store{n}.ts
                |+++ b/src/store{n}.ts
                |@@ -12,14 +12,17 @@ export class SessionStore{n}<T extends Session> {
                |   private readonly items = new Map<string, T>();
                |-  private revision = 0;
                |+  #revision = 0;
                |+  #pending = new Set<string>();
                |
                |   async load(id: string): Promise<T | undefined> {
                |-    const response = await fetch(`/session/${'$'}{id}`);
                |+    const response = await fetch(`/session/${'$'}{encodeURIComponent(id)}?rev=${'$'}{this.#revision}`);
                |     if (!response.ok) throw new Error(`load failed: ${'$'}{response.status}`);
                |-    return (await response.json()).session;
                |+    const body = (await response.json()) as { session: T };
                |+    this.items.set(id, body.session);
                |+    return body.session;
                |   }
                | }
                |\ No newline at end of file
                |
                """.trimMargin(),
        )
}
