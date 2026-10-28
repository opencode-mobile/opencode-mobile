package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written C# fixtures: the contract for the C-like lexer in C#. */
class CSharpGoldenTest {
    @Test
    fun declarationsAttributesAndDirectives() {
        assertGolden(
            "csharp",
            """
            using System;
            using System.Collections.Generic;
            #nullable enable
            #region Store
            #if DEBUG && !RELEASE
            #define TRACE
            #elif NET8_0
            #endif

            namespace App.Services;

            [Serializable]
            [System.Obsolete("Use Store2")]
            public sealed partial class Store<T> : IStore<T> where T : class
            {
                private readonly Dictionary<string, T> _items = new();
                public int Count { get; private set; }
                public record Entry(string Id, int Count);

                public async Task<T?> LoadAsync(string id, CancellationToken token = default)
                {
                    if (_items.TryGetValue(id, out var item) && item is not null) return item;
                    var @class = nameof(Store<T>);
                    foreach (var (key, value) in _items) { yield return value; }
                    return await base.LoadAsync(id, token);
                }
            }
            #endregion
            """.trimIndent(),
            """
            «Keyword|using» «Type|System»«Punctuation|;»
            «Keyword|using» «Type|System»«Punctuation|.»«Type|Collections»«Punctuation|.»«Type|Generic»«Punctuation|;»
            «Punctuation|#»«Keyword|nullable» enable
            «Punctuation|#»«Keyword|region» Store
            «Punctuation|#»«Keyword|if» «Type|DEBUG» «Operator|&&» «Operator|!»«Type|RELEASE»
            «Punctuation|#»«Keyword|define» «Constant|TRACE»
            «Punctuation|#»«Keyword|elif» «Constant|NET8_0»
            «Punctuation|#»«Keyword|endif»

            «Keyword|namespace» «Type|App»«Punctuation|.»«Type|Services»«Punctuation|;»

            «Punctuation|[»«Attribute|Serializable»«Punctuation|]»
            «Punctuation|[»«Attribute|System.Obsolete»«Punctuation|(»«String|"Use Store2"»«Punctuation|)]»
            «Keyword|public» «Keyword|sealed» «Keyword|partial» «Keyword|class» «Type|Store»«Operator|<»«Type|T»«Operator|>» «Punctuation|:» «Type|IStore»«Operator|<»«Type|T»«Operator|>» «Keyword|where» «Type|T» «Punctuation|:» «Keyword|class»
            «Punctuation|{»
                «Keyword|private» «Keyword|readonly» «Type|Dictionary»«Operator|<»«Keyword|string»«Punctuation|,» «Type|T»«Operator|>» «Variable|_items» «Operator|=» «Keyword|new»«Punctuation|();»
                «Keyword|public» «Keyword|int» «Type|Count» «Punctuation|{» «Keyword|get»«Punctuation|;» «Keyword|private» «Keyword|set»«Punctuation|;» «Punctuation|}»
                «Keyword|public» «Keyword|record» «Type|Entry»«Punctuation|(»«Keyword|string» «Type|Id»«Punctuation|,» «Keyword|int» «Type|Count»«Punctuation|);»

                «Keyword|public» «Keyword|async» «Type|Task»«Operator|<»«Type|T»«Operator|?>» «Function|LoadAsync»«Punctuation|(»«Keyword|string» «Variable|id»«Punctuation|,» «Type|CancellationToken» «Variable|token» «Operator|=» «Keyword|default»«Punctuation|)»
                «Punctuation|{»
                    «Keyword|if» «Punctuation|(»«Variable|_items»«Punctuation|.»«Function|TryGetValue»«Punctuation|(»«Variable|id»«Punctuation|,» «Keyword|out» «Keyword|var» «Variable|item»«Punctuation|)» «Operator|&&» «Variable|item» «Keyword|is» «Keyword|not» «Constant|null»«Punctuation|)» «Keyword|return» «Variable|item»«Punctuation|;»
                    «Keyword|var» «Variable|@class» «Operator|=» «Function|nameof»«Punctuation|(»«Type|Store»«Operator|<»«Type|T»«Operator|>»«Punctuation|);»
                    «Keyword|foreach» «Punctuation|(»«Variable|var» «Punctuation|(»«Variable|key»«Punctuation|,» «Variable|value»«Punctuation|)» «Keyword|in» «Variable|_items»«Punctuation|)» «Punctuation|{» «Keyword|yield» «Keyword|return» «Variable|value»«Punctuation|;» «Punctuation|}»
                    «Keyword|return» «Keyword|await» «Keyword|base»«Punctuation|.»«Function|LoadAsync»«Punctuation|(»«Variable|id»«Punctuation|,» «Variable|token»«Punctuation|);»
                «Punctuation|}»
            «Punctuation|}»
            «Punctuation|#»«Keyword|endregion»
            """.trimIndent(),
        )
    }

