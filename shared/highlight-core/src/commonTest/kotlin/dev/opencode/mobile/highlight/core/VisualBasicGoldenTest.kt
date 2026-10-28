package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Visual Basic fixtures: the contract for its lexer. One escaped string per line
 * keeps quotes, `$`, CRLF, tabs, and lone surrogates exact.
 */
class VisualBasicGoldenTest {
    @Test
    fun modulesProceduresAndTypes() {
        assertGolden(
            "vb",
            goldenLines(
                "Imports System.Text",
                "Module Sessions",
                "    Function Label(count As Integer) As String",
                "        Return \$\"sessions: {count}\"",
                "    End Function",
                "    Public Sub Main()",
                "        Dim list As New List(Of String)",
                "        list.Add(\"a\") : Call Report(list)",
                "        Dim x = Math.Max(1, 2) : Total(1) = Nothing",
                "        If x IsNot Nothing AndAlso Me.Ready Then Return",
                "    End Sub",
                "End Module",
            ),
            goldenLines(
                "«Keyword|Imports» System«Punctuation|.»Text",
                "«Keyword|Module» «Type|Sessions»",
                "    «Keyword|Function» «Function|Label»«Punctuation|(»count «Keyword|As» «Type|Integer»«Punctuation|)» «Keyword|As» «Type|String»",
                "        «Keyword|Return» «String|\$\"sessions: »«Punctuation|{»count«Punctuation|}»«String|\"»",
                "    «Keyword|End» «Keyword|Function»",
                "    «Keyword|Public» «Keyword|Sub» «Function|Main»«Punctuation|()»",
                "        «Keyword|Dim» list «Keyword|As» «Keyword|New» «Type|List»«Punctuation|(»«Keyword|Of» «Type|String»«Punctuation|)»",
                "        list«Punctuation|.»«Function|Add»«Punctuation|(»«String|\"a\"»«Punctuation|)» «Punctuation|:» «Keyword|Call» «Function|Report»«Punctuation|(»list«Punctuation|)»",
                "        «Keyword|Dim» x «Operator|=» Math«Punctuation|.»«Function|Max»«Punctuation|(»«Number|1»«Punctuation|,» «Number|2»«Punctuation|)» «Punctuation|:» «Function|Total»«Punctuation|(»«Number|1»«Punctuation|)» «Operator|=» «Constant|Nothing»",
                "        «Keyword|If» x «Keyword|IsNot» «Constant|Nothing» «Keyword|AndAlso» «Keyword|Me»«Punctuation|.»Ready «Keyword|Then» «Keyword|Return»",
                "    «Keyword|End» «Keyword|Sub»",
                "«Keyword|End» «Keyword|Module»",
            ),
        )
    }

    @Test
    fun commentsAndRem() {
        assertGolden(
            "vb",
            goldenLines(
                "' a comment",
                "REM another comment",
                "x = 1 ' trailing",
                "y = 2 : Rem after a colon",
                "Remove(1) : z = Remainder",
            ),
            goldenLines(
                "«Comment|' a comment»",
                "«Comment|REM another comment»",
                "x «Operator|=» «Number|1» «Comment|' trailing»",
                "y «Operator|=» «Number|2» «Punctuation|:» «Comment|Rem after a colon»",
                "«Function|Remove»«Punctuation|(»«Number|1»«Punctuation|)» «Punctuation|:» z «Operator|=» Remainder",
            ),
        )
    }

    @Test
    fun literals() {
        assertGolden(
            "vb",
            goldenLines(
                "s = \"say \"\"hi\"\"\" & \"x\"c",
                "d = #1/1/2020# : t = #12:30 PM#",
                "n = &HFF + &O17 + &B1010 + 1_000 + 1.5E3 + 10UL + 2.5D + 3!",
                "b = True Or False Or Nothing",
            ),
            goldenLines(
                "s «Operator|=» «String|\"say »«Constant|\"\"»«String|hi»«Constant|\"\"»«String|\"» «Operator|&» «String|\"x\"c»",
                "d «Operator|=» «Constant|#1/1/2020#» «Punctuation|:» t «Operator|=» «Constant|#12:30 PM#»",
                "n «Operator|=» «Number|&HFF» «Operator|+» «Number|&O17» «Operator|+» «Number|&B1010» «Operator|+» «Number|1_000» «Operator|+» «Number|1.5E3» «Operator|+» «Number|10UL» «Operator|+» «Number|2.5D» «Operator|+» «Number|3!»",
                "b «Operator|=» «Constant|True» «Keyword|Or» «Constant|False» «Keyword|Or» «Constant|Nothing»",
            ),
        )
    }

