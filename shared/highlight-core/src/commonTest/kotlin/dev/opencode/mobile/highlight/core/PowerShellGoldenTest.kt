package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written PowerShell fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class PowerShellGoldenTest {
    @Test
    fun functionsParametersAndCmdlets() {
        assertGolden(
            "powershell",
            goldenLines(
                "function Get-SessionLabel([int]\$Count) {",
                "    # build a label",
                "    return \"sessions: \$Count\"",
                "}",
                "[CmdletBinding()]",
                "param([Parameter(Mandatory)][string[]]\$Names, \$Path = \$env:TEMP)",
                "\$items = Get-ChildItem -Path \$Path -Recurse | Where-Object { \$_.Length -gt 1kb -and \$_.Name -like '*.log' }",
                "function global:Invoke-It { [Math]::Max(1, 2).ToString() }",
                "class Point { [int]\$X }",
            ),
            goldenLines(
                "«Keyword|function» «Function|Get-SessionLabel»«Punctuation|([»«Type|int»«Punctuation|]»«Variable|\$Count»«Punctuation|)» «Punctuation|{»",
                "    «Comment|# build a label»",
                "    «Keyword|return» «String|\"sessions: »«Variable|\$Count»«String|\"»",
                "«Punctuation|}»",
                "«Punctuation|[»«Type|CmdletBinding»«Punctuation|()]»",
                "«Keyword|param»«Punctuation|([»«Type|Parameter»«Punctuation|(»Mandatory«Punctuation|)][»«Type|string[]»«Punctuation|]»«Variable|\$Names»«Punctuation|,» «Variable|\$Path» «Operator|=» «Variable|\$env:TEMP»«Punctuation|)»",
                "«Variable|\$items» «Operator|=» «Function|Get-ChildItem» «Constant|-Path» «Variable|\$Path» «Constant|-Recurse» «Operator||» «Function|Where-Object» «Punctuation|{» «Variable|\$_»«Punctuation|.»«Property|Length» «Operator|-gt» «Number|1kb» «Operator|-and» «Variable|\$_»«Punctuation|.»«Property|Name» «Operator|-like» «String|'*.log'» «Punctuation|}»",
                "«Keyword|function» global«Punctuation|:»«Function|Invoke-It» «Punctuation|{» «Punctuation|[»«Type|Math»«Punctuation|]::»«Function|Max»«Punctuation|(»«Number|1»«Punctuation|,» «Number|2»«Punctuation|).»«Function|ToString»«Punctuation|()» «Punctuation|}»",
                "«Keyword|class» «Type|Point» «Punctuation|{» «Punctuation|[»«Type|int»«Punctuation|]»«Variable|\$X» «Punctuation|}»",
            ),
        )
    }

    @Test
    fun stringsSubexpressionsAndHereStrings() {
        assertGolden(
            "ps1",
            goldenLines(
                "Write-Host \"Found \$(\$items.Count) items in \${Path}\" -ForegroundColor Green",
                "\$h = @{ Name = 'it''s'; Count = 0x1F; List = @(1, 2.5e3, 10L) }",
                "throw [System.IO.FileNotFoundException]::new(\"missing `\"file`\" \"\"x\"\"\")",
                "\$text = @\"",
                "Hello \$name, today is \$(Get-Date -Format \"yyyy\")",
                "\"@",
                "\$raw = @'",
                "No \$expansion here",
                "'@",
                "\$s = \"multi \$(",
                "\"nested\") line",
                "end \$x\"",
            ),
            goldenLines(
                "«Function|Write-Host» «String|\"Found »«Punctuation|\$(»«Variable|\$items»«Punctuation|.»«Property|Count»«Punctuation|)»«String| items in »«Variable|\${Path}»«String|\"» «Constant|-ForegroundColor» Green",
                "«Variable|\$h» «Operator|=» «Punctuation|@{» Name «Operator|=» «String|'it»«Constant|''»«String|s'»«Punctuation|;» Count «Operator|=» «Number|0x1F»«Punctuation|;» List «Operator|=» «Punctuation|@(»«Number|1»«Punctuation|,» «Number|2.5e3»«Punctuation|,» «Number|10L»«Punctuation|)» «Punctuation|}»",
                "«Keyword|throw» «Punctuation|[»«Type|System.IO.FileNotFoundException»«Punctuation|]::»«Function|new»«Punctuation|(»«String|\"missing »«Constant|`\"»«String|file»«Constant|`\"»«String| »«Constant|\"\"»«String|x»«Constant|\"\"»«String|\"»«Punctuation|)»",
                "«Variable|\$text» «Operator|=» «String|@\"»",
                "«String|Hello »«Variable|\$name»«String|, today is »«Punctuation|\$(»«Function|Get-Date» «Constant|-Format» «String|\"yyyy\"»«Punctuation|)»",
                "«String|\"@»",
                "«Variable|\$raw» «Operator|=» «String|@'»",
                "«String|No \$expansion here»",
                "«String|'@»",
                "«Variable|\$s» «Operator|=» «String|\"multi »«Punctuation|\$(»",
                "«String|\"nested\"»«Punctuation|)»«String| line»",
                "«String|end »«Variable|\$x»«String|\"»",
            ),
        )
    }

    @Test
    fun commentsAndCaseInsensitiveKeywords() {
        assertGolden(
            "pwsh",
            goldenLines(
                "<# block",
                "comment #> \$done = \$true",
                "IF (\$x -CEQ 1) { RETURN } ElseIf (\$y -iLike \"*\") { \$Env:Path }",
                "\$a = 5 - -3 -bor 1; \$b = \$null -eq \$c",
            ),
            goldenLines(
                "«Comment|<# block»",
                "«Comment|comment #>» «Variable|\$done» «Operator|=» «Constant|\$true»",
                "«Keyword|IF» «Punctuation|(»«Variable|\$x» «Operator|-CEQ» «Number|1»«Punctuation|)» «Punctuation|{» «Keyword|RETURN» «Punctuation|}» «Keyword|ElseIf» «Punctuation|(»«Variable|\$y» «Operator|-iLike» «String|\"*\"»«Punctuation|)» «Punctuation|{» «Variable|\$Env:Path» «Punctuation|}»",
                "«Variable|\$a» «Operator|=» «Number|5» «Operator|-» «Operator|-»«Number|3» «Operator|-bor» «Number|1»«Punctuation|;» «Variable|\$b» «Operator|=» «Constant|\$null» «Operator|-eq» «Variable|\$c»",
            ),
        )
    }

    @Test
    fun unterminatedConstructs() {
        assertGolden(
            "powershell",
            goldenLines(
                "\$s = 'open",
                "still' + \"x\"",
                "\$x = @\"",
                "body \$y",
                "not closed",
            ),
            goldenLines(
                "«Variable|\$s» «Operator|=» «String|'open»",
                "«String|still'» «Operator|+» «String|\"x\"»",
                "«Variable|\$x» «Operator|=» «String|@\"»",
                "«String|body »«Variable|\$y»",
                "«String|not closed»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "powershell",
            "Write-Output \"😀 \$x\"\r\n# \uD83D\r\n\$s = \uD83D'open\r\n<# unterminated\r\n",
            goldenLines(
                "«Function|Write-Output» «String|\"😀 »«Variable|\$x»«String|\"»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "«Variable|\$s» «Operator|=» ⟨D83D⟩«String|'open»␍",
                "«String|<# unterminated»␍",
                "",
            ),
        )
    }

    @Test
    fun longLinesAreCapped() {
        assertGolden(
            "powershell",
            goldenLines(
                "\$s = 'abc def'",
                "Write-Host \$s",
            ),
            goldenLines(
                "«Variable|\$s» «Operator|=» «String|'abc de»f'",
                "«String|Write-Host \$»s",
            ),
            HighlightLimits(maxLineLength = 12),
        )
    }
}
