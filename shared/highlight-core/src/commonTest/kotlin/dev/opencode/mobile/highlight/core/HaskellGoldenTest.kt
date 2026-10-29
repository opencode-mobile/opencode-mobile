package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Haskell fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class HaskellGoldenTest {
    @Test
    fun modulesDataAndSignatures() {
        assertGolden(
            "haskell",
            goldenLines(
                "{-# LANGUAGE OverloadedStrings #-}",
                "module Main (main) where",
                "",
                "import qualified Data.Map as M",
                "import Data.List (foldl')",
                "",
                "data Shape = Circle Double | Rect { width :: Double }",
                "  deriving (Show, Eq)",
                "",
                "area :: Shape -> Double",
                "area (Circle r) = pi * r ^ 2",
                "area (Rect w) = w * w",
            ),
            goldenLines(
                "«Attribute|{-# LANGUAGE OverloadedStrings #-}»",
                "«Keyword|module» «Type|Main» «Punctuation|(»main«Punctuation|)» «Keyword|where»",
                "",
                "«Keyword|import» «Keyword|qualified» «Type|Data»«Punctuation|.»«Type|Map» «Keyword|as» «Type|M»",
                "«Keyword|import» «Type|Data»«Punctuation|.»«Type|List» «Punctuation|(»foldl'«Punctuation|)»",
                "",
                "«Keyword|data» «Type|Shape» «Operator|=» «Type|Circle» «Type|Double» «Operator||» «Type|Rect» «Punctuation|{» «Function|width» «Operator|::» «Type|Double» «Punctuation|}»",
                "  «Keyword|deriving» «Punctuation|(»«Type|Show»«Punctuation|,» «Type|Eq»«Punctuation|)»",
                "",
                "«Function|area» «Operator|::» «Type|Shape» «Operator|->» «Type|Double»",
                "«Function|area» «Punctuation|(»«Type|Circle» r«Punctuation|)» «Operator|=» pi «Operator|*» r «Operator|^» «Number|2»",
                "«Function|area» «Punctuation|(»«Type|Rect» w«Punctuation|)» «Operator|=» w «Operator|*» w",
            ),
        )
    }

    @Test
    fun doBlocksCaseAndWhere() {
        assertGolden(
            "hs",
            goldenLines(
                "main :: IO ()",
                "main = do",
                "  let xs = [1..10] :: [Int]",
                "      x' = foldl' (+) 0 xs `div` 2",
                "  case M.lookup 1 M.empty of",
                "    Nothing -> pure ()",
                "    Just (y:_) -> print (y, True)",
                "  where go acc = acc",
            ),
            goldenLines(
                "«Function|main» «Operator|::» «Type|IO» «Punctuation|()»",
                "«Function|main» «Operator|=» «Keyword|do»",
                "  «Keyword|let» xs «Operator|=» «Punctuation|[»«Number|1»«Operator|..»«Number|10»«Punctuation|]» «Operator|::» «Punctuation|[»«Type|Int»«Punctuation|]»",
                "      x' «Operator|=» foldl' «Punctuation|(»«Operator|+»«Punctuation|)» «Number|0» xs «Operator|`div`» «Number|2»",
                "  «Keyword|case» «Type|M»«Punctuation|.»lookup «Number|1» «Type|M»«Punctuation|.»empty «Keyword|of»",
                "    «Type|Nothing» «Operator|->» pure «Punctuation|()»",
                "    «Type|Just» «Punctuation|(»y«Operator|:»_«Punctuation|)» «Operator|->» print «Punctuation|(»y«Punctuation|,» «Constant|True»«Punctuation|)»",
                "  «Keyword|where» go acc «Operator|=» acc",
            ),
        )
    }

    @Test
    fun stringsCharactersAndPrimes() {
        assertGolden(
            "haskell",
            goldenLines(
                "s = \"tab\\t quote\\\" code\\1234 hex\\x7F\" ++ show 'a'",
                "c = ['\\n', '\\'', '\"', '\\x41', '\\^A']",
                "f' x'' = foldr' x'' 'b' ''Maybe 'Just",
                "g = \"gap \\",
                "    \\continues\" ++ \"after\"",
            ),
            goldenLines(
                "«Function|s» «Operator|=» «String|\"tab»«Constant|\\t»«String| quote»«Constant|\\\"»«String| code»«Constant|\\1234»«String| hex»«Constant|\\x7F»«String|\"» «Operator|++» show «String|'a'»",
                "«Function|c» «Operator|=» «Punctuation|[»«String|'\\n'»«Punctuation|,» «String|'\\''»«Punctuation|,» «String|'\"'»«Punctuation|,» «String|'\\x41'»«Punctuation|,» «String|'\\^A'»«Punctuation|]»",
                "«Function|f'» x'' «Operator|=» foldr' x'' «String|'b'» «Operator|''»«Type|Maybe» «Operator|'»«Type|Just»",
                "«Function|g» «Operator|=» «String|\"gap »«Constant|\\»",
                "«Constant|    \\»«String|continues\"» «Operator|++» «String|\"after\"»",
            ),
        )
    }

    @Test
    fun commentsNestAcrossLines() {
        assertGolden(
            "haskell",
            goldenLines(
                "-- | Haddock",
                "x = 1 -- trailing",
                "y = a --> b |-- c",
                "{- outer {- inner -}",
                "   still outer -} z = 2",
                "{- a {- b {- c {- d {- e -} -} -} -} -} w = 3",
            ),
            goldenLines(
                "«Comment|-- | Haddock»",
                "«Function|x» «Operator|=» «Number|1» «Comment|-- trailing»",
                "«Function|y» «Operator|=» a «Operator|-->» b «Operator||--» c",
                "«Comment|{- outer {- inner -}»",
                "«Comment|   still outer -}» z «Operator|=» «Number|2»",
                "«Comment|{- a {- b {- c {- d {- e -} -} -} -} -}» w «Operator|=» «Number|3»",
            ),
        )
    }

    @Test
    fun quasiQuotesAndTemplateHaskell() {
        assertGolden(
            "haskell",
            goldenLines(
                "sql = [sql|SELECT *",
                "  FROM t WHERE x = 'a'|] ++ more",
                "expr = [e|1 + 2|] ++ [|x|] ++ [d| f = 1 |]",
                "html = [Text.Heredoc.here|<p>\"x\"</p>|]",
            ),
            goldenLines(
                "«Function|sql» «Operator|=» «Punctuation|[»«Function|sql»«Punctuation||»«String|SELECT *»",
                "«String|  FROM t WHERE x = 'a'»«Punctuation||]» «Operator|++» more",
                "«Function|expr» «Operator|=» «Punctuation|[»«Keyword|e»«Punctuation||»«Number|1» «Operator|+» «Number|2»«Operator||»«Punctuation|]» «Operator|++» «Punctuation|[|»x«Operator||»«Punctuation|]» «Operator|++» «Punctuation|[»«Keyword|d»«Punctuation||» f «Operator|=» «Number|1» «Operator||»«Punctuation|]»",
                "«Function|html» «Operator|=» «Punctuation|[»«Function|Text.Heredoc.here»«Punctuation||»«String|<p>\"x\"</p>»«Punctuation||]»",
            ),
        )
    }

    @Test
    fun numbersAndOperators() {
        assertGolden(
            "haskell",
            goldenLines(
                "n = 42 + 0x1F + 0o17 + 0b101 + 1_000_000 + 3.14 + 6.02e23 + 1e-3",
                "op = a <\$> b <*> c >>= \\x -> x . y \$ z",
                "r = [a..b] ++ xs !! 0",
            ),
            goldenLines(
                "«Function|n» «Operator|=» «Number|42» «Operator|+» «Number|0x1F» «Operator|+» «Number|0o17» «Operator|+» «Number|0b101» «Operator|+» «Number|1_000_000» «Operator|+» «Number|3.14» «Operator|+» «Number|6.02e23» «Operator|+» «Number|1e-3»",
                "«Function|op» «Operator|=» a «Operator|<\$>» b «Operator|<*>» c «Operator|>>=» «Operator|\\»x «Operator|->» x «Operator|.» y «Operator|\$» z",
                "«Function|r» «Operator|=» «Punctuation|[»a«Operator|..»b«Punctuation|]» «Operator|++» xs «Operator|!!» «Number|0»",
            ),
        )
    }

    @Test
    fun unterminatedStringEndsAtTheLine() {
        assertGolden(
            "haskell",
            goldenLines(
                "s = \"open",
                "t = 1",
            ),
            goldenLines(
                "«Function|s» «Operator|=» «String|\"open»",
                "«Function|t» «Operator|=» «Number|1»",
            ),
        )
    }

    @Test
    fun unterminatedCommentRunsToTheEnd() {
        assertGolden(
            "haskell",
            goldenLines(
                "x = 1 {- open {- nested -}",
                "still",
            ),
            goldenLines(
                "«Function|x» «Operator|=» «Number|1» «Comment|{- open {- nested -}»",
                "«Comment|still»",
            ),
        )
    }

    @Test
    fun unterminatedQuasiQuoteRunsToTheEnd() {
        assertGolden(
            "haskell",
            goldenLines(
                "x = [q|open",
                "\"still\" {- quasi",
            ),
            goldenLines(
                "«Function|x» «Operator|=» «Punctuation|[»«Function|q»«Punctuation||»«String|open»",
                "«String|\"still\" {- quasi»",
            ),
        )
    }

    @Test
    fun unterminatedPragmaRunsToTheEnd() {
        assertGolden(
            "haskell",
            goldenLines(
                "{-# LANGUAGE",
                "  GADTs",
            ),
            goldenLines(
                "«Attribute|{-# LANGUAGE»",
                "«Attribute|  GADTs»",
            ),
        )
    }

    @Test
    fun crlfSurrogatesAndEmoji() {
        assertGolden(
            "haskell",
            goldenLines(
                "x = \"😀\" -- 😀\r",
                "{- \uD83D\r",
                "-} y = '\uDE00'\r",
                "z = 'é'\r",
                "",
            ),
            goldenLines(
                "«Function|x» «Operator|=» «String|\"😀\"» «Comment|-- 😀»␍",
                "«Comment|{- ⟨D83D⟩»␍",
                "«Comment|-}» y «Operator|=» «String|'⟨DE00⟩'»␍",
                "«Function|z» «Operator|=» «String|'é'»␍",
                "",
            ),
        )
    }

    @Test
    fun linesPastTheCapStayPlain() {
        assertGolden(
            "haskell",
            goldenLines(
                "x = \"abcdefghij\"",
                "{- abcdefghij",
                "-} y",
            ),
            goldenLines(
                "«Function|x» «Operator|=» «String|\"abcde»fghij\"",
                "«Comment|{- abcdefg»hij",
                "«Comment|-}» y",
            ),
            HighlightLimits(maxLineLength = 10),
        )
    }
}