    @Test
    fun interpolatedStrings() {
        assertGolden(
            "vb",
            goldenLines(
                "s = \$\"{count:N2} items {{literal}} {If(x, \"a\"\"b\", \"\")}\"",
                "t = \$\"open {Call(",
                "  1)} still text",
                "end\"",
            ),
            goldenLines(
                "s «Operator|=» «String|\$\"»«Punctuation|{»count«String|:N2»«Punctuation|}»«String| items »«Constant|{{»«String|literal»«Constant|}}»«String| »«Punctuation|{»«Keyword|If»«Punctuation|(»x«Punctuation|,» «String|\"a»«Constant|\"\"»«String|b\"»«Punctuation|,» «String|\"\"»«Punctuation|)}»«String|\"»",
                "t «Operator|=» «String|\$\"open »«Punctuation|{»«Keyword|Call»«Punctuation|(»",
                "  «Number|1»«Punctuation|)}»«String| still text»",
                "«String|end\"»",
            ),
        )
    }

    @Test
    fun multiLineStringsAndContinuations() {
        assertGolden(
            "vb",
            goldenLines(
                "s = \"first line",
                "second line\" & _",
                "    \"next\"",
                "x = 1 + _ ' comment after the underscore",
                "    2",
            ),
            goldenLines(
                "s «Operator|=» «String|\"first line»",
                "«String|second line\"» «Operator|&» «Punctuation|_»",
                "    «String|\"next\"»",
                "x «Operator|=» «Number|1» «Operator|+» «Punctuation|_» «Comment|' comment after the underscore»",
                "    «Number|2»",
            ),
        )
    }

    @Test
    fun attributesDirectivesAndEscapedNames() {
        assertGolden(
            "vb",
            goldenLines(
                "<Serializable>",
                "<Assembly: CLSCompliant(True)>",
                "Public Sub Run(<Out> ByRef x As Integer, Optional y As Integer = 1)",
                "#If DEBUG Then",
                "#End If",
                "#Region \"Helpers\"",
                "Dim [Error] As Integer = If(a < b, a, b)",
            ),
            goldenLines(
                "«Punctuation|<»«Attribute|Serializable»«Punctuation|>»",
                "«Punctuation|<»«Keyword|Assembly»«Punctuation|:» «Attribute|CLSCompliant»«Punctuation|(»«Constant|True»«Punctuation|)>»",
                "«Keyword|Public» «Keyword|Sub» «Function|Run»«Punctuation|(<»«Attribute|Out»«Punctuation|>» «Keyword|ByRef» x «Keyword|As» «Type|Integer»«Punctuation|,» «Keyword|Optional» y «Keyword|As» «Type|Integer» «Operator|=» «Number|1»«Punctuation|)»",
                "«Punctuation|#»«Keyword|If» DEBUG «Keyword|Then»",
                "«Punctuation|#»«Keyword|End» «Keyword|If»",
                "«Punctuation|#»«Keyword|Region» «String|\"Helpers\"»",
                "«Keyword|Dim» [Error] «Keyword|As» «Type|Integer» «Operator|=» «Keyword|If»«Punctuation|(»a «Operator|<» b«Punctuation|,» a«Punctuation|,» b«Punctuation|)»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndSurrogates() {
        assertGolden(
            "vb",
            "s = \"😀\" ' é\r\nt = \uD83D\r\n",
            goldenLines(
                "s «Operator|=» «String|\"😀\"» «Comment|' é»␍",
                "t «Operator|=» ⟨D83D⟩␍",
                "",
            ),
        )
    }

    @Test
    fun aStringCutByTheCapStaysOpen() {
        assertGolden(
            "vb",
            goldenLines(
                "s = \"abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij\"",
                "still string\" & x",
            ),
            goldenLines(
                "s «Operator|=» «String|\"abcdefghijabcdefghi»jabcdefghijabcdefghijabcdefghijabcdefghij\"",
                "«String|still string\"» «Operator|&» x",
            ),
            HighlightLimits(maxLineLength = 24),
        )
    }
}
