package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written XML fixtures: the contract for the XML lexer. */
class XmlGoldenTest {
    @Test
    fun declarationsNamespacesAndCdata() {
        assertGolden(
            "xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE note [
              <!ENTITY writer "Writer &amp; co">
              <!ELEMENT note (#PCDATA)>
            ]>
            <ns:root xmlns:ns="urn:x" ns:id="1">
              <child attr='v'>text &writer; &#10;</child>
              <![CDATA[ <not> a tag ]]>
              <![CDATA[ open
              still ]]> after
              <!-- c -->
              <Empty/>
            </ns:root>
            """.trimIndent(),
            """
            «Punctuation|<?»«Tag|xml» «Attribute|version»«Punctuation|=»«String|"1.0"» «Attribute|encoding»«Punctuation|=»«String|"UTF-8"»«Punctuation|?>»
            «Punctuation|<!»«Keyword|DOCTYPE» note «Punctuation|[»
              «Punctuation|<!»«Keyword|ENTITY» writer «String|"Writer &amp; co"»«Punctuation|>»
              «Punctuation|<!»«Keyword|ELEMENT» note (#PCDATA)«Punctuation|>»
            «Punctuation|]>»
            «Punctuation|<»«Tag|ns»«Punctuation|:»«Tag|root» «Attribute|xmlns»«Punctuation|:»«Attribute|ns»«Punctuation|=»«String|"urn:x"» «Attribute|ns»«Punctuation|:»«Attribute|id»«Punctuation|=»«String|"1"»«Punctuation|>»
              «Punctuation|<»«Tag|child» «Attribute|attr»«Punctuation|=»«String|'v'»«Punctuation|>»text «Constant|&writer;» «Constant|&#10;»«Punctuation|</»«Tag|child»«Punctuation|>»
              «Punctuation|<![CDATA[»«String| <not> a tag »«Punctuation|]]>»
              «Punctuation|<![CDATA[»«String| open»
            «String|  still »«Punctuation|]]>» after
              «Comment|<!-- c -->»
              «Punctuation|<»«Tag|Empty»«Punctuation|/>»
            «Punctuation|</»«Tag|ns»«Punctuation|:»«Tag|root»«Punctuation|>»
            """.trimIndent(),
        )
    }

    @Test
    fun unterminatedCdataAndInstruction() {
        assertGolden(
            "xml",
            """
            <?xml-stylesheet href="a.xsl"
              type="text/xsl"?>
            <a><![CDATA[ never closed
            """.trimIndent(),
            """
            «Punctuation|<?»«Tag|xml-stylesheet» «Attribute|href»«Punctuation|=»«String|"a.xsl"»
              «Attribute|type»«Punctuation|=»«String|"text/xsl"»«Punctuation|?>»
            «Punctuation|<»«Tag|a»«Punctuation|><![CDATA[»«String| never closed»
            """.trimIndent(),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "xml",
            "<a b=\"\uDE00\">\uD83D\uDE00</a>\r\n<!-- x\r\n--><b/>",
            "«Punctuation|<»«Tag|a» «Attribute|b»«Punctuation|=»«String|\"⟨DE00⟩\"»«Punctuation|>»\uD83D\uDE00«Punctuation|</»«Tag|a»«Punctuation|>»␍\n«Comment|<!-- x»␍\n«Comment|-->»«Punctuation|<»«Tag|b»«Punctuation|/>»",
        )
    }
}
