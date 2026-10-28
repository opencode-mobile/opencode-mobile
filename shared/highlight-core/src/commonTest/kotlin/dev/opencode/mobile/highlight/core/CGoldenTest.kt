package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written C fixtures: the contract for the C-like lexer in C. */
class CGoldenTest {
    @Test
    fun preprocessorAndDeclarations() {
        assertGolden(
            "c",
            """
            #include <stdio.h>
            #include "store.h"
            #  define MAX(a, b) ((a) > (b) ? (a) : (b))
            #define CAPACITY 16
            #define LOG(fmt, ...) \
                fprintf(stderr, "[log] " fmt "\n", ##__VA_ARGS__); \
                # stringified, not a directive
            int after_macro;
            #if defined(DEBUG) && CAPACITY > 2
            #error "capacity too small"
            #elif !defined(NDEBUG)
            #pragma once
            #endif
            typedef struct node { size_t len; struct node *next; FILE *fp; } node_t;
            enum status { IDLE, BUSY };
            static inline uint32_t hash(const char *restrict key) { return key == NULL ? EOF : 0; }
            """.trimIndent(),
            """
            «Punctuation|#»«Keyword|include» «String|<stdio.h>»
            «Punctuation|#»«Keyword|include» «String|"store.h"»
            «Punctuation|#»  «Keyword|define» «Function|MAX»«Punctuation|(»a«Punctuation|,» b«Punctuation|)» «Punctuation|((»a«Punctuation|)» «Operator|>» «Punctuation|(»b«Punctuation|)» «Operator|?» «Punctuation|(»a«Punctuation|)» «Punctuation|:» «Punctuation|(»b«Punctuation|))»
            «Punctuation|#»«Keyword|define» «Constant|CAPACITY» «Number|16»
            «Punctuation|#»«Keyword|define» «Function|LOG»«Punctuation|(»fmt«Punctuation|,» «Operator|...»«Punctuation|)» \
                «Function|fprintf»«Punctuation|(»stderr«Punctuation|,» «String|"[log] "» fmt «String|"»«Constant|\n»«String|"»«Punctuation|,» «Operator|##»__VA_ARGS__«Punctuation|);» \
                «Operator|#» stringified«Punctuation|,» not a directive
            «Keyword|int» after_macro«Punctuation|;»
            «Punctuation|#»«Keyword|if» «Keyword|defined»«Punctuation|(»«Constant|DEBUG»«Punctuation|)» «Operator|&&» «Constant|CAPACITY» «Operator|>» «Number|2»
            «Punctuation|#»«Keyword|error» «String|"capacity too small"»
            «Punctuation|#»«Keyword|elif» «Operator|!»«Keyword|defined»«Punctuation|(»«Constant|NDEBUG»«Punctuation|)»
            «Punctuation|#»«Keyword|pragma» once
            «Punctuation|#»«Keyword|endif»
            «Keyword|typedef» «Keyword|struct» «Type|node» «Punctuation|{» «Type|size_t» len«Punctuation|;» «Keyword|struct» «Type|node» «Operator|*»next«Punctuation|;» «Type|FILE» «Operator|*»fp«Punctuation|;» «Punctuation|}» «Type|node_t»«Punctuation|;»
            «Keyword|enum» «Type|status» «Punctuation|{» «Constant|IDLE»«Punctuation|,» «Constant|BUSY» «Punctuation|};»
            «Keyword|static» «Keyword|inline» «Type|uint32_t» «Function|hash»«Punctuation|(»«Keyword|const» «Keyword|char» «Operator|*»«Keyword|restrict» key«Punctuation|)» «Punctuation|{» «Keyword|return» key «Operator|==» «Constant|NULL» «Operator|?» «Constant|EOF» «Punctuation|:» «Number|0»«Punctuation|;» «Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun stringsCharactersAndNumbers() {
        assertGolden(
            "h",
            """
            const char *s = "esc \x41 \033 \0 \n %-8.3lf %s %% %zu 100%";
            const char *wide = L"wide" u"16" U"32" u8"utf8";
            const char *cont = "first \
            second";
            const char *not_cont = "even \\";
            char c = 'a', e = '\n', q = '\'', w = L'x', u8c = u8'x';
            long n = 10UL + 0x1Fu + 1.0f + 1e-3L + 0x1.8p3 + 1'000'000 + 0b1010 + 017;
            const char *open = "never closed
            int next = 1;
            /* open comment
               still */ int x;
            """.trimIndent(),
            """
            «Keyword|const» «Keyword|char» «Operator|*»s «Operator|=» «String|"esc »«Constant|\x41»«String| »«Constant|\033»«String| »«Constant|\0»«String| »«Constant|\n»«String| »«Constant|%-8.3lf»«String| »«Constant|%s»«String| »«Constant|%%»«String| »«Constant|%zu»«String| 100%"»«Punctuation|;»
            «Keyword|const» «Keyword|char» «Operator|*»wide «Operator|=» «String|L"wide"» «String|u"16"» «String|U"32"» «String|u8"utf8"»«Punctuation|;»
            «Keyword|const» «Keyword|char» «Operator|*»cont «Operator|=» «String|"first »«Constant|\»
            «String|second"»«Punctuation|;»
            «Keyword|const» «Keyword|char» «Operator|*»not_cont «Operator|=» «String|"even »«Constant|\\»«String|"»«Punctuation|;»
            «Keyword|char» c «Operator|=» «String|'a'»«Punctuation|,» e «Operator|=» «String|'»«Constant|\n»«String|'»«Punctuation|,» q «Operator|=» «String|'»«Constant|\'»«String|'»«Punctuation|,» w «Operator|=» «String|L'x'»«Punctuation|,» u8c «Operator|=» «String|u8'x'»«Punctuation|;»
            «Keyword|long» n «Operator|=» «Number|10UL» «Operator|+» «Number|0x1Fu» «Operator|+» «Number|1.0f» «Operator|+» «Number|1e-3L» «Operator|+» «Number|0x1.8p3» «Operator|+» «Number|1'000'000» «Operator|+» «Number|0b1010» «Operator|+» «Number|017»«Punctuation|;»
            «Keyword|const» «Keyword|char» «Operator|*»open «Operator|=» «String|"never closed»
            «Keyword|int» next «Operator|=» «Number|1»«Punctuation|;»
            «Comment|/* open comment»
            «Comment|   still */» «Keyword|int» x«Punctuation|;»
            """.trimIndent(),
        )
    }
}
