package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Kotlin fixtures: the contract for the C-like lexer in Kotlin. */
class KotlinGoldenTest {
    @Test
    fun nestedBlockComments() {
        assertGolden(
            "kotlin",
            """
            /* outer /* inner */ still outer */ val a = 1
            /* open /* nested
               */ still comment
            */ val b = 2
            // line comment
            /** KDoc */
            """.trimIndent(),
            """
            «Comment|/* outer /* inner */ still outer */» «Keyword|val» a «Operator|=» «Number|1»
            «Comment|/* open /* nested»
            «Comment|   */ still comment»
            «Comment|*/» «Keyword|val» b «Operator|=» «Number|2»
            «Comment|// line comment»
            «Comment|/** KDoc */»
            """.trimIndent(),
        )
    }

    @Test
    fun stringTemplatesRawStringsAndCharacters() {
        assertGolden(
            "kotlin",
            """
            val s = "Hi ${'$'}name, ${'$'}{user.age + 1} \${'$'} \"q\" \u00e9 ${'$'}{"nested ${'$'}{x}"}"
            val c = 'c'; val e = '\n'; val u = 'A'
            val raw = ""${'"'}
                raw ${'$'}id ${'$'}{items.size} \n "quoted" ""
                ""${'"'}.trimIndent()
            val quotes = ""${'"'}a""${'"'}"
            val open = "never closed
            val next = 1
            """.trimIndent(),
            """
            «Keyword|val» s «Operator|=» «String|"Hi »«Variable|${'$'}name»«String|, »«Punctuation|${'$'}{»user«Punctuation|.»«Property|age» «Operator|+» «Number|1»«Punctuation|}»«String| »«Constant|\${'$'}»«String| »«Constant|\"»«String|q»«Constant|\"»«String| »«Constant|\u00e9»«String| »«Punctuation|${'$'}{»«String|"nested »«Punctuation|${'$'}{»x«Punctuation|}»«String|"»«Punctuation|}»«String|"»
            «Keyword|val» c «Operator|=» «String|'c'»«Punctuation|;» «Keyword|val» e «Operator|=» «String|'»«Constant|\n»«String|'»«Punctuation|;» «Keyword|val» u «Operator|=» «String|'A'»
            «Keyword|val» raw «Operator|=» «String|""${'"'}»
            «String|    raw »«Variable|${'$'}id»«String| »«Punctuation|${'$'}{»items«Punctuation|.»«Property|size»«Punctuation|}»«String| \n "quoted" ""»
            «String|    ""${'"'}»«Punctuation|.»«Function|trimIndent»«Punctuation|()»
            «Keyword|val» quotes «Operator|=» «String|""${'"'}a""${'"'}"»
            «Keyword|val» open «Operator|=» «String|"never closed»
            «Keyword|val» next «Operator|=» «Number|1»
            """.trimIndent(),
        )
    }

