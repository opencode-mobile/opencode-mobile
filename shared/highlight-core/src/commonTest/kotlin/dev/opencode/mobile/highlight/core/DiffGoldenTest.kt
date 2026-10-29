package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written diff fixtures: the contract for the diff lexer. */
class DiffGoldenTest {
    @Test
    fun gitDiffWithCountedHunks() {
        // The hunk counts tell the removed line "--- old comment" from a file header.
        assertGolden(
            "diff",
            """
            diff --git a/q.sql b/q.sql
            index 3b18e51..a9c2f04 100644
            --- a/q.sql
            +++ b/q.sql
            @@ -1,4 +1,4 @@ CREATE TABLE t
             select 1;
            --- old comment
            +-- new comment

             end;
            \ No newline at end of file
            diff --git a/b.txt b/b.txt
            new file mode 100644
            --- /dev/null
            +++ b/b.txt
            @@ -0,0 +1 @@
            +hello
            """.trimIndent(),
            """
            «Warning|diff --git a/q.sql b/q.sql»
            «Warning|index 3b18e51..a9c2f04 100644»
            «Warning|--- a/q.sql»
            «Warning|+++ b/q.sql»
            «Keyword|@@ -1,4 +1,4 @@» «Function|CREATE TABLE t»
             select 1;
            «Deleted|--- old comment»
            «Inserted|+-- new comment»

             end;
            «Comment|\ No newline at end of file»
            «Warning|diff --git a/b.txt b/b.txt»
            «Warning|new file mode 100644»
            «Warning|--- /dev/null»
            «Warning|+++ b/b.txt»
            «Keyword|@@ -0,0 +1 @@»
            «Inserted|+hello»
            """.trimIndent(),
        )
    }

    @Test
    fun hunksWithoutCountsAsModelsWriteThem() {
        assertGolden(
            "patch",
            """
            --- a/app.ts
            +++ b/app.ts
            @@
            -const a = 1
            +const a = 2
             context
            --- a/next.ts
            @@ ... @@ fn
            -x
            """.trimIndent(),
            """
            «Warning|--- a/app.ts»
            «Warning|+++ b/app.ts»
            «Keyword|@@»
            «Deleted|-const a = 1»
            «Inserted|+const a = 2»
             context
            «Warning|--- a/next.ts»
            «Keyword|@@ ... @@» «Function|fn»
            «Deleted|-x»
            """.trimIndent(),
        )
    }

    @Test
    fun linesWithoutHeaders() {
        assertGolden(
            "diff",
            "+a\n-b\n c\n> d\n< e\n! f\nplain\n>no",
            "«Inserted|+a»\n«Deleted|-b»\n c\n«Inserted|> d»\n«Deleted|< e»\n«Warning|! f»\nplain\n>no",
        )
    }

    @Test
    fun otherHeadersCombinedAndOversizedHunks() {
        assertGolden(
            "diff",
            """
            similarity index 90%
            rename from a.txt
            rename to b.txt
            Binary files a.png and b.png differ
            Index: foo.c
            ===================================================================
            *** foo.c.orig
            @@@ -1,2 -1,2 +1,3 @@@ main
              ctx
            @@ -1 +1 @@
            -a
            +b
            @@ -1,40000 +1,40000 @@
            --- big hunk, loose
            """.trimIndent(),
            """
            «Warning|similarity index 90%»
            «Warning|rename from a.txt»
            «Warning|rename to b.txt»
            «Warning|Binary files a.png and b.png differ»
            «Warning|Index: foo.c»
            «Warning|===================================================================»
            «Warning|*** foo.c.orig»
            «Keyword|@@@ -1,2 -1,2 +1,3 @@@» «Function|main»
              ctx
            «Keyword|@@ -1 +1 @@»
            «Deleted|-a»
            «Inserted|+b»
            «Keyword|@@ -1,40000 +1,40000 @@»
            «Warning|--- big hunk, loose»
            """.trimIndent(),
        )
    }

    @Test
    fun crlfTerminatorsStayOutsideSpans() {
        assertGolden(
            "diff",
            "--- a\r\n+++ b\r\n@@ -1 +1 @@\r\n-x\r\n+y\r\n",
            "«Warning|--- a»␍\n«Warning|+++ b»␍\n«Keyword|@@ -1 +1 @@»␍\n«Deleted|-x»␍\n«Inserted|+y»␍\n",
        )
    }

    @Test
    fun unicodeAndTheLineCap() {
        assertGolden(
            "diff",
            "+😀 \uD83D x​\n-" + "y".repeat(30),
            "«Inserted|+😀 ⟨D83D⟩ x​»\n«Deleted|-yyyyyyyyy»" + "y".repeat(21),
            HighlightLimits(maxLineLength = 10),
        )
    }
}
