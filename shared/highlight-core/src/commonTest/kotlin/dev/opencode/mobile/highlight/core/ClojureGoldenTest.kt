package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Clojure fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class ClojureGoldenTest {
    @Test
    fun namespacesAndDefinitions() {
        assertGolden(
            "clojure",
            goldenLines(
                "(ns my.app",
                "  (:require [clojure.string :as str]))",
                "",
                ";; A comment",
                "(defn greet",
                "  \"Docstring\"",
                "  [name & {:keys [punct] :or {punct \"!\"}}]",
                "  (str \"Hello, \" name punct))",
                "",
                "(def ^:private counter (atom 0))",
                "(defrecord Point [x y])",
                "(defmacro unless [c & body] `(if ~c nil (do ~@body)))",
            ),
            goldenLines(
                "«Punctuation|(»«Keyword|ns» my.app",
                "  «Punctuation|(»«Constant|:require» «Punctuation|[»clojure.string «Constant|:as» str«Punctuation|]))»",
                "",
                "«Comment|;; A comment»",
                "«Punctuation|(»«Keyword|defn» «Function|greet»",
                "  «String|\"Docstring\"»",
                "  «Punctuation|[»name «Variable|&» «Punctuation|{»«Constant|:keys» «Punctuation|[»punct«Punctuation|]» «Constant|:or» «Punctuation|{»punct «String|\"!\"»«Punctuation|}}]»",
                "  «Punctuation|(»«Function|str» «String|\"Hello, \"» name punct«Punctuation|))»",
                "",
                "«Punctuation|(»«Keyword|def» «Attribute|^:private» counter «Punctuation|(»«Function|atom» «Number|0»«Punctuation|))»",
                "«Punctuation|(»«Keyword|defrecord» «Type|Point» «Punctuation|[»x y«Punctuation|])»",
                "«Punctuation|(»«Keyword|defmacro» «Function|unless» «Punctuation|[»c «Variable|&» body«Punctuation|]» «Operator|`»«Punctuation|(»«Keyword|if» «Operator|~»c «Constant|nil» «Punctuation|(»«Keyword|do» «Operator|~@»body«Punctuation|)))»",
            ),
        )
    }

    @Test
    fun readerMacrosAndLiterals() {
        assertGolden(
            "clj",
            goldenLines(
                "(let [r #\"\\d+\" s #{1 2} f #(* % 2) g #(+ %1 %&) c \\a nl \\newline u \\u00e9]",
                "  (swap! counter inc)",
                "  (->> [1 2 3] (map inc) (filter odd?))",
                "  @counter #'my.app/greet #?(:clj 1 :cljs 2) #inst \"2024-01-01\"",
                "  (.toUpperCase \"x\") (java.util.Date.) (Math/abs -1)",
                "  ^{:doc \"m\"} [nil true false ::local :ns/kw #:a{:b 1}])",
            ),
            goldenLines(
                "«Punctuation|(»«Keyword|let» «Punctuation|[»r «String|#\"»«Constant|\\d»«String|+\"» s «Punctuation|#{»«Number|1» «Number|2»«Punctuation|}» f «Punctuation|#(»«Function|*» «Variable|%» «Number|2»«Punctuation|)» g «Punctuation|#(»«Function|+» «Variable|%1» «Variable|%&»«Punctuation|)» c «String|\\a» nl «String|\\newline» u «String|\\u00e9»«Punctuation|]»",
                "  «Punctuation|(»«Function|swap!» counter inc«Punctuation|)»",
                "  «Punctuation|(»«Keyword|->>» «Punctuation|[»«Number|1» «Number|2» «Number|3»«Punctuation|]» «Punctuation|(»«Function|map» inc«Punctuation|)» «Punctuation|(»«Function|filter» odd?«Punctuation|))»",
                "  «Operator|@»counter «Operator|#'»my.app/greet «Operator|#?»«Punctuation|(»«Constant|:clj» «Number|1» «Constant|:cljs» «Number|2»«Punctuation|)» «Attribute|#inst» «String|\"2024-01-01\"»",
                "  «Punctuation|(»«Function|.toUpperCase» «String|\"x\"»«Punctuation|)» «Punctuation|(»«Function|java.util.Date.»«Punctuation|)» «Punctuation|(»«Function|Math/abs» «Number|-1»«Punctuation|)»",
                "  «Attribute|^»«Punctuation|{»«Constant|:doc» «String|\"m\"»«Punctuation|}» «Punctuation|[»«Constant|nil» «Constant|true» «Constant|false» «Constant|::local» «Constant|:ns/kw» «Constant|#:a»«Punctuation|{»«Constant|:b» «Number|1»«Punctuation|}])»",
            ),
        )
    }

    @Test
    fun numbers() {
        assertGolden(
            "clojure",
            "[42 -1 +7 1/3 -2/4 1.5 1e10 2.5e-3 42N 1.5M 0x1F 2r1010 36rZZ ##Inf ##NaN]",
            "«Punctuation|[»«Number|42» «Number|-1» «Number|+7» «Number|1/3» «Number|-2/4» «Number|1.5» «Number|1e10» «Number|2.5e-3» «Number|42N» «Number|1.5M» «Number|0x1F» «Number|2r1010» «Number|36rZZ» «Number|##Inf» «Number|##NaN»«Punctuation|]»",
        )
    }

    @Test
    fun discardedForms() {
        assertGolden(
            "clojure",
            goldenLines(
                "[1 #_2 3 #_(a (b \"c)\")) 4 #_ #_ x y 5 #_\"s\" 6]",
                "#_(spans",
                "   lines) (f)",
            ),
            goldenLines(
                "«Punctuation|[»«Number|1» «Comment|#_2» «Number|3» «Comment|#_(a (b \"c)\"))» «Number|4» «Comment|#_ #_ x y» «Number|5» «Comment|#_\"s\"» «Number|6»«Punctuation|]»",
                "«Comment|#_»«Punctuation|(»«Function|spans»",
                "   lines«Punctuation|)» «Punctuation|(»«Function|f»«Punctuation|)»",
            ),
        )
    }

    @Test
    fun stringsAcrossLinesAndUnterminated() {
        assertGolden(
            "clojure",
            goldenLines(
                "(str \"multi",
                "line \\\"quoted\\\" text\" #\"re",
                "gex\")",
                "(def s \"open",
                "still",
            ),
            goldenLines(
                "«Punctuation|(»«Function|str» «String|\"multi»",
                "«String|line »«Constant|\\\"»«String|quoted»«Constant|\\\"»«String| text\"» «String|#\"re»",
                "«String|gex\"»«Punctuation|)»",
                "«Punctuation|(»«Keyword|def» «Variable|s» «String|\"open»",
                "«String|still»",
            ),
        )
    }

    @Test
    fun crlfSurrogatesAndEmoji() {
        assertGolden(
            "clojure",
            goldenLines(
                "(str \"😀\" \\😀) ; 😀\r",
                "(f \\\uD83D \"\uDE00\")\r",
                "",
            ),
            goldenLines(
                "«Punctuation|(»«Function|str» «String|\"😀\"» «String|\\😀»«Punctuation|)» «Comment|; 😀»␍",
                "«Punctuation|(»«Function|f» «String|\\⟨D83D⟩» «String|\"⟨DE00⟩\"»«Punctuation|)»␍",
                "",
            ),
        )
    }

    @Test
    fun linesPastTheCapStayPlain() {
        assertGolden(
            "clojure",
            goldenLines(
                "(str \"abcdefghij\")",
                "(f)",
            ),
            goldenLines(
                "«Punctuation|(»«Function|str» «String|\"abcd»efghij\")",
                "«String|(f)»",
            ),
            HighlightLimits(maxLineLength = 10),
        )
    }
}
