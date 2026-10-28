package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written shell (bash, zsh, sh) fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class ShellGoldenTest {
    @Test
    fun scriptsWithExpansionsAndOptions() {
        assertGolden(
            "shellscript",
            goldenLines(
                "#!/usr/bin/env bash",
                "set -euo pipefail",
                "echo \"building \$APP_NAME\" >&2",
                "export PATH=\"\$HOME/bin:\$PATH\" DEBUG=1",
                "cp -r --verbose \"\$src\" \"\${dest:-/tmp}\" 2>&1 | tee -a build.log >/dev/null",
                "[[ -n \"\$1\" && \$# -gt 2 ]] && exit 1",
            ),
            goldenLines(
                "«Comment|#!/usr/bin/env bash»",
                "«Function|set» «Constant|-euo» pipefail",
                "«Function|echo» «String|\"building »«Variable|\$APP_NAME»«String|\"» «Operator|>&»«Number|2»",
                "«Keyword|export» «Variable|PATH»«Operator|=»«String|\"»«Variable|\$HOME»«String|/bin:»«Variable|\$PATH»«String|\"» «Variable|DEBUG»«Operator|=»1",
                "«Function|cp» «Constant|-r» «Constant|--verbose» «String|\"»«Variable|\$src»«String|\"» «String|\"»«Variable|\${dest:-/tmp}»«String|\"» «Number|2»«Operator|>&»«Number|1» «Operator||» «Function|tee» «Constant|-a» build.log «Operator|>»/dev/null",
                "«Keyword|[[» «Constant|-n» «String|\"»«Variable|\$1»«String|\"» «Operator|&&» «Variable|\$#» «Constant|-gt» «Number|2» «Keyword|]]» «Operator|&&» «Keyword|exit» «Number|1»",
            ),
        )
    }

    @Test
    fun controlFlowKeywordsAndFunctions() {
        assertGolden(
            "bash",
            goldenLines(
                "for f in *.txt; do",
                "  if [[ -f \"\$f\" ]]; then rm \"\$f\"; elif true; then :; else break; fi",
                "done",
                "while read -r line; do echo \"\$line\"; done < input",
                "case \"\$1\" in",
                "  start) run_server ;;",
                "  stop|halt) kill \"\$pid\" ;;",
                "  *) usage ;;",
                "esac",
                "function greet() { local name=\$1; printf '%s\\n' \"hi \$name\"; return 0; }",
                "deploy () {",
                "  ENV=prod make deploy",
                "}",
            ),
            goldenLines(
                "«Keyword|for» «Variable|f» «Keyword|in» *.txt«Operator|;» «Keyword|do»",
                "  «Keyword|if» «Keyword|[[» «Constant|-f» «String|\"»«Variable|\$f»«String|\"» «Keyword|]]»«Operator|;» «Keyword|then» «Function|rm» «String|\"»«Variable|\$f»«String|\"»«Operator|;» «Keyword|elif» «Function|true»«Operator|;» «Keyword|then» «Function|:»«Operator|;» «Keyword|else» «Keyword|break»«Operator|;» «Keyword|fi»",
                "«Keyword|done»",
                "«Keyword|while» «Function|read» «Constant|-r» line«Operator|;» «Keyword|do» «Function|echo» «String|\"»«Variable|\$line»«String|\"»«Operator|;» «Keyword|done» «Operator|<» input",
                "«Keyword|case» «String|\"»«Variable|\$1»«String|\"» «Keyword|in»",
                "  start«Punctuation|)» «Function|run_server» «Operator|;;»",
                "  «Function|stop»«Operator||»halt«Punctuation|)» «Function|kill» «String|\"»«Variable|\$pid»«String|\"» «Operator|;;»",
                "  *«Punctuation|)» «Function|usage» «Operator|;;»",
                "«Keyword|esac»",
                "«Keyword|function» «Function|greet»«Punctuation|()» «Punctuation|{» «Keyword|local» «Variable|name»«Operator|=»«Variable|\$1»«Operator|;» «Function|printf» «String|'%s\\n'» «String|\"hi »«Variable|\$name»«String|\"»«Operator|;» «Keyword|return» «Number|0»«Operator|;» «Punctuation|}»",
                "«Function|deploy» «Punctuation|()» «Punctuation|{»",
                "  «Variable|ENV»«Operator|=»prod «Function|make» deploy",
                "«Punctuation|}»",
            ),
        )
    }

    @Test
    fun substitutionsNestInsideQuotes() {
        assertGolden(
            "sh",
            goldenLines(
                "dir=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"",
                "now=`date +%s`",
                "sum=\$((a + 2 * (b - 1))); (( i++ ))",
                "echo \"user: \${USER} home: \$HOME pid: \$\$ args: \$@ \\\$literal \\\"q\\\"\"",
                "echo \$'tab\\tquote\\'' 'single \$none' \$\"locale\"",
            ),
            goldenLines(
                "«Variable|dir»«Operator|=»«String|\"»«Punctuation|\$(»«Function|cd» «String|\"»«Punctuation|\$(»«Function|dirname» «String|\"\$0\"»«Punctuation|)»«String|\"» «Operator|&&» «Function|pwd»«Punctuation|)»«String|\"»",
                "«Variable|now»«Operator|=»«Punctuation|`»«Function|date» +%s«Punctuation|`»",
                "«Variable|sum»«Operator|=»«Punctuation|\$((»a «Operator|+» «Number|2» «Operator|*» «Punctuation|(»b «Operator|-» «Number|1»«Punctuation|)))»«Operator|;» «Punctuation|((» i«Operator|++» «Punctuation|))»",
                "«Function|echo» «String|\"user: »«Variable|\${USER}»«String| home: »«Variable|\$HOME»«String| pid: »«Variable|\$\$»«String| args: »«Variable|\$@»«String| »«Constant|\\\$»«String|literal »«Constant|\\\"»«String|q»«Constant|\\\"»«String|\"»",
                "«Function|echo» «String|\$'tab»«Constant|\\t»«String|quote»«Constant|\\'»«String|'» «String|'single \$none'» «String|\$\"locale\"»",
            ),
        )
    }

    @Test
    fun multiLineQuotesAndSubstitutions() {
        assertGolden(
            "zsh",
            goldenLines(
                "msg=\"first line",
                "second \$x line\"",
                "out=\$(git log \\",
                "  --oneline | head -1)",
                "echo 'a",
                "b' done",
            ),
            goldenLines(
                "«Variable|msg»«Operator|=»«String|\"first line»",
                "«String|second »«Variable|\$x»«String| line\"»",
                "«Variable|out»«Operator|=»«Punctuation|\$(»«Function|git» log \\",
                "  «Constant|--oneline» «Operator||» «Function|head» «Constant|-1»«Punctuation|)»",
                "«Function|echo» «String|'a»",
                "«String|b'» «Keyword|done»",
            ),
        )
    }

    @Test
    fun heredocsWithAndWithoutExpansion() {
        assertGolden(
            "bash",
            goldenLines(
                "cat <<EOF > out.txt",
                "Hello \$USER, today is \$(date +%A) and \${HOME}.",
                "Escaped \\\$HOME and \\`cmd\\`.",
                "EOF",
                "cat <<'RAW'",
                "No \$expansion here",
                "RAW",
                "\tcat <<-  \"END\" | sort",
                "\tindented \$x",
                "\tEND",
                "echo after",
            ),
            goldenLines(
                "«Function|cat» «Operator|<<»«Keyword|EOF» «Operator|>» out.txt",
                "«String|Hello »«Variable|\$USER»«String|, today is »«Punctuation|\$(»«Function|date» +%A«Punctuation|)»«String| and »«Variable|\${HOME}»«String|.»",
                "«String|Escaped »«Constant|\\\$»«String|HOME and »«Constant|\\`»«String|cmd»«Constant|\\`»«String|.»",
                "«Keyword|EOF»",
                "«Function|cat» «Operator|<<»«Keyword|'RAW'»",
                "«String|No \$expansion here»",
                "«Keyword|RAW»",
                "␉«Function|cat» «Operator|<<-»  «Keyword|\"END\"» «Operator||» «Function|sort»",
                "«String|␉indented \$x»",
                "␉«Keyword|END»",
                "«Function|echo» after",
            ),
        )
    }

    @Test
    fun heredocInsideCommandSubstitution() {
        assertGolden(
            "bash",
            goldenLines(
                "body=\$(cat <<EOF",
                "line one \$x",
                "EOF",
                ")",
                "echo \"\$body\"",
            ),
            goldenLines(
                "«Variable|body»«Operator|=»«Punctuation|\$(»«Function|cat» «Operator|<<»«Keyword|EOF»",
                "«String|line one »«Variable|\$x»",
                "«Keyword|EOF»",
                "«Punctuation|)»",
                "«Function|echo» «String|\"»«Variable|\$body»«String|\"»",
            ),
        )
    }

    @Test
    fun hereStringsAndRedirectionsAreNotHeredocs() {
        assertGolden(
            "bash",
            goldenLines(
                "grep x <<< \"\$text\"",
                "echo \$(( 1 << 4 ))",
                "cmd 2>/dev/null >>log &> both",
            ),
            goldenLines(
                "«Function|grep» x «Operator|<<<» «String|\"»«Variable|\$text»«String|\"»",
                "«Function|echo» «Punctuation|\$((» «Number|1» «Operator|<<» «Number|4» «Punctuation|))»",
                "«Function|cmd» «Number|2»«Operator|>»/dev/null «Operator|>>»log «Operator|&>» both",
            ),
        )
    }

    @Test
    fun consoleSnippetsWithAPrompt() {
        assertGolden(
            "console",
            goldenLines(
                "\$ npm install --save-dev typescript",
                "\$ ls -la",
                "total 0",
            ),
            goldenLines(
                "«Punctuation|\$» «Function|npm» install «Constant|--save-dev» typescript",
                "«Punctuation|\$» «Function|ls» «Constant|-la»",
                "«Function|total» «Number|0»",
            ),
        )
    }

    @Test
    fun unterminatedConstructsAtTheEnd() {
        assertGolden(
            "sh",
            goldenLines(
                "echo \"open \$x",
            ),
            goldenLines(
                "«Function|echo» «String|\"open »«Variable|\$x»",
            ),
        )
    }

    @Test
    fun unterminatedHeredocColorsTheRest() {
        assertGolden(
            "sh",
            goldenLines(
                "cat <<EOF",
                "body \$x",
                "no end",
            ),
            goldenLines(
                "«Function|cat» «Operator|<<»«Keyword|EOF»",
                "«String|body »«Variable|\$x»",
                "«String|no end»",
            ),
        )
    }

    @Test
    fun crlfTabsSurrogatesAndEmoji() {
        assertGolden(
            "sh",
            "echo \"😀 ok\"\r\n\tx=1\r\n# note \uD83D\r\necho \uDE00 'é'\r\n",
            goldenLines(
                "«Function|echo» «String|\"😀 ok\"»␍",
                "␉«Variable|x»«Operator|=»1␍",
                "«Comment|# note ⟨D83D⟩»␍",
                "«Function|echo» ⟨DE00⟩ «String|'é'»␍",
                "",
            ),
        )
    }

    @Test
    fun lineContinuationKeepsArguments() {
        assertGolden(
            "sh",
            goldenLines(
                "apt-get install -y \\",
                "  curl \\",
                "  git && echo done",
            ),
            goldenLines(
                "«Function|apt-get» install «Constant|-y» \\",
                "  curl \\",
                "  git «Operator|&&» «Function|echo» done",
            ),
        )
    }

    @Test
    fun longLinesAreCapped() {
        assertGolden(
            "sh",
            goldenLines(
                "echo \"abc \$x def ghi\"",
                "echo next",
            ),
            goldenLines(
                "«Function|echo» «String|\"abc »«Variable|\$x» def ghi\"",
                "«String|echo next»",
            ),
            HighlightLimits(maxLineLength = 12),
        )
    }
}
