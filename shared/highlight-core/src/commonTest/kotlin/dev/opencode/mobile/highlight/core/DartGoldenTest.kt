package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Dart fixtures: the contract for the C-like lexer in Dart. */
class DartGoldenTest {
    @Test
    fun classesAsyncAndInterpolation() {
        assertGolden(
            "dart",
            """
            #!/usr/bin/env dart
            import 'package:flutter/material.dart' show Widget, State;

            /// A counter.
            @immutable
            class Counter extends StatelessWidget with Mixin implements Comparable<Counter> {
              static const int maxCount = 10;
              final String label;
              late final List<int>? items = [1, 2, 0xFF, 1.5e3];
              const Counter({super.key, required this.label});

              Future<void> load() async {
                final name = 'user ${'$'}label ${'$'}{items?.length ?? 0} \${'$'}x \n';
                var raw = r'C:\path ${'$'}notInterpolated';
                var multi = ""${'"'}
              Hello ${'$'}name, ${'$'}{greet("x")}
            ""${'"'};
                await for (final v in stream) { print(v); }
                print(#symbol.part);
                return null;
              }
              int get count => _count;
              set count(int value) { _count = value; }
            }
            /* outer /* nested */ still comment */
            var r3 = r'''raw
            ${'$'}x''';
            """.trimIndent(),
            """
            «Comment|#!/usr/bin/env dart»
            «Keyword|import» «String|'package:flutter/material.dart'» «Keyword|show» «Type|Widget»«Punctuation|,» «Type|State»«Punctuation|;»

            «Comment|/// A counter.»
            «Attribute|@immutable»
            «Keyword|class» «Type|Counter» «Keyword|extends» «Type|StatelessWidget» «Keyword|with» «Type|Mixin» «Keyword|implements» «Type|Comparable»«Operator|<»«Type|Counter»«Operator|>» «Punctuation|{»
              «Keyword|static» «Keyword|const» «Type|int» maxCount «Operator|=» «Number|10»«Punctuation|;»
              «Keyword|final» «Type|String» label«Punctuation|;»
              «Keyword|late» «Keyword|final» «Type|List»«Operator|<»«Type|int»«Operator|>?» items «Operator|=» «Punctuation|[»«Number|1»«Punctuation|,» «Number|2»«Punctuation|,» «Number|0xFF»«Punctuation|,» «Number|1.5e3»«Punctuation|];»
              «Keyword|const» «Function|Counter»«Punctuation|({»«Keyword|super»«Punctuation|.»«Property|key»«Punctuation|,» «Keyword|required» «Keyword|this»«Punctuation|.»«Property|label»«Punctuation|});»

              «Type|Future»«Operator|<»«Type|void»«Operator|>» «Function|load»«Punctuation|()» «Keyword|async» «Punctuation|{»
                «Keyword|final» name «Operator|=» «String|'user ${'$'}»«Variable|label»«String| »«Punctuation|${'$'}{»items«Operator|?»«Punctuation|.»«Property|length» «Operator|??» «Number|0»«Punctuation|}»«String| »«Constant|\${'$'}»«String|x »«Constant|\n»«String|'»«Punctuation|;»
                «Keyword|var» raw «Operator|=» «String|r'C:\path ${'$'}notInterpolated'»«Punctuation|;»
                «Keyword|var» multi «Operator|=» «String|""${'"'}»
            «String|  Hello ${'$'}»«Variable|name»«String|, »«Punctuation|${'$'}{»«Function|greet»«Punctuation|(»«String|"x"»«Punctuation|)}»
            «String|""${'"'}»«Punctuation|;»
                «Keyword|await» «Keyword|for» «Punctuation|(»«Keyword|final» v «Keyword|in» stream«Punctuation|)» «Punctuation|{» «Function|print»«Punctuation|(»v«Punctuation|);» «Punctuation|}»
                «Function|print»«Punctuation|(»«Constant|#symbol.part»«Punctuation|);»
                «Keyword|return» «Constant|null»«Punctuation|;»
              «Punctuation|}»
              «Type|int» «Keyword|get» count «Operator|=>» _count«Punctuation|;»
              «Keyword|set» «Function|count»«Punctuation|(»«Type|int» value«Punctuation|)» «Punctuation|{» _count «Operator|=» value«Punctuation|;» «Punctuation|}»
            «Punctuation|}»
            «Comment|/* outer /* nested */ still comment */»
            «Keyword|var» r3 «Operator|=» «String|r'''raw»
            «String|${'$'}x'''»«Punctuation|;»
            """.trimIndent(),
        )
    }

    @Test
    fun nestedRawAndTripleQuotedStrings() {
        assertGolden(
            "dart",
            """
            var nested = 'a ${'$'}{'b ${'$'}{c} d'} e';
            var unicode = "\u{1F600} \x41 \${'$'} ${'$'}_private";
            var adjacent = 'one' "two" r"three\n";
            var quotes = '''it's "fine" ''' + ""${'"'}say 'hi' ${'$'}x""${'"'};
            var map = {'key': [1, 2], #sym: null};
            """.trimIndent(),
            """
            «Keyword|var» nested «Operator|=» «String|'a »«Punctuation|${'$'}{»«String|'b »«Punctuation|${'$'}{»c«Punctuation|}»«String| d'»«Punctuation|}»«String| e'»«Punctuation|;»
            «Keyword|var» unicode «Operator|=» «String|"»«Constant|\u{1F600}»«String| »«Constant|\x41»«String| »«Constant|\${'$'}»«String| ${'$'}»«Variable|_private»«String|"»«Punctuation|;»
            «Keyword|var» adjacent «Operator|=» «String|'one'» «String|"two"» «String|r"three\n"»«Punctuation|;»
            «Keyword|var» quotes «Operator|=» «String|'''it's "fine" '''» «Operator|+» «String|""${'"'}say 'hi' ${'$'}»«Variable|x»«String|""${'"'}»«Punctuation|;»
            «Keyword|var» map «Operator|=» «Punctuation|{»«String|'key'»«Punctuation|:» «Punctuation|[»«Number|1»«Punctuation|,» «Number|2»«Punctuation|],» «Constant|#sym»«Punctuation|:» «Constant|null»«Punctuation|};»
            """.trimIndent(),
        )
    }
}
