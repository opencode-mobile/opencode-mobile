package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Pascal (Object Pascal, Delphi) fixtures: the contract for its lexer. One escaped
 * string per line keeps quotes, `$`, CRLF, tabs, and lone surrogates exact.
 */
class PascalGoldenTest {
    @Test
    fun programStructureAndDeclarations() {
        assertGolden(
            "pascal",
            goldenLines(
                "program Sessions;",
                "uses SysUtils, Classes;",
                "type",
                "  TForm1 = class(TForm)",
                "  private",
                "    FCount: Integer;",
                "  public",
                "    property Count: Integer read FCount write SetCount default 0;",
                "    procedure Click(Sender: TObject); override;",
                "  end;",
                "procedure TForm1.Click(Sender: TObject);",
                "begin",
                "  Count := Count + 1;",
                "  Writeln('sessions: ', Count);",
                "  if Assigned(Sender) and not Done then Exit;",
                "  raise EConvertError.Create('bad');",
                "end;",
            ),
            goldenLines(
                "«Keyword|program» Sessions«Punctuation|;»",
                "«Keyword|uses» SysUtils«Punctuation|,» Classes«Punctuation|;»",
                "«Keyword|type»",
                "  «Type|TForm1» «Operator|=» «Keyword|class»«Punctuation|(»«Type|TForm»«Punctuation|)»",
                "  «Keyword|private»",
                "    FCount«Punctuation|:» «Type|Integer»«Punctuation|;»",
                "  «Keyword|public»",
                "    «Keyword|property» Count«Punctuation|:» «Type|Integer» «Keyword|read» FCount «Keyword|write» SetCount «Keyword|default» «Number|0»«Punctuation|;»",
                "    «Keyword|procedure» «Function|Click»«Punctuation|(»Sender«Punctuation|:» «Type|TObject»«Punctuation|);» «Keyword|override»«Punctuation|;»",
                "  «Keyword|end»«Punctuation|;»",
                "«Keyword|procedure» «Type|TForm1»«Punctuation|.»«Function|Click»«Punctuation|(»Sender«Punctuation|:» «Type|TObject»«Punctuation|);»",
                "«Keyword|begin»",
                "  Count «Operator|:=» Count «Operator|+» «Number|1»«Punctuation|;»",
                "  «Function|Writeln»«Punctuation|(»«String|'sessions: '»«Punctuation|,» Count«Punctuation|);»",
                "  «Keyword|if» «Function|Assigned»«Punctuation|(»Sender«Punctuation|)» «Keyword|and» «Keyword|not» Done «Keyword|then» Exit«Punctuation|;»",
                "  «Keyword|raise» «Type|EConvertError»«Punctuation|.»«Function|Create»«Punctuation|(»«String|'bad'»«Punctuation|);»",
                "«Keyword|end»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun commentsAndDirectives() {
        assertGolden(
            "pascal",
            goldenLines(
                "{\$mode objfpc}{\$H+}",
                "{ brace comment } x := 1; // line comment",
                "(* paren comment with { inside *) y := 2;",
                "(*\$I include.inc*)",
                "{ open brace comment",
                "  continues (* here",
                "} z := 3; (* open paren",
                "   comment *) w := 4;",
            ),
            goldenLines(
                "«Keyword|{\$mode objfpc}{\$H+}»",
                "«Comment|{ brace comment }» x «Operator|:=» «Number|1»«Punctuation|;» «Comment|// line comment»",
                "«Comment|(* paren comment with { inside *)» y «Operator|:=» «Number|2»«Punctuation|;»",
                "«Keyword|(*\$I include.inc*)»",
                "«Comment|{ open brace comment»",
                "«Comment|  continues (* here»",
                "«Comment|}» z «Operator|:=» «Number|3»«Punctuation|;» «Comment|(* open paren»",
                "«Comment|   comment *)» w «Operator|:=» «Number|4»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun stringsCharacterCodesAndNumbers() {
        assertGolden(
            "pascal",
            goldenLines(
                "S := 'it''s' + #13#10 + #\$0A + 'a'#9'b';",
                "N := \$FF + &17 + %1010 + 1.5e3 + 2E-2;",
                "for I := 1 to 10 do R := A[1..2];",
                "P := @X; Q := P^; &begin := nil;",
            ),
            goldenLines(
                "S «Operator|:=» «String|'it»«Constant|''»«String|s'» «Operator|+» «Constant|#13#10» «Operator|+» «Constant|#\$0A» «Operator|+» «String|'a'»«Constant|#9»«String|'b'»«Punctuation|;»",
                "N «Operator|:=» «Number|\$FF» «Operator|+» «Number|&17» «Operator|+» «Number|%1010» «Operator|+» «Number|1.5e3» «Operator|+» «Number|2E-2»«Punctuation|;»",
                "«Keyword|for» I «Operator|:=» «Number|1» «Keyword|to» «Number|10» «Keyword|do» R «Operator|:=» A«Punctuation|[»«Number|1»«Operator|..»«Number|2»«Punctuation|];»",
                "P «Operator|:=» «Operator|@»X«Punctuation|;» Q «Operator|:=» P«Operator|^»«Punctuation|;» &begin «Operator|:=» «Constant|nil»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun multiLineStrings() {
        assertGolden(
            "pascal",
            goldenLines(
                "const Text = '''",
                "  first line",
                "  it's here",
                "  ''';",
                "S := '''';",
            ),
            goldenLines(
                "«Keyword|const» Text «Operator|=» «String|'''»",
                "«String|  first line»",
                "«String|  it's here»",
                "«String|  '''»«Punctuation|;»",
                "S «Operator|:=» «String|'»«Constant|''»«String|'»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun unterminatedStrings() {
        assertGolden(
            "pascal",
            goldenLines(
                "S := 'open",
                "T := 1;",
            ),
            goldenLines(
                "S «Operator|:=» «String|'open»",
                "T «Operator|:=» «Number|1»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndSurrogates() {
        assertGolden(
            "pascal",
            "S := '😀'; // é\r\nT := \uD83D;\r\n{ c\r\n}\r\n",
            goldenLines(
                "S «Operator|:=» «String|'😀'»«Punctuation|;» «Comment|// é»␍",
                "T «Operator|:=» ⟨D83D⟩«Punctuation|;»␍",
                "«Comment|{ c»␍",
                "«Comment|}»␍",
                "",
            ),
        )
    }

    @Test
    fun aCommentCutByTheCapStaysOpen() {
        assertGolden(
            "pascal",
            goldenLines(
                "x := 1; { abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij }",
                "still comment",
                "} y := 2;",
            ),
            goldenLines(
                "x «Operator|:=» «Number|1»«Punctuation|;» «Comment|{ abcdefghijabcd»efghijabcdefghijabcdefghijabcdefghijabcdefghij }",
                "«Comment|still comment»",
                "«Comment|}» y «Operator|:=» «Number|2»«Punctuation|;»",
            ),
            HighlightLimits(maxLineLength = 24),
        )
    }
}
