package dev.opencode.mobile.markdown

import kotlin.random.Random

/** Typical code per language, repeated with varying identifiers, and pathological inputs. */
internal object BaselineCorpus {
    class WorstCase(
        val name: String,
        val language: String,
        val source: String,
    )

    fun code(
        language: String,
        size: Int,
    ): String {
        if (language == "json") return json(size)
        val template = TEMPLATES.getValue(language)
        val builder = StringBuilder(size + template.length)
        var index = 0
        while (builder.length < size) {
            builder.append(template.replace("{n}", index.toString()))
            index += 1
        }
        val cut = builder.lastIndexOf("\n", size)
        return builder.substring(0, if (cut > 0) cut + 1 else size)
    }

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

    fun worstCases(): List<WorstCase> {
        val random = Random(872)
        val minified = buildString { while (length < 1_000_000) append("function a${length}(b,c){return b+c*\"x\"}var d=[1,2,3],e={f:1,g:'h',i:/[a-z]+/g};") }
        val typicalTs = code("typescript", 120_000)
        return listOf(
            WorstCase("Minified JS, one 1 MB line", "javascript", minified),
            WorstCase("TypeScript, one 100K line", "typescript", typicalTs.replace('\n', ' ').take(100_000)),
            WorstCase("Unclosed block comment, 1 MB", "typescript", "/*\n" + code("typescript", 1_000_000)),
            WorstCase("Unclosed template literal, 1 MB", "typescript", "const t = `\n" + code("typescript", 1_000_000)),
            WorstCase("Unclosed string, one 100K line", "python", "s = \"" + "abc \\\" ".repeat(14_300)),
            WorstCase("20K open parentheses, one line", "typescript", "const x = " + "(".repeat(20_000) + "a"),
            WorstCase("10K nested generics, one line", "typescript", "let x: " + "A<".repeat(10_000) + "B" + ">".repeat(10_000) + ";"),
            WorstCase("10K arrow-function heads, one line", "typescript", "const f = " + "(a, b) => ".repeat(10_000) + "a;"),
            WorstCase("JSON nested 50K deep, one line", "json", "[".repeat(50_000) + "]".repeat(50_000)),
            WorstCase("JSON nested 2K deep, pretty", "json", nestedJson(2_000)),
            WorstCase("Binary bytes as text, 200K", "typescript", String(CharArray(200_000) { if (random.nextInt(80) == 0) '\n' else random.nextInt(0, 256).toChar() })),
            WorstCase("Surrogates, emoji, zero-width, 200K", "typescript", unicodeSoup(random)),
            WorstCase("CRLF TypeScript, 250K", "typescript", code("typescript", 250_000).replace("\n", "\r\n")),
            WorstCase("Diff with a 50K line, 1 MB", "diff", code("diff", 950_000) + "+" + "x".repeat(50_000) + "\n"),
            WorstCase("Markdown fence of 1 MB TypeScript", "markdown", "# Title\n\n```ts\n" + code("typescript", 1_000_000) + "```\n"),
        )
    }

    private fun nestedJson(depth: Int): String =
        buildString {
            repeat(depth) { level -> append("  ".repeat(level)).append("{\"k$level\": \"v\", \"child\":\n") }
            append("  ".repeat(depth)).append("null\n")
            repeat(depth) { level -> append("  ".repeat(depth - level - 1)).append("}\n") }
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
