package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Swift fixtures: the contract for the C-like lexer in Swift. */
class SwiftGoldenTest {
    @Test
    fun nestedBlockComments() {
        assertGolden(
            "swift",
            """
            /* outer /* inner */ still outer */ let a = 1
            /* open /* nested
               */ still comment
            */ let b = 2
            /// Doc comment
            """.trimIndent(),
            """
            «Comment|/* outer /* inner */ still outer */» «Keyword|let» a «Operator|=» «Number|1»
            «Comment|/* open /* nested»
            «Comment|   */ still comment»
            «Comment|*/» «Keyword|let» b «Operator|=» «Number|2»
            «Comment|/// Doc comment»
            """.trimIndent(),
        )
    }

    @Test
    fun interpolationMultilineAndRawStrings() {
        assertGolden(
            "swift",
            """
            let s = "Hi \(name), \(user.age + (1 * 2)) \("nested \(x)") \t\"q\" \u{1F600}"
            let raw = #"raw "quoted" \(notInterpolated) \#(id) \#n"#
            let two = ##"has "# inside \##(x)"##
            let open = "never closed
            let multi = ""${'"'}
                Hello \(name)
                "quotes" and \n
                ""${'"'}
            let rawMulti = #""${'"'}
                raw \(no) \#(yes)
                ""${'"'}#
            let next = 1
            """.trimIndent(),
            """
            «Keyword|let» s «Operator|=» «String|"Hi »«Punctuation|\(»name«Punctuation|)»«String|, »«Punctuation|\(»user«Punctuation|.»«Property|age» «Operator|+» «Punctuation|(»«Number|1» «Operator|*» «Number|2»«Punctuation|))»«String| »«Punctuation|\(»«String|"nested »«Punctuation|\(»x«Punctuation|)»«String|"»«Punctuation|)»«String| »«Constant|\t\"»«String|q»«Constant|\"»«String| »«Constant|\u{1F600}»«String|"»
            «Keyword|let» raw «Operator|=» «String|#"raw "quoted" \(notInterpolated) »«Punctuation|\#(»id«Punctuation|)»«String| »«Constant|\#n»«String|"#»
            «Keyword|let» two «Operator|=» «String|##"has "# inside »«Punctuation|\##(»x«Punctuation|)»«String|"##»
            «Keyword|let» open «Operator|=» «String|"never closed»
            «Keyword|let» multi «Operator|=» «String|""${'"'}»
            «String|    Hello »«Punctuation|\(»name«Punctuation|)»
            «String|    "quotes" and »«Constant|\n»
            «String|    ""${'"'}»
            «Keyword|let» rawMulti «Operator|=» «String|#""${'"'}»
            «String|    raw \(no) »«Punctuation|\#(»yes«Punctuation|)»
            «String|    ""${'"'}#»
            «Keyword|let» next «Operator|=» «Number|1»
            """.trimIndent(),
        )
    }

