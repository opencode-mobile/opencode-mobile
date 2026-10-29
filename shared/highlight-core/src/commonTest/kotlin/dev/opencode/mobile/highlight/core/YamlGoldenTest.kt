package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written YAML fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class YamlGoldenTest {
    @Test
    fun mappingsSequencesAndScalars() {
        assertGolden(
            "yaml",
            goldenLines(
                "name: opencode",
                "version: 1.2",
                "on:",
                "  push:",
                "    branches: [main, \"release/*\"]",
                "steps:",
                "  - run: ./gradlew test",
                "  - name: Build # comment",
                "    with: {fetch-depth: 0, lfs: true, ref: ~}",
                "    if: \${{ github.event_name == 'push' }}",
            ),
            goldenLines(
                "«Property|name»«Punctuation|:» «String|opencode»",
                "«Property|version»«Punctuation|:» «Number|1.2»",
                "«Property|on»«Punctuation|:»",
                "  «Property|push»«Punctuation|:»",
                "    «Property|branches»«Punctuation|:» «Punctuation|[»«String|main»«Punctuation|,» «String|\"release/*\"»«Punctuation|]»",
                "«Property|steps»«Punctuation|:»",
                "  «Punctuation|-» «Property|run»«Punctuation|:» «String|./gradlew test»",
                "  «Punctuation|-» «Property|name»«Punctuation|:» «String|Build» «Comment|# comment»",
                "    «Property|with»«Punctuation|:» «Punctuation|{»«Property|fetch-depth»«Punctuation|:» «Number|0»«Punctuation|,» «Property|lfs»«Punctuation|:» «Constant|true»«Punctuation|,» «Property|ref»«Punctuation|:» «Constant|~»«Punctuation|}»",
                "    «Property|if»«Punctuation|:» «String|\${{ github.event_name == 'push' }}»",
            ),
        )
    }

    @Test
    fun scalarTypes() {
        assertGolden(
            "yml",
            goldenLines(
                "int: 42",
                "neg: -1.5e3",
                "hex: 0x1F",
                "oct: 0o17",
                "inf: -.inf",
                "nan: .NaN",
                "nil: null",
                "tilde: ~",
                "yes: yes",
                "off: Off",
                "word: true story",
                "date: 2001-12-14",
                "quoted: \"a\\tb \\\"c\\\" \\u00e9\"",
                "single: 'it''s'",
                "url: http://example.com:8080/path",
            ),
            goldenLines(
                "«Property|int»«Punctuation|:» «Number|42»",
                "«Property|neg»«Punctuation|:» «Number|-1.5e3»",
                "«Property|hex»«Punctuation|:» «Number|0x1F»",
                "«Property|oct»«Punctuation|:» «Number|0o17»",
                "«Property|inf»«Punctuation|:» «Number|-.inf»",
                "«Property|nan»«Punctuation|:» «Number|.NaN»",
                "«Property|nil»«Punctuation|:» «Constant|null»",
                "«Property|tilde»«Punctuation|:» «Constant|~»",
                "«Property|yes»«Punctuation|:» «Constant|yes»",
                "«Property|off»«Punctuation|:» «Constant|Off»",
                "«Property|word»«Punctuation|:» «String|true story»",
                "«Property|date»«Punctuation|:» «String|2001-12-14»",
                "«Property|quoted»«Punctuation|:» «String|\"a»«Constant|\\t»«String|b »«Constant|\\\"»«String|c»«Constant|\\\"»«String| »«Constant|\\u00e9»«String|\"»",
                "«Property|single»«Punctuation|:» «String|'it»«Constant|''»«String|s'»",
                "«Property|url»«Punctuation|:» «String|http://example.com:8080/path»",
            ),
        )
    }

    @Test
    fun anchorsAliasesTagsAndKeys() {
        assertGolden(
            "yaml",
            goldenLines(
                "%YAML 1.2",
                "---",
                "base: &base",
                "  a: 1",
                "derived:",
                "  <<: *base",
                "  tag: !!str 123",
                "  custom: !Ref vpc",
                "\"quoted key\": v",
                "'single key': w",
                "? complex",
                ": value",
                "- - nested",
                "  - list",
                "...",
            ),
            goldenLines(
                "«Keyword|%YAML 1.2»",
                "«Keyword|---»",
                "«Property|base»«Punctuation|:» «Variable|&base»",
                "  «Property|a»«Punctuation|:» «Number|1»",
                "«Property|derived»«Punctuation|:»",
                "  «Property|<<»«Punctuation|:» «Variable|*base»",
                "  «Property|tag»«Punctuation|:» «Type|!!str» «Number|123»",
                "  «Property|custom»«Punctuation|:» «Type|!Ref» «String|vpc»",
                "«Property|\"quoted key\"»«Punctuation|:» «String|v»",
                "«Property|'single key'»«Punctuation|:» «String|w»",
                "«Punctuation|?» «String|complex»",
                "«Punctuation|:» «String|value»",
                "«Punctuation|-» «Punctuation|-» «String|nested»",
                "  «Punctuation|-» «String|list»",
                "«Keyword|...»",
            ),
        )
    }

    @Test
    fun blockScalarsEndByIndentation() {
        assertGolden(
            "yaml",
            goldenLines(
                "script: |",
                "  echo one",
                "    indented",
                "",
                "  echo two",
                "folded: >-",
                "  text",
                "  more",
                "keep: |+2",
                "    exact",
                "next: value",
                "list:",
                "  - |",
                "    item text",
                "  - plain",
            ),
            goldenLines(
                "«Property|script»«Punctuation|:» «Keyword||»",
                "  «String|echo one»",
                "  «String|  indented»",
                "",
                "  «String|echo two»",
                "«Property|folded»«Punctuation|:» «Keyword|>-»",
                "  «String|text»",
                "  «String|more»",
                "«Property|keep»«Punctuation|:» «Keyword||+2»",
                "  «String|  exact»",
                "«Property|next»«Punctuation|:» «String|value»",
                "«Property|list»«Punctuation|:»",
                "  «Punctuation|-» «Keyword||»",
                "    «String|item text»",
                "  «Punctuation|-» «String|plain»",
            ),
        )
    }

    @Test
    fun blockScalarAtRootAndAfterMarkers() {
        assertGolden(
            "yaml",
            goldenLines(
                "--- |",
                "  root text",
                "---",
                "key: >",
                " # not a comment",
                " text",
            ),
            goldenLines(
                "«Keyword|---» «Keyword||»",
                "  «String|root text»",
                "«Keyword|---»",
                "«Property|key»«Punctuation|:» «Keyword|>»",
                " «String|# not a comment»",
                " «String|text»",
            ),
        )
    }

    @Test
    fun multiLineQuotedScalars() {
        assertGolden(
            "yaml",
            goldenLines(
                "desc: \"first",
                "  second \\\"q\\\" end\"",
                "other: 'one",
                "  two'",
                "after: 1",
            ),
            goldenLines(
                "«Property|desc»«Punctuation|:» «String|\"first»",
                "«String|  second »«Constant|\\\"»«String|q»«Constant|\\\"»«String| end\"»",
                "«Property|other»«Punctuation|:» «String|'one»",
                "«String|  two'»",
                "«Property|after»«Punctuation|:» «Number|1»",
            ),
        )
    }

    @Test
    fun flowCollectionsAcrossLines() {
        assertGolden(
            "yaml",
            goldenLines(
                "matrix: {",
                "  os: [ubuntu, macos],",
                "  node: [18, 20]",
                "}",
                "next: x",
            ),
            goldenLines(
                "«Property|matrix»«Punctuation|:» «Punctuation|{»",
                "  «Property|os»«Punctuation|:» «Punctuation|[»«String|ubuntu»«Punctuation|,» «String|macos»«Punctuation|],»",
                "  «Property|node»«Punctuation|:» «Punctuation|[»«Number|18»«Punctuation|,» «Number|20»«Punctuation|]»",
                "«Punctuation|}»",
                "«Property|next»«Punctuation|:» «String|x»",
            ),
        )
    }

    @Test
    fun unterminatedAndOddInput() {
        assertGolden(
            "yaml",
            goldenLines(
                "key: \"open",
                "still: open",
            ),
            goldenLines(
                "«Property|key»«Punctuation|:» «String|\"open»",
                "«String|still: open»",
            ),
        )
    }

    @Test
    fun crlfTabsSurrogatesAndEmoji() {
        assertGolden(
            "yaml",
            "a: 😀\r\nb: \uD83D x\r\nc: |\r\n  é\uDE00\r\nd: 1\r\n",
            goldenLines(
                "«Property|a»«Punctuation|:» «String|😀»␍",
                "«Property|b»«Punctuation|:» «String|⟨D83D⟩ x»␍",
                "«Property|c»«Punctuation|:» «Keyword||»␍",
                "  «String|é⟨DE00⟩»␍",
                "«Property|d»«Punctuation|:» «Number|1»␍",
                "",
            ),
        )
    }
}
