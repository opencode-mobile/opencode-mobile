package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Groovy fixtures: the contract for the C-like lexer in Groovy. */
class GroovyGoldenTest {
    @Test
    fun classesGStringsAndSlashyStrings() {
        assertGolden(
            "groovy",
            """
            #!/usr/bin/env groovy
            package com.example

            import groovy.transform.CompileStatic

            @CompileStatic
            class Session implements Serializable {
              static String label(int count) {
                return "sessions: ${'$'}count"
              }
              def greet(String name) {
                def gs = "Hello ${'$'}{name.toUpperCase()} and ${'$'}user.address.city!"
                def plain = 'no ${'$'}interp here\n'
                def tq = '''triple
            single ${'$'}x'''
                def tdq = ""${'"'}triple
            double ${'$'}{name}""${'"'}
                def re = ~/\d+\/x${'$'}/
                def path = /C:\Users\${'$'}name/
                def ds = ${'$'}/dollar ${'$'}name ${'$'}${'$'} ${'$'}/ slashy/${'$'}
                def ratio = a / b / c
                list.each { it -> println it }
                return map?.get('key') ?: null
              }
            }
            """.trimIndent(),
            """
            «Comment|#!/usr/bin/env groovy»
            «Keyword|package» com«Punctuation|.»«Property|example»

            «Keyword|import» groovy«Punctuation|.»«Property|transform»«Punctuation|.»«Type|CompileStatic»

            «Attribute|@CompileStatic»
            «Keyword|class» «Type|Session» «Keyword|implements» «Type|Serializable» «Punctuation|{»
              «Keyword|static» «Type|String» «Function|label»«Punctuation|(»«Keyword|int» count«Punctuation|)» «Punctuation|{»
                «Keyword|return» «String|"sessions: »«Variable|${'$'}count»«String|"»
              «Punctuation|}»
              «Keyword|def» «Function|greet»«Punctuation|(»«Type|String» name«Punctuation|)» «Punctuation|{»
                «Keyword|def» gs «Operator|=» «String|"Hello »«Punctuation|${'$'}{»name«Punctuation|.»«Function|toUpperCase»«Punctuation|()}»«String| and »«Variable|${'$'}user.address.city»«String|!"»
                «Keyword|def» plain «Operator|=» «String|'no ${'$'}interp here»«Constant|\n»«String|'»
                «Keyword|def» tq «Operator|=» «String|'''triple»
            «String|single ${'$'}x'''»
                «Keyword|def» tdq «Operator|=» «String|""${'"'}triple»
            «String|double »«Punctuation|${'$'}{»name«Punctuation|}»«String|""${'"'}»
                «Keyword|def» re «Operator|=» «Operator|~»«String|/\d+»«Constant|\/»«String|x${'$'}/»
                «Keyword|def» path «Operator|=» «String|/C:\Users\»«Variable|${'$'}name»«String|/»
                «Keyword|def» ds «Operator|=» «String|${'$'}/dollar »«Variable|${'$'}name»«String| »«Constant|${'$'}${'$'}»«String| »«Constant|${'$'}/»«String| slashy/${'$'}»
                «Keyword|def» ratio «Operator|=» a «Operator|/» b «Operator|/» c
                list«Punctuation|.»«Property|each» «Punctuation|{» it «Operator|->» println it «Punctuation|}»
                «Keyword|return» map«Operator|?»«Punctuation|.»«Function|get»«Punctuation|(»«String|'key'»«Punctuation|)» «Operator|?:» «Constant|null»
              «Punctuation|}»
            «Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun operatorsClosuresAndNestedGStrings() {
        assertGolden(
            "groovy",
            """
            @Grab('org.apache.commons:commons-lang3:3.12.0')
            def matcher = text =~ /a(\d+)b/
            def same = text ==~ ~/x/
            def closure = { String s, int n = 2 -> s * n }
            assert closure('ab') == 'abab'
            println "${'$'}{[1, 2].collect { "<${'$'}it>" }.join(',')}"
            """.trimIndent(),
            """
            «Attribute|@Grab»«Punctuation|(»«String|'org.apache.commons:commons-lang3:3.12.0'»«Punctuation|)»
            «Keyword|def» matcher «Operator|=» text «Operator|=~» «String|/a(\d+)b/»
            «Keyword|def» same «Operator|=» text «Operator|==~» «Operator|~»«String|/x/»
            «Keyword|def» closure «Operator|=» «Punctuation|{» «Type|String» s«Punctuation|,» «Keyword|int» n «Operator|=» «Number|2» «Operator|->» s «Operator|*» n «Punctuation|}»
            «Keyword|assert» «Function|closure»«Punctuation|(»«String|'ab'»«Punctuation|)» «Operator|==» «String|'abab'»
            println «String|"»«Punctuation|${'$'}{[»«Number|1»«Punctuation|,» «Number|2»«Punctuation|].»«Property|collect» «Punctuation|{» «String|"<»«Variable|${'$'}it»«String|>"» «Punctuation|}.»«Function|join»«Punctuation|(»«String|','»«Punctuation|)}»«String|"»
            """.trimIndent(),
        )
    }
}