    @Test
    fun declarationsAttributesAndDirectives() {
        assertGolden(
            "swift",
            """
            import SwiftUI

            @MainActor
            final class Store<T: Identifiable & Sendable>: ObservableObject where T.ID == String {
                @Published private(set) var status: Status = .idle
                private var items: [String: T] = [:]
                weak var delegate: StoreDelegate?
                lazy var cache = Cache()

                init(capacity: Int = 64) { self.capacity = capacity }

                func load<R>(_ id: String, using loader: (String) async throws -> R?) async rethrows -> R? {
                    guard let loaded = try await loader(id) else { return nil }
                    switch status {
                    case .busy: status = .idle
                    case let .failed(message, code) where code > 1_000: print(message, 0x1F, 0b11, 0o7, 1.5e3)
                    default: break
                    }
                    return items[id].map { ${'$'}0.value } ?? loaded
                }

                var body: some View { Text(`default`) }
            }
            #if DEBUG
            let x = #selector(Store.load)
            #endif
            actor Counter { nonisolated func id() -> Int { 1 } }
            extension Store: CustomStringConvertible {}
            protocol P { associatedtype Item }
            """.trimIndent(),
            """
            «Keyword|import» «Type|SwiftUI»

            «Attribute|@MainActor»
            «Keyword|final» «Keyword|class» «Type|Store»«Operator|<»«Type|T»«Punctuation|:» «Type|Identifiable» «Operator|&» «Type|Sendable»«Operator|>:» «Type|ObservableObject» «Keyword|where» «Type|T»«Punctuation|.»«Type|ID» «Operator|==» «Type|String» «Punctuation|{»
                «Attribute|@Published» «Keyword|private»«Punctuation|(»set«Punctuation|)» «Keyword|var» status«Punctuation|:» «Type|Status» «Operator|=» «Punctuation|.»«Property|idle»
                «Keyword|private» «Keyword|var» items«Punctuation|:» «Punctuation|[»«Type|String»«Punctuation|:» «Type|T»«Punctuation|]» «Operator|=» «Punctuation|[:]»
                «Keyword|weak» «Keyword|var» delegate«Punctuation|:» «Type|StoreDelegate»«Operator|?»
                «Keyword|lazy» «Keyword|var» cache «Operator|=» «Function|Cache»«Punctuation|()»

                «Keyword|init»«Punctuation|(»capacity«Punctuation|:» «Type|Int» «Operator|=» «Number|64»«Punctuation|)» «Punctuation|{» «Keyword|self»«Punctuation|.»«Property|capacity» «Operator|=» capacity «Punctuation|}»

                «Keyword|func» «Function|load»«Operator|<»«Type|R»«Operator|>»«Punctuation|(»_ id«Punctuation|:» «Type|String»«Punctuation|,» using loader«Punctuation|:» «Punctuation|(»«Type|String»«Punctuation|)» «Keyword|async» «Keyword|throws» «Operator|->» «Type|R»«Operator|?»«Punctuation|)» «Keyword|async» «Keyword|rethrows» «Operator|->» «Type|R»«Operator|?» «Punctuation|{»
                    «Keyword|guard» «Keyword|let» loaded «Operator|=» «Keyword|try» «Keyword|await» «Function|loader»«Punctuation|(»id«Punctuation|)» «Keyword|else» «Punctuation|{» «Keyword|return» «Constant|nil» «Punctuation|}»
                    «Keyword|switch» status «Punctuation|{»
                    «Keyword|case» «Punctuation|.»«Property|busy»«Punctuation|:» status «Operator|=» «Punctuation|.»«Property|idle»
                    «Keyword|case» «Keyword|let» «Punctuation|.»«Function|failed»«Punctuation|(»message«Punctuation|,» code«Punctuation|)» «Keyword|where» code «Operator|>» «Number|1_000»«Punctuation|:» «Function|print»«Punctuation|(»message«Punctuation|,» «Number|0x1F»«Punctuation|,» «Number|0b11»«Punctuation|,» «Number|0o7»«Punctuation|,» «Number|1.5e3»«Punctuation|)»
                    «Keyword|default»«Punctuation|:» «Keyword|break»
                    «Punctuation|}»
                    «Keyword|return» items«Punctuation|[»id«Punctuation|].»«Property|map» «Punctuation|{» ${'$'}0«Punctuation|.»«Property|value» «Punctuation|}» «Operator|??» loaded
                «Punctuation|}»

                «Keyword|var» body«Punctuation|:» «Keyword|some» «Type|View» «Punctuation|{» «Function|Text»«Punctuation|(»`default`«Punctuation|)» «Punctuation|}»
            «Punctuation|}»
            «Keyword|#if» «Type|DEBUG»
            «Keyword|let» x «Operator|=» «Keyword|#selector»«Punctuation|(»«Type|Store»«Punctuation|.»«Property|load»«Punctuation|)»
            «Keyword|#endif»
            «Keyword|actor» «Type|Counter» «Punctuation|{» «Keyword|nonisolated» «Keyword|func» «Function|id»«Punctuation|()» «Operator|->» «Type|Int» «Punctuation|{» «Number|1» «Punctuation|}» «Punctuation|}»
            «Keyword|extension» «Type|Store»«Punctuation|:» «Type|CustomStringConvertible» «Punctuation|{}»
            «Keyword|protocol» «Type|P» «Punctuation|{» «Keyword|associatedtype» «Type|Item» «Punctuation|}»
            """.trimIndent(),
        )
    }
}
