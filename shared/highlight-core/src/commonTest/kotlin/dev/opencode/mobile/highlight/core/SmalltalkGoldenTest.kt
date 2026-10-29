package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Smalltalk fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class SmalltalkGoldenTest {
    @Test
    fun classesAndMethods() {
        assertGolden(
            "smalltalk",
            goldenLines(
                "\"A comment",
                "  across lines\"",
                "Object subclass: #Point",
                "    instanceVariableNames: 'x y'",
                "    package: 'Demo'.",
                "",
                "Point >> x: aNumber y: another",
                "    | sum |",
                "    x := aNumber.",
                "    ^ self class new setX: x y: (another + 16r1F)",
            ),
            goldenLines(
                "«Comment|\"A comment»",
                "«Comment|  across lines\"»",
                "«Type|Object» «Function|subclass:» «Constant|#Point»",
                "    «Function|instanceVariableNames:» «String|'x y'»",
                "    «Function|package:» «String|'Demo'»«Punctuation|.»",
                "",
                "«Type|Point» «Operator|>>» «Function|x:» aNumber «Function|y:» another",
                "    «Punctuation||» sum «Punctuation||»",
                "    x «Operator|:=» aNumber«Punctuation|.»",
                "    «Keyword|^» «Keyword|self» class new «Function|setX:» x «Function|y:» «Punctuation|(»another «Operator|+» «Number|16r1F»«Punctuation|)»",
            ),
        )
    }

    @Test
    fun literalsBlocksAndCascades() {
        assertGolden(
            "smalltalk",
            goldenLines(
                "sum := #(1 \$a #foo bar: at:put: 'it''s' (nested 2) true -3) size.",
                "[:each | each printString] value: 3.",
                "#at:put: , #+ , #'quoted sym' , ##double.",
                "Transcript show: 'Hello'; cr.",
                "thisContext. super foo. nil isNil & true | false.",
                "bytes := #[1 2 3]. brace := {1. 2}.",
                "n := 42 + 3.14 + 1e10 + 2r1010 + 1.5s2 + 1/2.",
            ),
            goldenLines(
                "sum «Operator|:=» «Punctuation|#(»«Number|1» «String|\$a» «Constant|#foo» «Constant|bar:» «Constant|at:put:» «String|'it»«Constant|''»«String|s'» «Punctuation|(»«Constant|nested» «Number|2»«Punctuation|)» «Constant|true» «Number|-3»«Punctuation|)» size«Punctuation|.»",
                "«Punctuation|[»«Variable|:each» «Punctuation||» each printString«Punctuation|]» «Function|value:» «Number|3»«Punctuation|.»",
                "«Constant|#at:put:» «Operator|,» «Constant|#+» «Operator|,» «Constant|#'quoted sym'» «Operator|,» «Constant|##double»«Punctuation|.»",
                "«Type|Transcript» «Function|show:» «String|'Hello'»«Punctuation|;» cr«Punctuation|.»",
                "«Keyword|thisContext»«Punctuation|.» «Keyword|super» foo«Punctuation|.» «Constant|nil» isNil «Operator|&» «Constant|true» «Punctuation||» «Constant|false»«Punctuation|.»",
                "bytes «Operator|:=» «Punctuation|#[»«Number|1» «Number|2» «Number|3»«Punctuation|].» brace «Operator|:=» «Punctuation|{»«Number|1»«Punctuation|.» «Number|2»«Punctuation|}.»",
                "n «Operator|:=» «Number|42» «Operator|+» «Number|3.14» «Operator|+» «Number|1e10» «Operator|+» «Number|2r1010» «Operator|+» «Number|1.5s2» «Operator|+» «Number|1»«Operator|/»«Number|2»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun literalArraysAndStringsAcrossLines() {
        assertGolden(
            "smalltalk",
            goldenLines(
                "arr := #(a #(b",
                "  c) 'str",
                "across' d) size.",
                "\"comment with 'quote'\" x := 'multi",
                "line' , 'next'.",
            ),
            goldenLines(
                "arr «Operator|:=» «Punctuation|#(»«Constant|a» «Punctuation|#(»«Constant|b»",
                "  «Constant|c»«Punctuation|)» «String|'str»",
                "«String|across'» «Constant|d»«Punctuation|)» size«Punctuation|.»",
                "«Comment|\"comment with 'quote'\"» x «Operator|:=» «String|'multi»",
                "«String|line'» «Operator|,» «String|'next'»«Punctuation|.»",
            ),
        )
    }

    @Test
    fun unterminatedStringRunsToTheEnd() {
        assertGolden(
            "smalltalk",
            goldenLines(
                "x := 'open",
                "still.",
            ),
            goldenLines(
                "x «Operator|:=» «String|'open»",
                "«String|still.»",
            ),
        )
    }

    @Test
    fun unterminatedCommentRunsToTheEnd() {
        assertGolden(
            "smalltalk",
            goldenLines(
                "x := 1. \"open",
                "still 'q'",
            ),
            goldenLines(
                "x «Operator|:=» «Number|1»«Punctuation|.» «Comment|\"open»",
                "«Comment|still 'q'»",
            ),
        )
    }

    @Test
    fun crlfSurrogatesAndEmoji() {
        assertGolden(
            "smalltalk",
            goldenLines(
                "x := '😀'. \"😀\"\r",
                "y := \$\uD83D.\r",
                "z := \$😀 , #é.\r",
                "",
            ),
            goldenLines(
                "x «Operator|:=» «String|'😀'»«Punctuation|.» «Comment|\"😀\"»␍",
                "y «Operator|:=» «String|\$⟨D83D⟩»«Punctuation|.»␍",
                "z «Operator|:=» «String|\$😀» «Operator|,» «Constant|#é»«Punctuation|.»␍",
                "",
            ),
        )
    }

    @Test
    fun linesPastTheCapStayPlain() {
        assertGolden(
            "smalltalk",
            goldenLines(
                "x := 'abcdefghij'.",
                "\"abcdefghij",
                "x\" y",
            ),
            goldenLines(
                "x «Operator|:=» «String|'abcd»efghij'.",
                "«String|\"abcdefghi»j",
                "«String|x\" y»",
            ),
            HighlightLimits(maxLineLength = 10),
        )
    }
}
