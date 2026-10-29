package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Fortran fixtures, free form and fixed form: the contract for both lexers. One
 * escaped string per line keeps quotes, `$`, CRLF, tabs, and lone surrogates exact.
 */
class FortranGoldenTest {
    @Test
    fun freeFormProgramDeclarationsAndCalls() {
        assertGolden(
            "fortran-free-form",
            goldenLines(
                "program sessions",
                "  use iso_fortran_env, only: dp => real64",
                "  implicit none",
                "  integer :: count, data",
                "  real(kind=dp), allocatable :: x(:)",
                "  type(point) :: p",
                "  count = 3; data = 1",
                "  call report(p%x, count)",
                "  print *, \"sessions:\", count",
                "contains",
                "  pure function area(r) result(a)",
                "  end function area",
                "end program sessions",
            ),
            goldenLines(
                "«Keyword|program» «Function|sessions»",
                "  «Keyword|use» iso_fortran_env«Punctuation|,» «Keyword|only»«Punctuation|:» dp «Operator|=>» real64",
                "  «Keyword|implicit» «Keyword|none»",
                "  «Type|integer» «Operator|::» count«Punctuation|,» data",
                "  «Type|real»«Punctuation|(»kind«Operator|=»dp«Punctuation|),» «Keyword|allocatable» «Operator|::» x«Punctuation|(:)»",
                "  «Keyword|type»«Punctuation|(»«Type|point»«Punctuation|)» «Operator|::» p",
                "  count «Operator|=» «Number|3»«Punctuation|;» data «Operator|=» «Number|1»",
                "  «Keyword|call» «Function|report»«Punctuation|(»p«Punctuation|%»«Property|x»«Punctuation|,» count«Punctuation|)»",
                "  «Keyword|print» «Operator|*»«Punctuation|,» «String|\"sessions:\"»«Punctuation|,» count",
                "«Keyword|contains»",
                "  «Keyword|pure» «Keyword|function» «Function|area»«Punctuation|(»r«Punctuation|)» «Keyword|result»«Punctuation|(»a«Punctuation|)»",
                "  «Keyword|end» «Keyword|function» «Function|area»",
                "«Keyword|end» «Keyword|program» «Function|sessions»",
            ),
        )
    }

    @Test
    fun freeFormOperatorsLiteralsAndNumbers() {
        assertGolden(
            "fortran-free-form",
            goldenLines(
                "if (n .gt. 1 .and. .not. done .or. flag .eqv. .TRUE._lk) then",
                "  x = 1.0_dp + 1.5d-3 * 2.e5 ** 2 // 'a' // \"it\"\"s\"",
                "  m = z'FF' + b\"0101\" + 42_int64 + 1.eq.2",
                "  v = (/ 1, 2 /) == [3, 4]",
                "  s = sqrt(abs(x)) ! comment",
                "end if",
            ),
            goldenLines(
                "«Keyword|if» «Punctuation|(»n «Operator|.gt.» «Number|1» «Operator|.and.» «Operator|.not.» done «Operator|.or.» flag «Operator|.eqv.» «Constant|.TRUE._lk»«Punctuation|)» «Keyword|then»",
                "  x «Operator|=» «Number|1.0_dp» «Operator|+» «Number|1.5d-3» «Operator|*» «Number|2.e5» «Operator|**» «Number|2» «Operator|//» «String|'a'» «Operator|//» «String|\"it»«Constant|\"\"»«String|s\"»",
                "  m «Operator|=» «Number|z'FF'» «Operator|+» «Number|b\"0101\"» «Operator|+» «Number|42_int64» «Operator|+» «Number|1»«Operator|.eq.»«Number|2»",
                "  v «Operator|=» «Punctuation|(/» «Number|1»«Punctuation|,» «Number|2» «Punctuation|/)» «Operator|==» «Punctuation|[»«Number|3»«Punctuation|,» «Number|4»«Punctuation|]»",
                "  s «Operator|=» «Function|sqrt»«Punctuation|(»«Function|abs»«Punctuation|(»x«Punctuation|))» «Comment|! comment»",
                "«Keyword|end» «Keyword|if»",
            ),
        )
    }

