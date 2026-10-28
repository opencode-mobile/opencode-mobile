package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Scala fixtures: the contract for the C-like lexer in Scala. */
class ScalaGoldenTest {
    @Test
    fun objectsInterpolatorsAndPatterns() {
        assertGolden(
            "scala",
            """
            package com.example

            import scala.concurrent.{Future, ExecutionContext}

            /** Doc /* nested */ comment */
            @main def run(args: String*): Unit = println(s"Hello ${'$'}{args.mkString(", ")} ${'$'}name${'$'}${'$'} \n")

            sealed trait Shape
            case class Circle(radius: Double) extends Shape
            object Sessions {
              def label(count: Int): String =
                s"sessions: ${'$'}count"
              val raw = ""${'"'}line one
              "quoted" \n not escaped
              ""${'"'}
              val f = f"${'$'}pi%.2f and ${'$'}{x + 1}"
              val sym = 'mySymbol
              val c = 'a'
              val e = '\n'
              type Id = Long
              given ordering: Ordering[Int] = Ordering.Int
              def apply[A](xs: List[A])(using ctx: Ctx): Option[A] = xs match {
                case head :: _ => Some(head)
                case Nil => None
              }
              val `type` = 0x1F + 1_000L + 2.5f
              val ml = s""${'"'}multi ${'$'}x
              ${'$'}{y} end""${'"'}
            }
            """.trimIndent(),
            """
            «Keyword|package» com«Punctuation|.»«Property|example»

            «Keyword|import» scala«Punctuation|.»«Property|concurrent»«Punctuation|.{»«Type|Future»«Punctuation|,» «Type|ExecutionContext»«Punctuation|}»

            «Comment|/** Doc /* nested */ comment */»
            «Attribute|@main» «Keyword|def» «Function|run»«Punctuation|(»args«Operator|:» «Type|String»«Operator|*»«Punctuation|)»«Operator|:» «Type|Unit» «Operator|=» «Function|println»«Punctuation|(»«Keyword|s»«String|"Hello »«Punctuation|${'$'}{»args«Punctuation|.»«Function|mkString»«Punctuation|(»«String|", "»«Punctuation|)}»«String| »«Punctuation|${'$'}»«Variable|name»«Constant|${'$'}${'$'}»«String| »«Constant|\n»«String|"»«Punctuation|)»

            «Keyword|sealed» «Keyword|trait» «Type|Shape»
            «Keyword|case» «Keyword|class» «Type|Circle»«Punctuation|(»radius«Operator|:» «Type|Double»«Punctuation|)» «Keyword|extends» «Type|Shape»
            «Keyword|object» «Type|Sessions» «Punctuation|{»
              «Keyword|def» «Function|label»«Punctuation|(»count«Operator|:» «Type|Int»«Punctuation|)»«Operator|:» «Type|String» «Operator|=»
                «Keyword|s»«String|"sessions: »«Punctuation|${'$'}»«Variable|count»«String|"»
              «Keyword|val» raw «Operator|=» «String|""${'"'}line one»
            «String|  "quoted" \n not escaped»
            «String|  ""${'"'}»
              «Keyword|val» f «Operator|=» «Keyword|f»«String|"»«Punctuation|${'$'}»«Variable|pi»«String|%.2f and »«Punctuation|${'$'}{»x «Operator|+» «Number|1»«Punctuation|}»«String|"»
              «Keyword|val» sym «Operator|=» «Constant|'mySymbol»
              «Keyword|val» c «Operator|=» «String|'a'»
              «Keyword|val» e «Operator|=» «String|'»«Constant|\n»«String|'»
              «Keyword|type» «Type|Id» «Operator|=» «Type|Long»
              «Keyword|given» ordering«Operator|:» «Type|Ordering»«Punctuation|[»«Type|Int»«Punctuation|]» «Operator|=» «Type|Ordering»«Punctuation|.»«Type|Int»
              «Keyword|def» «Function|apply»«Punctuation|[»«Type|A»«Punctuation|](»xs«Operator|:» «Type|List»«Punctuation|[»«Type|A»«Punctuation|])(»«Keyword|using» ctx«Operator|:» «Type|Ctx»«Punctuation|)»«Operator|:» «Type|Option»«Punctuation|[»«Type|A»«Punctuation|]» «Operator|=» xs «Keyword|match» «Punctuation|{»
                «Keyword|case» head «Operator|::» _ «Operator|=>» «Function|Some»«Punctuation|(»head«Punctuation|)»
                «Keyword|case» «Type|Nil» «Operator|=>» «Type|None»
              «Punctuation|}»
              «Keyword|val» `type` «Operator|=» «Number|0x1F» «Operator|+» «Number|1_000L» «Operator|+» «Number|2.5f»
              «Keyword|val» ml «Operator|=» «Keyword|s»«String|""${'"'}multi »«Punctuation|${'$'}»«Variable|x»
            «String|  »«Punctuation|${'$'}{»y«Punctuation|}»«String| end""${'"'}»
            «Punctuation|}»
            """.trimIndent(),
        )
    }
}
