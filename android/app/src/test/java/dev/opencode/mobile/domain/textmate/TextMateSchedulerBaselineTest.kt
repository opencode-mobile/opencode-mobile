package dev.opencode.mobile.domain.textmate

import dev.opencode.mobile.domain.filesystem.TextMateProjectFileHighlighter
import dev.opencode.mobile.markdown.CodeBlockKey
import dev.opencode.mobile.markdown.HighlightIntent
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightRevision
import dev.opencode.mobile.markdown.OpenCodeBundledJoniMarkdownHighlighter
import dev.opencode.mobile.markdown.OpenCodeSemanticFallbackHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import dev.opencode.mobile.markdown.OpenCodeTextMateResourceReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Baseline for #872: how the File Viewer and Session Chat share the Android TextMate engine
 * (scheduler adapter plus File Viewer highlighter). Engine-only numbers live in markdown-core's
 * `TextMateBaselineBenchmarkTest`.
 *
 * Opt-in, full tier only, run from `android/`:
 * `HIGHLIGHT_BASELINE=full ./gradlew :app:testDebugUnitTest --tests '*TextMateSchedulerBaselineTest*' --rerun`.
 * Report: `android/app/build/reports/highlight-baseline/scheduler.md`.
 */
class TextMateSchedulerBaselineTest {
    private val keys = AtomicLong()
    private val output = File("build/reports/highlight-baseline/scheduler.md")
    private val report = StringBuilder()

    @Test
    fun baseline() {
        val tier = System.getenv("HIGHLIGHT_BASELINE")?.lowercase()
        assumeTrue("Set HIGHLIGHT_BASELINE=full to run", tier == "full" || tier == "1")
        check(File(GRAMMAR_ROOT, "textmate-grammars/manifest.json").isFile) { "No grammars at ${GRAMMAR_ROOT.absolutePath}" }
        line("# TextMate scheduler baseline (JVM host, Joni, TypeScript)")
        line("")
        line("- Host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} cores")
        line("- A 2,000-character fence and files of 250K characters and 1 MB, all typical TypeScript.")

        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "textmate-owner").apply { isDaemon = true } }
        val adapter =
            AndroidTextMateHighlightingEngine(
                delegate = OpenCodeTextMateHighlightingEngine(highlighter()),
                ownerDispatcher = executor.asCoroutineDispatcher(),
            )
        val fileHighlighter = TextMateProjectFileHighlighter(adapter, Dispatchers.Default) { true }
        val fence = typescript(2_000)
        val file250k = typescript(250_000)
        val file1m = typescript(1_000_000)
        runBlocking {
            adapter.highlight(request(typescript(8_000)))

            section("File Viewer path (TextMateProjectFileHighlighter on the shared engine)")
            table("File", "Time to spans ms", "Engine outcome", "Spans", "Semantic fallback alone ms")
            for ((label, source) in listOf("250K characters" to file250k, "1 MB" to file1m)) {
                val (outcomeNanos, outcome) = measure { adapter.highlight(request(source)) }
                val (nanos, spans) = measure { fileHighlighter.highlight("bench.ts", "typescript", source) }
                val (fallbackNanos, _) = measure { OpenCodeSemanticFallbackHighlighter.highlight("typescript", source) }
                row(label, ms(nanos), "${outcome.outcome.name} (${ms(outcomeNanos)} ms)", "%,d".format(spans.size), ms(fallbackNanos))
            }

            section("Chat fence sent 20 ms after the File Viewer starts a file")
            table("File", "Fence intent", "Fence latency ms", "Fence outcome", "File ms", "File outcome")
            for ((label, source) in listOf("250K characters" to file250k, "1 MB" to file1m)) {
                for (intent in listOf(HighlightIntent.InteractiveFinal, HighlightIntent.VisibleHistory)) {
                    val file = async(Dispatchers.Default) { measure { adapter.highlight(request(source)) } }
                    delay(20)
                    val chat = async(Dispatchers.Default) { measure { adapter.highlight(request(fence, intent)) } }
                    val (fileNanos, fileResult) = file.await()
                    val (chatNanos, chatResult) = chat.await()
                    row(label, intent.name, ms(chatNanos), chatResult.outcome.name, ms(fileNanos), fileResult.outcome.name)
                }
            }

            section("Queued chat fences when a 1 MB file arrives (250K file running, 3 fences queued)")
            table("Request", "Outcome", "Completed after ms")
            val running = async(Dispatchers.Default) { "Running 250K file" to measure { adapter.highlight(request(file250k)) } }
            delay(20)
            val queued =
                (1..3).map { index ->
                    async(Dispatchers.Default) {
                        "Fence $index" to measure { adapter.highlight(request(fence, HighlightIntent.VisibleHistory)) }
                    }
                }
            delay(20)
            val big = async(Dispatchers.Default) { "1 MB file" to measure { adapter.highlight(request(file1m)) } }
            (listOf(running.await()) + queued.awaitAll() + big.await()).forEach { (label, measured) ->
                row(label, measured.second.outcome.name, ms(measured.first))
            }
        }
        executor.shutdownNow()
    }

    private fun request(
        source: String,
        intent: HighlightIntent = HighlightIntent.InteractiveFinal,
    ) = HighlightRequest(
        key = CodeBlockKey("baseline:${keys.incrementAndGet()}"),
        revision = HighlightRevision(1),
        language = "typescript",
        source = source,
        intent = intent,
        policyEpoch = 0,
    )

    private fun section(title: String) {
        line("")
        line("## $title")
        line("")
    }

    private fun table(vararg headers: String) {
        line(headers.joinToString(" | ", "| ", " |"))
        line(headers.joinToString(" | ", "| ", " |") { "---" })
    }

    private fun row(vararg cells: String) = line(cells.joinToString(" | ", "| ", " |"))

    /** Rewrites the report after every line, so a run in progress can be read. */
    private fun line(text: String) {
        report.appendLine(text)
        println("HIGHLIGHT_BASELINE $text")
        output.parentFile.mkdirs()
        output.writeText(report.toString())
    }

    private companion object {
        /** The app's unit-test classpath has no grammars, so read them from markdown-core's sources. */
        val GRAMMAR_ROOT = File("../../shared/markdown-core/src/commonMain/resources")

        fun highlighter() =
            OpenCodeBundledJoniMarkdownHighlighter.create(
                OpenCodeTextMateResourceReader { path -> File(GRAMMAR_ROOT, path).takeIf { it.isFile }?.readText() },
            )

        suspend inline fun <T> measure(crossinline block: suspend () -> T): Pair<Long, T> {
            val startedAt = System.nanoTime()
            val value = block()
            return (System.nanoTime() - startedAt) to value
        }

        fun ms(nanos: Long): String = "%.1f".format(nanos / 1_000_000.0)

        /** The same TypeScript sample as markdown-core's `BaselineCorpus`, repeated to [size]. */
        fun typescript(size: Int): String {
            val builder = StringBuilder(size + TYPESCRIPT.length)
            var index = 0
            while (builder.length < size) {
                builder.append(TYPESCRIPT.replace("{n}", index.toString()))
                index += 1
            }
            return builder.substring(0, builder.lastIndexOf("\n", size) + 1)
        }

        val TYPESCRIPT =
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
            """.trimMargin()
    }
}
