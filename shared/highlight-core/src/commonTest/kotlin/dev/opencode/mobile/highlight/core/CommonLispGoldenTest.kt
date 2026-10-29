package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Common Lisp fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class CommonLispGoldenTest {
    @Test
    fun definitionsAndLambdaLists() {
        assertGolden(
            "common-lisp",
            goldenLines(
                ";;; A comment",
                "(defpackage :my-app (:use :cl))",
                "(in-package :my-app)",
                "",
                "(defun greet (name &optional (punct \"!\") &key verbose)",
                "  \"Docstring\"",
                "  (format nil \"Hello, ~A~A\" name punct))",
                "",
                "(defvar *counter* 0)",
                "(defclass point () ((x :initarg :x)))",
                "(defmacro with-thing ((var) &body body)",
                "  `(let ((,var 1)) ,@body))",
                "(defmethod area :around ((s shape)) (call-next-method))",
            ),
            goldenLines(
                "«Comment|;;; A comment»",
                "«Punctuation|(»«Keyword|defpackage» «Constant|:my-app» «Punctuation|(»«Constant|:use» «Constant|:cl»«Punctuation|))»",
                "«Punctuation|(»«Keyword|in-package» «Constant|:my-app»«Punctuation|)»",
                "",
                "«Punctuation|(»«Keyword|defun» «Function|greet» «Punctuation|(»«Variable|name» «Keyword|&optional» «Punctuation|(»«Variable|punct» «String|\"!\"»«Punctuation|)» «Keyword|&key» «Variable|verbose»«Punctuation|)»",
                "  «String|\"Docstring\"»",
                "  «Punctuation|(»«Function|format» «Constant|nil» «String|\"Hello, ~A~A\"» name punct«Punctuation|))»",
                "",
                "«Punctuation|(»«Keyword|defvar» «Variable|*counter*» «Number|0»«Punctuation|)»",
                "«Punctuation|(»«Keyword|defclass» «Type|point» «Punctuation|()» «Punctuation|((»«Function|x» «Constant|:initarg» «Constant|:x»«Punctuation|)))»",
                "«Punctuation|(»«Keyword|defmacro» «Function|with-thing» «Punctuation|((»«Variable|var»«Punctuation|)» «Keyword|&body» «Variable|body»«Punctuation|)»",
                "  «Operator|`»«Punctuation|(»«Keyword|let» «Punctuation|((»«Operator|,»var «Number|1»«Punctuation|))» «Operator|,@»body«Punctuation|))»",
                "«Punctuation|(»«Keyword|defmethod» «Function|area» «Constant|:around» «Punctuation|((»«Variable|s» «Variable|shape»«Punctuation|))» «Punctuation|(»«Function|call-next-method»«Punctuation|))»",
            ),
        )
    }

    @Test
    fun letBindingsQuotesAndCharacters() {
        assertGolden(
            "lisp",
            goldenLines(
                "(let ((c #\\a) (s #\\Space) (p #\\() (r (compute 1)))",
                "  (mapcar #'1+ '(1 2 3))",
                "  (lambda (x y) (+ x y))",
                "  (destructuring-bind (a b) list (list a b))",
                "  (when (and t nil) (print c)))",
                "(DEFUN Upper (X) (PRINC X))",
            ),
            goldenLines(
                "«Punctuation|(»«Keyword|let» «Punctuation|((»«Variable|c» «String|#\\a»«Punctuation|)» «Punctuation|(»«Variable|s» «String|#\\Space»«Punctuation|)» «Punctuation|(»«Variable|p» «String|#\\(»«Punctuation|)» «Punctuation|(»«Variable|r» «Punctuation|(»«Function|compute» «Number|1»«Punctuation|)))»",
                "  «Punctuation|(»«Function|mapcar» «Operator|#'»«Function|1+» «Operator|'»«Punctuation|(»«Number|1» «Number|2» «Number|3»«Punctuation|))»",
                "  «Punctuation|(»«Keyword|lambda» «Punctuation|(»«Variable|x» «Variable|y»«Punctuation|)» «Punctuation|(»«Function|+» x y«Punctuation|))»",
                "  «Punctuation|(»«Keyword|destructuring-bind» «Punctuation|(»«Variable|a» «Variable|b»«Punctuation|)» list «Punctuation|(»«Function|list» a b«Punctuation|))»",
                "  «Punctuation|(»«Keyword|when» «Punctuation|(»«Keyword|and» «Constant|t» «Constant|nil»«Punctuation|)» «Punctuation|(»«Function|print» c«Punctuation|)))»",
                "«Punctuation|(»«Keyword|DEFUN» «Function|Upper» «Punctuation|(»«Variable|X»«Punctuation|)» «Punctuation|(»«Function|PRINC» X«Punctuation|))»",
            ),
        )
    }

    @Test
    fun numbersAndReaderMacros() {
        assertGolden(
            "common-lisp",
            goldenLines(
                "(list 42 -7 +3 1/3 1.5 1.5d0 6.02e23 .5 #x1F #b101 #o17 #36rZZ 1+ 1- +)",
                "#+sbcl (sb-ext:quit) #-(or ccl) (other) #p\"/tmp/x\" #(1 2) #:gensym #.(eval) #1=(a . #1#)",
                "(pkg:sym pkg::internal |piped symbol| \"esc\\\"aped\")",
            ),
            goldenLines(
                "«Punctuation|(»«Function|list» «Number|42» «Number|-7» «Number|+3» «Number|1/3» «Number|1.5» «Number|1.5d0» «Number|6.02e23» «Number|.5» «Number|#x1F» «Number|#b101» «Number|#o17» «Number|#36rZZ» 1+ 1- +«Punctuation|)»",
                "«Attribute|#+sbcl» «Punctuation|(»«Function|sb-ext:quit»«Punctuation|)» «Attribute|#-»«Punctuation|(»«Keyword|or» ccl«Punctuation|)» «Punctuation|(»«Function|other»«Punctuation|)» «Operator|#p»«String|\"/tmp/x\"» «Punctuation|#(»«Number|1» «Number|2»«Punctuation|)» «Constant|#:gensym» «Operator|#.»«Punctuation|(»«Function|eval»«Punctuation|)» «Operator|#1=»«Punctuation|(»«Function|a» . «Operator|#1#»«Punctuation|)»",
                "«Punctuation|(»«Function|pkg:sym» pkg::internal |piped symbol| «String|\"esc»«Constant|\\\"»«String|aped\"»«Punctuation|)»",
            ),
        )
    }

    @Test
    fun commentsNestAcrossLines() {
        assertGolden(
            "common-lisp",
            goldenLines(
                "(f \"multi",
                "line\") #| outer #| inner |#",
                "  still |# (g)",
                "#| a #| b #| c #| d |# |# |# |# (h)",
            ),
            goldenLines(
                "«Punctuation|(»«Function|f» «String|\"multi»",
                "«String|line\"»«Punctuation|)» «Comment|#| outer #| inner |#»",
                "«Comment|  still |#» «Punctuation|(»«Function|g»«Punctuation|)»",
                "«Comment|#| a #| b #| c #| d |# |# |# |#» «Punctuation|(»«Function|h»«Punctuation|)»",
            ),
        )
    }

    @Test
    fun unterminatedStringRunsToTheEnd() {
        assertGolden(
            "common-lisp",
            goldenLines(
                "(f \"open",
                "(g)",
            ),
            goldenLines(
                "«Punctuation|(»«Function|f» «String|\"open»",
                "«String|(g)»",
            ),
        )
    }

    @Test
    fun unterminatedCommentRunsToTheEnd() {
        assertGolden(
            "common-lisp",
            goldenLines(
                "(f) #| open #| nested |#",
                "(g) |#",
            ),
            goldenLines(
                "«Punctuation|(»«Function|f»«Punctuation|)» «Comment|#| open #| nested |#»",
                "«Comment|(g) |#»",
            ),
        )
    }

    @Test
    fun crlfSurrogatesAndEmoji() {
        assertGolden(
            "common-lisp",
            goldenLines(
                "(f \"😀\" #\\😀) ; 😀\r",
                "(g #\\\uD83D \"\uDE00\")\r",
                "",
            ),
            goldenLines(
                "«Punctuation|(»«Function|f» «String|\"😀\"» «String|#\\😀»«Punctuation|)» «Comment|; 😀»␍",
                "«Punctuation|(»«Function|g» «String|#\\⟨D83D⟩» «String|\"⟨DE00⟩\"»«Punctuation|)»␍",
                "",
            ),
        )
    }

    @Test
    fun linesPastTheCapStayPlain() {
        assertGolden(
            "common-lisp",
            goldenLines(
                "(f \"abcdefghij\")",
                "#| abcdefghij",
                "|# x",
            ),
            goldenLines(
                "«Punctuation|(»«Function|f» «String|\"abcdef»ghij\")",
                "«String|#| abcdefg»hij",
                "«String||# x»",
            ),
            HighlightLimits(maxLineLength = 10),
        )
    }
}