    @Test
    fun stringsInterpolationAndRawStrings() {
        assertGolden(
            "cs",
            """
            var s = "Tab\t \"q\" \u00e9 \x41 \U0001F600";
            var path = @"C:\temp\""quoted""${'"'};
            var multi = @"line one
            line two {not interpolated}";
            var text = ${'$'}"{id}: {count,5} {{literal}} {(a ? "x" : "y")}\n";
            var both = ${'$'}@"{id}
            at {path} {{x}}";
            var both2 = @${'$'}"{id} ""q""${'"'};
            var raw = ""${'"'}
                raw "text" { here }
                ""${'"'};
            var raw4 = ""${'"'}"
                has ""${'"'} inside
                ""${'"'}";
            var rawi = ${'$'}${'$'}""${'"'}
                {"name": {{id}}, "n": {{count + 1}}}
                ""${'"'};
            char c = 'x', e = '\n', q = '\'';
            decimal m = 1.5m + 1_000 + 0xFF + 0b1010 + 1e3 + 2f + 3d + 4UL;
            var open = "never closed
            var next = 1;
            """.trimIndent(),
            """
            «Keyword|var» «Variable|s» «Operator|=» «String|"Tab»«Constant|\t»«String| »«Constant|\"»«String|q»«Constant|\"»«String| »«Constant|\u00e9»«String| »«Constant|\x41»«String| »«Constant|\U0001F600»«String|"»«Punctuation|;»
            «Keyword|var» «Variable|path» «Operator|=» «String|@"C:\temp\»«Constant|""»«String|quoted»«Constant|""»«String|"»«Punctuation|;»
            «Keyword|var» «Variable|multi» «Operator|=» «String|@"line one»
            «String|line two {not interpolated}"»«Punctuation|;»
            «Keyword|var» «Variable|text» «Operator|=» «String|${'$'}"»«Punctuation|{»«Variable|id»«Punctuation|}»«String|: »«Punctuation|{»«Variable|count»«Punctuation|,»«Number|5»«Punctuation|}»«String| »«Constant|{{»«String|literal»«Constant|}}»«String| »«Punctuation|{(»«Variable|a» «Operator|?» «String|"x"» «Punctuation|:» «String|"y"»«Punctuation|)}»«Constant|\n»«String|"»«Punctuation|;»
            «Keyword|var» «Variable|both» «Operator|=» «String|${'$'}@"»«Punctuation|{»«Variable|id»«Punctuation|}»
            «String|at »«Punctuation|{»«Variable|path»«Punctuation|}»«String| »«Constant|{{»«String|x»«Constant|}}»«String|"»«Punctuation|;»
            «Keyword|var» «Variable|both2» «Operator|=» «String|@${'$'}"»«Punctuation|{»«Variable|id»«Punctuation|}»«String| »«Constant|""»«String|q»«Constant|""»«String|"»«Punctuation|;»
            «Keyword|var» «Variable|raw» «Operator|=» «String|""${'"'}»
            «String|    raw "text" { here }»
            «String|    ""${'"'}»«Punctuation|;»
            «Keyword|var» «Variable|raw4» «Operator|=» «String|""${'"'}"»
            «String|    has ""${'"'} inside»
            «String|    ""${'"'}"»«Punctuation|;»
            «Keyword|var» «Variable|rawi» «Operator|=» «String|${'$'}${'$'}""${'"'}»
            «String|    {"name": »«Punctuation|{{»«Variable|id»«Punctuation|}}»«String|, "n": »«Punctuation|{{»«Variable|count» «Operator|+» «Number|1»«Punctuation|}}»«String|}»
            «String|    ""${'"'}»«Punctuation|;»
            «Keyword|char» «Variable|c» «Operator|=» «String|'x'»«Punctuation|,» «Variable|e» «Operator|=» «String|'»«Constant|\n»«String|'»«Punctuation|,» «Variable|q» «Operator|=» «String|'»«Constant|\'»«String|'»«Punctuation|;»
            «Keyword|decimal» «Variable|m» «Operator|=» «Number|1.5m» «Operator|+» «Number|1_000» «Operator|+» «Number|0xFF» «Operator|+» «Number|0b1010» «Operator|+» «Number|1e3» «Operator|+» «Number|2f» «Operator|+» «Number|3d» «Operator|+» «Number|4UL»«Punctuation|;»
            «Keyword|var» «Variable|open» «Operator|=» «String|"never closed»
            «Keyword|var» «Variable|next» «Operator|=» «Number|1»«Punctuation|;»
            """.trimIndent(),
        )
    }
}