    @Test
    fun freeFormContinuationLines() {
        assertGolden(
            "fortran-free-form",
            goldenLines(
                "s = 'one &",
                "    &two' // &",
                "    'three",
                "x = 1 ! & in a comment does not continue",
                "#ifdef DEBUG",
            ),
            goldenLines(
                "s «Operator|=» «String|'one »«Punctuation|&»",
                "    «Punctuation|&»«String|two'» «Operator|//» «Punctuation|&»",
                "    «String|'three»",
                "x «Operator|=» «Number|1» «Comment|! & in a comment does not continue»",
                "«Punctuation|#»«Keyword|ifdef» DEBUG",
            ),
        )
    }

    @Test
    fun fixedFormColumns() {
        assertGolden(
            "fortran-fixed-form",
            goldenLines(
                "C     A COMMENT IN COLUMN 1",
                "c     lowercase comment",
                "*     STAR COMMENT",
                "!     BANG COMMENT",
                "      PROGRAM SESSIONS",
                "      INTEGER COUNT",
                "   10 COUNT = 3",
                "      PRINT *, 'SESSIONS:', COUNT                                       SEQ00001",
                "     1, 'MORE'",
                "      GO TO 10",
                "      END",
            ),
            goldenLines(
                "«Comment|C     A COMMENT IN COLUMN 1»",
                "«Comment|c     lowercase comment»",
                "«Comment|*     STAR COMMENT»",
                "«Comment|!     BANG COMMENT»",
                "      «Keyword|PROGRAM» «Function|SESSIONS»",
                "      «Type|INTEGER» COUNT",
                "   «Number|10» COUNT «Operator|=» «Number|3»",
                "      «Keyword|PRINT» «Operator|*»«Punctuation|,» «String|'SESSIONS:'»«Punctuation|,» COUNT                                       «Comment|SEQ00001»",
                "     «Punctuation|1,» «String|'MORE'»",
                "      «Keyword|GO» «Keyword|TO» «Number|10»",
                "      «Keyword|END»",
            ),
        )
    }

    @Test
    fun fixedFormStringsContinueOntoContinuationLines() {
        assertGolden(
            "fortran-fixed-form",
            goldenLines(
                "      WRITE (*,*) 'OPEN STRING",
                "C     A COMMENT BETWEEN",
                "",
                "     +CONTINUES HERE', X",
                "      Y = 'DROPPED",
                "      Z = 1",
            ),
            goldenLines(
                "      «Keyword|WRITE» «Punctuation|(»«Operator|*»«Punctuation|,»«Operator|*»«Punctuation|)» «String|'OPEN STRING»",
                "«Comment|C     A COMMENT BETWEEN»",
                "",
                "     «Punctuation|+»«String|CONTINUES HERE'»«Punctuation|,» X",
                "      Y «Operator|=» «String|'DROPPED»",
                "      Z «Operator|=» «Number|1»",
            ),
        )
    }

    @Test
    fun fixedFormTabsAndLabelFieldText() {
        assertGolden(
            "fortran-fixed-form",
            "\tX = 1.5D0\n\t1, Y\n  x = 2\n",
            goldenLines(
                "␉X «Operator|=» «Number|1.5D0»",
                "␉«Punctuation|1,» Y",
                "  x «Operator|=» «Number|2»",
                "",
            ),
        )
    }

    @Test
    fun crlfEmojiAndSurrogates() {
        assertGolden(
            "fortran-free-form",
            "s = '😀' ! é\r\nt = \uD83Dx\r\n",
            goldenLines(
                "s «Operator|=» «String|'😀'» «Comment|! é»␍",
                "t «Operator|=» ⟨D83D⟩x␍",
                "",
            ),
        )
    }

    @Test
    fun fixedFormLinesCutByTheCapKeepTheirStringOpen() {
        assertGolden(
            "fortran-fixed-form",
            goldenLines(
                "      PRINT *, 'abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij'",
                "     1, X",
                "      Y = 1",
            ),
            goldenLines(
                "      «Keyword|PRINT» «Operator|*»«Punctuation|,» «String|'abcdefgh»ijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij'",
                "     «Punctuation|1»«String|, X»",
                "      Y «Operator|=» «Number|1»",
            ),
            HighlightLimits(maxLineLength = 24),
        )
    }

    @Test
    fun freeFormLinesCutByTheCapEndTheirString() {
        assertGolden(
            "fortran-free-form",
            goldenLines(
                "s = 'abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij' &",
                "x = 1",
            ),
            goldenLines(
                "s «Operator|=» «String|'abcdefghijabcdefghi»jabcdefghijabcdefghijabcdefghijabcdefghij' &",
                "x «Operator|=» «Number|1»",
            ),
            HighlightLimits(maxLineLength = 24),
        )
    }
}
