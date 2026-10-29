package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Make fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class MakeGoldenTest {
    @Test
    fun assignmentsAndFunctions() {
        assertGolden(
            "make",
            goldenLines(
                "# Makefile",
                ".PHONY: help build test",
                "CC := gcc",
                "CFLAGS ?= -O2 -Wall",
                "OBJS = \$(patsubst %.c,%.o,\$(wildcard src/*.c))",
                "LIBS += -lm # math",
                "NOW != date",
                "ESC = \$\$HOME \${VAR} \$@",
            ),
            goldenLines(
                "«Comment|# Makefile»",
                "«Function|.PHONY»«Punctuation|:» help build test",
                "«Variable|CC» «Operator|:=» gcc",
                "«Variable|CFLAGS» «Operator|?=» -O2 -Wall",
                "«Variable|OBJS» «Operator|=» «Punctuation|\$(»«Function|patsubst» %.c«Punctuation|,»%.o«Punctuation|,\$(»«Function|wildcard» src/*.c«Punctuation|))»",
                "«Variable|LIBS» «Operator|+=» -lm «Comment|# math»",
                "«Variable|NOW» «Operator|!=» date",
                "«Variable|ESC» «Operator|=» «Constant|\$\$»HOME «Variable|\${VAR}» «Variable|\$@»",
            ),
        )
    }

    @Test
    fun rulesAndRecipes() {
        assertGolden(
            "makefile",
            goldenLines(
                "build: \$(OBJS) | dirs",
                "\t@echo \"Building \$@ from \$^\"",
                "\t-rm -f *.tmp && \$(CC) \$(CFLAGS) -o \$@ \$^ \$(LIBS)",
                "\tfor f in \$(SRC:.c=.o); do echo \$\$f; done",
                "",
                "%.o: %.c",
                "\t\$(CC) -c \$< -o \$@ # shell comment",
                "app: CFLAGS += -DAPP",
                "clean: ; rm -rf build",
            ),
            goldenLines(
                "«Function|build»«Punctuation|:» «Variable|\$(OBJS)» «Operator||» dirs",
                "␉«Keyword|@»«Function|echo» «String|\"Building »«Variable|\$@»«String| from »«Variable|\$^»«String|\"»",
                "␉«Keyword|-»«Function|rm» «Constant|-f» *.tmp «Operator|&&» «Variable|\$(CC)» «Variable|\$(CFLAGS)» «Constant|-o» «Variable|\$@» «Variable|\$^» «Variable|\$(LIBS)»",
                "␉«Keyword|for» «Variable|f» «Keyword|in» «Variable|\$(SRC»:.c=.o«Punctuation|)»«Operator|;» «Keyword|do» «Function|echo» «Variable|\$\$f»«Operator|;» «Keyword|done»",
                "",
                "«Function|%.o»«Punctuation|:» %.c",
                "␉«Variable|\$(CC)» «Constant|-c» «Variable|\$<» «Constant|-o» «Variable|\$@» «Comment|# shell comment»",
                "«Function|app»«Punctuation|:» «Variable|CFLAGS» «Operator|+=» -DAPP",
                "«Function|clean»«Punctuation|:» «Punctuation|;» «Function|rm» «Constant|-rf» build",
            ),
        )
    }

    @Test
    fun directivesAndDefine() {
        assertGolden(
            "make",
            goldenLines(
                "ifeq (\$(OS),Windows_NT)",
                "  EXT := .exe",
                "else ifdef DEBUG",
                "  CFLAGS += -g",
                "endif",
                "define HELP",
                "Usage: make \$(TARGET)",
                "define INNER",
                "endef",
                "still body",
                "endef",
                "export PATH",
                "override X = 1",
                "-include deps.mk",
            ),
            goldenLines(
                "«Keyword|ifeq» «Punctuation|(»«Variable|\$(OS)»«Punctuation|,»Windows_NT«Punctuation|)»",
                "  «Variable|EXT» «Operator|:=» .exe",
                "«Keyword|else» «Keyword|ifdef» «Variable|DEBUG»",
                "  «Variable|CFLAGS» «Operator|+=» -g",
                "«Keyword|endif»",
                "«Keyword|define» «Variable|HELP»",
                "Usage: make «Variable|\$(TARGET)»",
                "«Keyword|define» INNER",
                "«Keyword|endef»",
                "still body",
                "«Keyword|endef»",
                "«Keyword|export» «Variable|PATH»",
                "«Keyword|override» «Variable|X» «Operator|=» 1",
                "«Keyword|-include» deps.mk",
            ),
        )
    }

    @Test
    fun continuedLines() {
        assertGolden(
            "make",
            goldenLines(
                "X = \$(call f,\\",
                "  a,b) c",
                "# comment \\",
                "continued",
                "all:",
                "\techo \"a \\",
                " b\" && \\",
                "\tls",
                "SRCS = a.c \\",
                "       b.c",
                "Y = \$(Z",
            ),
            goldenLines(
                "«Variable|X» «Operator|=» «Punctuation|\$(»«Function|call» f«Punctuation|,»\\",
                "  a«Punctuation|,»b«Punctuation|)» c",
                "«Comment|# comment \\»",
                "«Comment|continued»",
                "«Function|all»«Punctuation|:»",
                "␉«Function|echo» «String|\"a \\»",
                "«String| b\"» «Operator|&&» \\",
                "␉«Function|ls»",
                "«Variable|SRCS» «Operator|=» a.c \\",
                "       b.c",
                "«Variable|Y» «Operator|=» «Variable|\$(Z»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "make",
            "all: a\r\n\t@echo \"😀 \$@\"\r\n# \uD83D\r\nX = \uD83D \$(Y)\r\n",
            goldenLines(
                "«Function|all»«Punctuation|:» a␍",
                "␉«Keyword|@»«Function|echo» «String|\"😀 »«Variable|\$@»«String|\"»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "«Variable|X» «Operator|=» ⟨D83D⟩ «Variable|\$(Y)»␍",
                "",
            ),
        )
    }

    @Test
    fun longLinesAreCapped() {
        assertGolden(
            "make",
            goldenLines(
                "X = \$(call abc,def)",
                "Y = 1",
            ),
            goldenLines(
                "«Variable|X» «Operator|=» «Punctuation|\$(»«Function|call» abc,def)",
                "«Variable|Y» «Operator|=» 1",
            ),
            HighlightLimits(maxLineLength = 12),
        )
    }
}