    @Test
    fun declarationsAnnotationsAndSoftKeywords() {
        assertGolden(
            "kt",
            """
            @file:JvmName("Store")
            package dev.example.store

            import kotlinx.coroutines.flow.StateFlow

            @Suppress("MagicNumber") @JvmInline
            value class Id(val raw: String)

            sealed interface Status {
                data object Idle : Status
                data class Failed(val message: String, val code: Int = 0x1F) : Status
            }

            class Store<T : Any>(private val capacity: Int = 64) : Base() {
                private val items = linkedMapOf<String, T>()
                val size: Int get() = items.size + 1_000L + 2u + 1.5f + 3.0e-2
                fun String.shout(): String = uppercase()
                suspend fun <R> load(id: String): R? = items[id]?.let { it as R }
                override fun toString() = "Store(${'$'}capacity, ${'$'}{items.size})"
                fun `loads a session`() {
                    items.forEach { return@forEach }
                    val data = Status.Idle; val value = 2
                    when (val s = status) { is Status.Failed -> s.code; else -> null }
                    val ref = String::class
                    val r = 1..10 step 2; val u = 0 until 5
                }
                companion object { const val MAX_SIZE = 10 }
            }
            """.trimIndent(),
            """
            «Attribute|@file:JvmName»«Punctuation|(»«String|"Store"»«Punctuation|)»
            «Keyword|package» dev«Punctuation|.»«Property|example»«Punctuation|.»«Property|store»

            «Keyword|import» kotlinx«Punctuation|.»«Property|coroutines»«Punctuation|.»«Property|flow»«Punctuation|.»«Type|StateFlow»

            «Attribute|@Suppress»«Punctuation|(»«String|"MagicNumber"»«Punctuation|)» «Attribute|@JvmInline»
            «Keyword|value» «Keyword|class» «Type|Id»«Punctuation|(»«Keyword|val» raw«Punctuation|:» «Type|String»«Punctuation|)»

            «Keyword|sealed» «Keyword|interface» «Type|Status» «Punctuation|{»
                «Keyword|data» «Keyword|object» «Type|Idle» «Punctuation|:» «Type|Status»
                «Keyword|data» «Keyword|class» «Type|Failed»«Punctuation|(»«Keyword|val» message«Punctuation|:» «Type|String»«Punctuation|,» «Keyword|val» code«Punctuation|:» «Type|Int» «Operator|=» «Number|0x1F»«Punctuation|)» «Punctuation|:» «Type|Status»
            «Punctuation|}»

            «Keyword|class» «Type|Store»«Operator|<»«Type|T» «Punctuation|:» «Type|Any»«Operator|>»«Punctuation|(»«Keyword|private» «Keyword|val» capacity«Punctuation|:» «Type|Int» «Operator|=» «Number|64»«Punctuation|)» «Punctuation|:» «Function|Base»«Punctuation|()» «Punctuation|{»
                «Keyword|private» «Keyword|val» items «Operator|=» linkedMapOf«Operator|<»«Type|String»«Punctuation|,» «Type|T»«Operator|>»«Punctuation|()»
                «Keyword|val» size«Punctuation|:» «Type|Int» «Function|get»«Punctuation|()» «Operator|=» items«Punctuation|.»«Property|size» «Operator|+» «Number|1_000L» «Operator|+» «Number|2u» «Operator|+» «Number|1.5f» «Operator|+» «Number|3.0e-2»
                «Keyword|fun» «Type|String»«Punctuation|.»«Function|shout»«Punctuation|():» «Type|String» «Operator|=» «Function|uppercase»«Punctuation|()»
                «Keyword|suspend» «Keyword|fun» «Operator|<»«Type|R»«Operator|>» «Function|load»«Punctuation|(»id«Punctuation|:» «Type|String»«Punctuation|):» «Type|R»«Operator|?» «Operator|=» items«Punctuation|[»id«Punctuation|]»«Operator|?»«Punctuation|.»«Property|let» «Punctuation|{» it «Keyword|as» «Type|R» «Punctuation|}»
                «Keyword|override» «Keyword|fun» «Function|toString»«Punctuation|()» «Operator|=» «String|"Store(»«Variable|${'$'}capacity»«String|, »«Punctuation|${'$'}{»items«Punctuation|.»«Property|size»«Punctuation|}»«String|)"»
                «Keyword|fun» «Function|`loads a session`»«Punctuation|()» «Punctuation|{»
                    items«Punctuation|.»«Property|forEach» «Punctuation|{» «Keyword|return»@forEach «Punctuation|}»
                    «Keyword|val» data «Operator|=» «Type|Status»«Punctuation|.»«Type|Idle»«Punctuation|;» «Keyword|val» value «Operator|=» «Number|2»
                    «Keyword|when» «Punctuation|(»«Keyword|val» s «Operator|=» status«Punctuation|)» «Punctuation|{» «Keyword|is» «Type|Status»«Punctuation|.»«Type|Failed» «Operator|->» s«Punctuation|.»«Property|code»«Punctuation|;» «Keyword|else» «Operator|->» «Constant|null» «Punctuation|}»
                    «Keyword|val» ref «Operator|=» «Type|String»«Operator|::»«Keyword|class»
                    «Keyword|val» r «Operator|=» «Number|1»«Operator|..»«Number|10» step «Number|2»«Punctuation|;» «Keyword|val» u «Operator|=» «Number|0» until «Number|5»
                «Punctuation|}»
                «Keyword|companion» «Keyword|object» «Punctuation|{» «Keyword|const» «Keyword|val» «Constant|MAX_SIZE» «Operator|=» «Number|10» «Punctuation|}»
            «Punctuation|}»
            """.trimIndent(),
        )
    }
}
