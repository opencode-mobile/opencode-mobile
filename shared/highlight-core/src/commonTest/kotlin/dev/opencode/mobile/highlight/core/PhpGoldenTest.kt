package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written PHP fixtures, PHP islands in HTML included: the contract for the PHP lexer. */
class PhpGoldenTest {
    @Test
    fun aSnippetWithoutAnOpenerIsPhp() {
        assertGolden(
            "php",
            """
            ${'$'}x = TRUE && null; # comment
            echo Foo::class;
            """.trimIndent(),
            """
            «Variable|${'$'}x» «Operator|=» «Constant|TRUE» «Operator|&&» «Constant|null»«Punctuation|;» «Comment|# comment»
            «Keyword|echo» «Type|Foo»«Operator|::»«Keyword|class»«Punctuation|;»
            """.trimIndent(),
        )
    }

    @Test
    fun classesFunctionsAndInterpolation() {
        assertGolden(
            "php",
            """
            <?php

            namespace App\Models;

            #[Attribute]
            final class User extends Model implements HasName
            {
                const MAX_AGE = 120;
                private ?string ${'$'}name = null;

                public function label(int ${'$'}count): string {
                    return "sessions: {${'$'}count} and ${'$'}this->name {${'$'}this->items['a']} ${'$'}arr[0] \n";
                }
            }
            """.trimIndent(),
            """
            «Punctuation|<?»«Keyword|php»

            «Keyword|namespace» «Type|App»«Punctuation|\»«Type|Models»«Punctuation|;»

            «Punctuation|#[»«Type|Attribute»«Punctuation|]»
            «Keyword|final» «Keyword|class» «Type|User» «Keyword|extends» «Type|Model» «Keyword|implements» «Type|HasName»
            «Punctuation|{»
                «Keyword|const» «Constant|MAX_AGE» «Operator|=» «Number|120»«Punctuation|;»
                «Keyword|private» «Operator|?»«Keyword|string» «Variable|${'$'}name» «Operator|=» «Constant|null»«Punctuation|;»

                «Keyword|public» «Keyword|function» «Function|label»«Punctuation|(»«Keyword|int» «Variable|${'$'}count»«Punctuation|)»«Operator|:» «Keyword|string» «Punctuation|{»
                    «Keyword|return» «String|"sessions: »«Punctuation|{»«Variable|${'$'}count»«Punctuation|}»«String| and »«Variable|${'$'}this»«Operator|->»«Property|name»«String| »«Punctuation|{»«Variable|${'$'}this»«Operator|->»«Property|items»«Punctuation|[»«String|'a'»«Punctuation|]}»«String| »«Variable|${'$'}arr»«Punctuation|[»«Number|0»«Punctuation|]»«String| »«Constant|\n»«String|"»«Punctuation|;»
                «Punctuation|}»
            «Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun heredocNowdocAndMultiLineStrings() {
        assertGolden(
            "php",
            """
            <?php
            ${'$'}sql = <<<SQL
              SELECT * FROM users WHERE id = ${'$'}id
              SQL;
            ${'$'}raw = <<<'EOT'
            raw ${'$'}not
            EOT;
            ${'$'}s = 'it\'s
            two lines';
            echo Str::upper(${'$'}s) . PHP_EOL; // done ?> after
            """.trimIndent(),
            """
            «Punctuation|<?»«Keyword|php»
            «Variable|${'$'}sql» «Operator|=» «String|<<<SQL»
            «String|  SELECT * FROM users WHERE id = »«Variable|${'$'}id»
              «String|SQL»«Punctuation|;»
            «Variable|${'$'}raw» «Operator|=» «String|<<<'EOT'»
            «String|raw ${'$'}not»
            «String|EOT»«Punctuation|;»
            «Variable|${'$'}s» «Operator|=» «String|'it»«Constant|\'»«String|s»
            «String|two lines'»«Punctuation|;»
            «Keyword|echo» «Type|Str»«Operator|::»«Function|upper»«Punctuation|(»«Variable|${'$'}s»«Punctuation|)» «Operator|.» «Constant|PHP_EOL»«Punctuation|;» «Comment|// done »«Punctuation|?>» after
            """.trimIndent(),
        )
    }

    @Test
    fun islandsInHtmlAttributesAndScript() {
        assertGolden(
            "php",
            """
            <ul class="<?= ${'$'}cls ?>">
            <?php foreach (${'$'}items as ${'$'}item): ?>
              <li><?= htmlspecialchars(${'$'}item) ?></li>
            <?php endforeach; ?>
            </ul>
            <script>
              var data = <?= json_encode(${'$'}data) ?>;
              /* comment <?php echo "?>"; ?> still */
            </script>
            """.trimIndent(),
            """
            «Punctuation|<»«Tag|ul» «Attribute|class»«Punctuation|=»«String|"»«Punctuation|<?=» «Variable|${'$'}cls» «Punctuation|?>»«String|"»«Punctuation|>»
            «Punctuation|<?»«Keyword|php» «Keyword|foreach» «Punctuation|(»«Variable|${'$'}items» «Keyword|as» «Variable|${'$'}item»«Punctuation|)»«Operator|:» «Punctuation|?>»
              «Punctuation|<»«Tag|li»«Punctuation|><?=» «Function|htmlspecialchars»«Punctuation|(»«Variable|${'$'}item»«Punctuation|)» «Punctuation|?></»«Tag|li»«Punctuation|>»
            «Punctuation|<?»«Keyword|php» «Keyword|endforeach»«Punctuation|;» «Punctuation|?>»
            «Punctuation|</»«Tag|ul»«Punctuation|>»
            «Punctuation|<»«Tag|script»«Punctuation|>»
              «Keyword|var» «Variable|data» «Operator|=» «Punctuation|<?=» «Function|json_encode»«Punctuation|(»«Variable|${'$'}data»«Punctuation|)» «Punctuation|?>;»
              «Comment|/* comment »«Punctuation|<?»«Keyword|php» «Keyword|echo» «String|"?>"»«Punctuation|;» «Punctuation|?>»«Comment| still */»
            «Punctuation|</»«Tag|script»«Punctuation|>»
            """.trimIndent(),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "php",
            "<?php\r\n\$a = \"x\r\ny \$b\";\r\n?>\r\n<b>\uD83D\uDE00\uD83D</b>",
            "«Punctuation|<?»«Keyword|php»␍\n«Variable|\$a» «Operator|=» «String|\"x»␍\n«String|y »«Variable|\$b»«String|\"»«Punctuation|;»␍\n«Punctuation|?>»␍\n«Punctuation|<»«Tag|b»«Punctuation|>»\uD83D\uDE00⟨D83D⟩«Punctuation|</»«Tag|b»«Punctuation|>»",
        )
    }
}
