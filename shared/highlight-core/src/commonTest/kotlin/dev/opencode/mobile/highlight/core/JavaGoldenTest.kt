package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Java fixtures: the contract for the C-like lexer in Java. */
class JavaGoldenTest {
    @Test
    fun classesGenericsAndAnnotations() {
        assertGolden(
            "java",
            """
            package com.example.store;

            import java.util.List;
            import static java.util.Objects.requireNonNull;

            /** Javadoc with {@link Store}. */
            @SuppressWarnings("unchecked")
            public final class Store<T extends Comparable<T>> implements AutoCloseable {
                private static final int DEFAULT_CAPACITY = 16;
                private final Map<String, List<T>> items = new HashMap<>();

                public record Entry(String id, int count) {}
                sealed interface Status permits Idle, Busy {}

                @Override
                public synchronized <R> Optional<R> load(final String id) throws IOException {
                    var list = items.getOrDefault(id, List.of());
                    for (int i = 0; i < list.size(); i++) { this.count += i; }
                    if (list instanceof ArrayList<T> a && !a.isEmpty()) return Optional.empty();
                    return super.load(id);
                }
            }
            """.trimIndent(),
            """
            «Keyword|package» «Variable|com»«Punctuation|.»«Property|example»«Punctuation|.»«Property|store»«Punctuation|;»

            «Keyword|import» «Variable|java»«Punctuation|.»«Property|util»«Punctuation|.»«Type|List»«Punctuation|;»
            «Keyword|import» «Keyword|static» «Variable|java»«Punctuation|.»«Property|util»«Punctuation|.»«Type|Objects»«Punctuation|.»«Property|requireNonNull»«Punctuation|;»

            «Comment|/** Javadoc with {@link Store}. */»
            «Attribute|@SuppressWarnings»«Punctuation|(»«String|"unchecked"»«Punctuation|)»
            «Keyword|public» «Keyword|final» «Keyword|class» «Type|Store»«Operator|<»«Type|T» «Keyword|extends» «Type|Comparable»«Operator|<»«Type|T»«Operator|>>» «Keyword|implements» «Type|AutoCloseable» «Punctuation|{»
                «Keyword|private» «Keyword|static» «Keyword|final» «Keyword|int» «Constant|DEFAULT_CAPACITY» «Operator|=» «Number|16»«Punctuation|;»
                «Keyword|private» «Keyword|final» «Type|Map»«Operator|<»«Type|String»«Punctuation|,» «Type|List»«Operator|<»«Type|T»«Operator|>>» «Variable|items» «Operator|=» «Keyword|new» «Type|HashMap»«Operator|<>»«Punctuation|();»

                «Keyword|public» «Keyword|record» «Type|Entry»«Punctuation|(»«Type|String» «Variable|id»«Punctuation|,» «Keyword|int» «Variable|count»«Punctuation|)» «Punctuation|{}»
                «Keyword|sealed» «Keyword|interface» «Type|Status» «Keyword|permits» «Type|Idle»«Punctuation|,» «Type|Busy» «Punctuation|{}»

                «Attribute|@Override»
                «Keyword|public» «Keyword|synchronized» «Operator|<»«Type|R»«Operator|>» «Type|Optional»«Operator|<»«Type|R»«Operator|>» «Function|load»«Punctuation|(»«Keyword|final» «Type|String» «Variable|id»«Punctuation|)» «Keyword|throws» «Type|IOException» «Punctuation|{»
                    «Keyword|var» «Variable|list» «Operator|=» «Variable|items»«Punctuation|.»«Function|getOrDefault»«Punctuation|(»«Variable|id»«Punctuation|,» «Type|List»«Punctuation|.»«Function|of»«Punctuation|());»
                    «Keyword|for» «Punctuation|(»«Keyword|int» «Variable|i» «Operator|=» «Number|0»«Punctuation|;» «Variable|i» «Operator|<» «Variable|list»«Punctuation|.»«Function|size»«Punctuation|();» «Variable|i»«Operator|++»«Punctuation|)» «Punctuation|{» «Keyword|this»«Punctuation|.»«Property|count» «Operator|+=» «Variable|i»«Punctuation|;» «Punctuation|}»
                    «Keyword|if» «Punctuation|(»«Variable|list» «Keyword|instanceof» «Type|ArrayList»«Operator|<»«Type|T»«Operator|>» «Variable|a» «Operator|&&» «Operator|!»«Variable|a»«Punctuation|.»«Function|isEmpty»«Punctuation|())» «Keyword|return» «Type|Optional»«Punctuation|.»«Function|empty»«Punctuation|();»
                    «Keyword|return» «Keyword|super»«Punctuation|.»«Function|load»«Punctuation|(»«Variable|id»«Punctuation|);»
                «Punctuation|}»
            «Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun stringsTextBlocksAndNumbers() {
        assertGolden(
            "java",
            """
            String s = "Tab\t \"q\" \u00e9 \\ end";
            char c = 'x', e = '\n', q = '\'', u = '\u0041';
            String block = ""${'"'}
                Hello, "world" \n
                trailing \
                ""\" still text
                ""${'"'};
            long l = 100L + 0xFF_FFL + 0b1010 + 017 + 1_000;
            double d = 1.5 + 1e-3 + 2.5f + 3d + .5 + 0x1.8p3;
            String open = "never closed
            int next = 1;
            """.trimIndent(),
            """
            «Type|String» «Variable|s» «Operator|=» «String|"Tab»«Constant|\t»«String| »«Constant|\"»«String|q»«Constant|\"»«String| »«Constant|\u00e9»«String| »«Constant|\\»«String| end"»«Punctuation|;»
            «Keyword|char» «Variable|c» «Operator|=» «String|'x'»«Punctuation|,» «Variable|e» «Operator|=» «String|'»«Constant|\n»«String|'»«Punctuation|,» «Variable|q» «Operator|=» «String|'»«Constant|\'»«String|'»«Punctuation|,» «Variable|u» «Operator|=» «String|'»«Constant|\u0041»«String|'»«Punctuation|;»
            «Type|String» «Variable|block» «Operator|=» «String|""${'"'}»
            «String|    Hello, "world" »«Constant|\n»
            «String|    trailing »«Constant|\»
            «String|    ""»«Constant|\"»«String| still text»
            «String|    ""${'"'}»«Punctuation|;»
            «Keyword|long» «Variable|l» «Operator|=» «Number|100L» «Operator|+» «Number|0xFF_FFL» «Operator|+» «Number|0b1010» «Operator|+» «Number|017» «Operator|+» «Number|1_000»«Punctuation|;»
            «Keyword|double» «Variable|d» «Operator|=» «Number|1.5» «Operator|+» «Number|1e-3» «Operator|+» «Number|2.5f» «Operator|+» «Number|3d» «Operator|+» «Number|.5» «Operator|+» «Number|0x1.8p3»«Punctuation|;»
            «Type|String» «Variable|open» «Operator|=» «String|"never closed»
            «Keyword|int» «Variable|next» «Operator|=» «Number|1»«Punctuation|;»
            """.trimIndent(),
        )
    }
}
