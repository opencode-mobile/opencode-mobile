package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Crystal fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class CrystalGoldenTest {
    @Test
    fun classesMethodsAndInterpolation() {
        assertGolden(
            "crystal",
            goldenLines(
                "# Sessions",
                "require \"json\"",
                "@[JSON::Serializable::Options(emit_nulls: true)]",
                "class Session < Base",
                "  include JSON::Serializable",
                "  MAX_SIZE = 10",
                "  property name : String = \"x\"",
                "  def initialize(@name : String, @count = 0)",
                "  end",
                "  def label(count : Int32) : String",
                "    \"sessions: #{count} #{@name.upcase + \"#{1}\"}\\n\"",
                "  end",
                "  def self.build(**kw) : self",
                "    new(**kw)",
                "  end",
                "  def valid? : Bool = @name.size > 0",
                "  def ==(other : self) = name == other.name",
                "end",
            ),
            goldenLines(
                "«Comment|# Sessions»",
                "«Keyword|require» «String|\"json\"»",
                "«Punctuation|@[»«Attribute|JSON»«Punctuation|::»«Attribute|Serializable»«Punctuation|::»«Attribute|Options»«Punctuation|(»«Constant|emit_nulls:» «Constant|true»«Punctuation|)]»",
                "«Keyword|class» «Type|Session» «Operator|<» «Type|Base»",
                "  «Keyword|include» «Constant|JSON»«Punctuation|::»«Type|Serializable»",
                "  «Constant|MAX_SIZE» «Operator|=» «Number|10»",
                "  «Keyword|property» name «Punctuation|:» «Type|String» «Operator|=» «String|\"x\"»",
                "  «Keyword|def» «Function|initialize»«Punctuation|(»«Variable|@name» «Punctuation|:» «Type|String»«Punctuation|,» «Variable|@count» «Operator|=» «Number|0»«Punctuation|)»",
                "  «Keyword|end»",
                "  «Keyword|def» «Function|label»«Punctuation|(»«Variable|count» «Punctuation|:» «Type|Int32»«Punctuation|)» «Punctuation|:» «Type|String»",
                "    «String|\"sessions: »«Punctuation|#{»count«Punctuation|}»«String| »«Punctuation|#{»«Variable|@name»«Punctuation|.»«Property|upcase» «Operator|+» «String|\"#{1}\"»«Punctuation|}»«Constant|\\n»«String|\"»",
                "  «Keyword|end»",
                "  «Keyword|def» «Variable|self»«Punctuation|.»«Function|build»«Punctuation|(»«Operator|**»«Variable|kw»«Punctuation|)» «Punctuation|:» «Variable|self»",
                "    «Function|new»«Punctuation|(»«Operator|**»kw«Punctuation|)»",
                "  «Keyword|end»",
                "  «Keyword|def» «Function|valid?» «Punctuation|:» «Type|Bool» «Operator|=» «Variable|@name»«Punctuation|.»«Property|size» «Operator|>» «Number|0»",
                "  «Keyword|def» «Function|==»«Punctuation|(»«Variable|other» «Punctuation|:» «Variable|self»«Punctuation|)» «Operator|=» name «Operator|==» other«Punctuation|.»«Property|name»",
                "«Keyword|end»",
            ),
        )
    }

    @Test
    fun literalsSymbolsAndBlocks() {
        assertGolden(
            "crystal",
            goldenLines(
                "x = %w(a b c) + %i[x y] + %q{raw #{no}} + %Q<i #{y}> + %(p (n) #{z})",
                "re = /\\d+#{z}/i + %r{a/b}m",
                "c = 'a' + '\\n' + '\\u{1F600}' + :sym + :\"quoted\" + :+ + :valid?",
                "h = {key: 1, \"str\" => 2, sym: :value}",
                "list.each { |item, i| puts item } && list.map do |x| x * 2 end",
                "n = 1_i64 + 2u8 + 1.5f32 + 0x1F + 0b101 + 1e3",
                "t = x.as?(String) || (\$~ && @@count && nil)",
            ),
            goldenLines(
                "x «Operator|=» «String|%w(a b c)» «Operator|+» «Constant|%i[x y]» «Operator|+» «String|%q{raw #{no}}» «Operator|+» «String|%Q<i »«Punctuation|#{»y«Punctuation|}»«String|>» «Operator|+» «String|%(p (n) »«Punctuation|#{»z«Punctuation|}»«String|)»",
                "re «Operator|=» «String|/»«Constant|\\d»«String|+»«Punctuation|#{»z«Punctuation|}»«String|/i» «Operator|+» «String|%r{a/b}m»",
                "c «Operator|=» «String|'a'» «Operator|+» «String|'\\n'» «Operator|+» «String|'\\u{1F600}'» «Operator|+» «Constant|:sym» «Operator|+» «Constant|:\"quoted\"» «Operator|+» «Constant|:+» «Operator|+» «Constant|:valid?»",
                "h «Operator|=» «Punctuation|{»«Constant|key:» «Number|1»«Punctuation|,» «String|\"str\"» «Operator|=>» «Number|2»«Punctuation|,» «Constant|sym:» «Constant|:value»«Punctuation|}»",
                "list«Punctuation|.»«Property|each» «Punctuation|{» «Punctuation||»«Variable|item»«Punctuation|,» «Variable|i»«Punctuation||» «Function|puts» item «Punctuation|}» «Operator|&&» list«Punctuation|.»«Property|map» «Keyword|do» «Punctuation||»«Variable|x»«Punctuation||» x «Operator|*» «Number|2» «Keyword|end»",
                "n «Operator|=» «Number|1_i64» «Operator|+» «Number|2u8» «Operator|+» «Number|1.5f32» «Operator|+» «Number|0x1F» «Operator|+» «Number|0b101» «Operator|+» «Number|1e3»",
                "t «Operator|=» x«Punctuation|.»«Function|as?»«Punctuation|(»«Type|String»«Punctuation|)» «Operator|||» «Punctuation|(»«Variable|\$~» «Operator|&&» «Variable|@@count» «Operator|&&» «Constant|nil»«Punctuation|)»",
            ),
        )
    }

    @Test
    fun heredocsAndMacros() {
        assertGolden(
            "crystal",
            goldenLines(
                "text = <<-EOS",
                "  Hello #{name}",
                "    indented \\n",
                "  EOS",
                "raw = <<-'RAW'",
                "  #{not} interpolated",
                "  RAW",
                "{% if flag?(:linux) %}",
                "  puts {{ @type.name.stringify }}",
                "{% end %}",
                "macro getter2(name)",
                "  def {{name.id}}; @{{name.id}}; end",
                "end",
            ),
            goldenLines(
                "text «Operator|=» «String|<<-EOS»",
                "«String|  Hello »«Punctuation|#{»name«Punctuation|}»",
                "«String|    indented »«Constant|\\n»",
                "  «String|EOS»",
                "raw «Operator|=» «String|<<-'RAW'»",
                "«String|  #{not} interpolated»",
                "  «String|RAW»",
                "«Punctuation|{%» «Keyword|if» «Function|flag?»«Punctuation|(»«Constant|:linux»«Punctuation|)» «Punctuation|%}»",
                "  «Function|puts» «Punctuation|{{» «Variable|@type»«Punctuation|.»«Property|name»«Punctuation|.»«Property|stringify» «Punctuation|}}»",
                "«Punctuation|{%» «Keyword|end» «Punctuation|%}»",
                "«Keyword|macro» «Function|getter2»«Punctuation|(»«Variable|name»«Punctuation|)»",
                "  «Keyword|def» «Punctuation|{{»name«Punctuation|.»«Property|id»«Punctuation|}};» «Variable|@»«Punctuation|{{»name«Punctuation|.»«Property|id»«Punctuation|}};» «Keyword|end»",
                "«Keyword|end»",
            ),
        )
    }

    @Test
    fun multiLineStringsAndNesting() {
        assertGolden(
            "crystal",
            goldenLines(
                "s = \"line one",
                "line #{two(",
                "  3)} three\"",
                "w = %w(",
                "  a (b)",
                ")",
                "done = 1",
            ),
            goldenLines(
                "s «Operator|=» «String|\"line one»",
                "«String|line »«Punctuation|#{»«Function|two»«Punctuation|(»",
                "  «Number|3»«Punctuation|)}»«String| three\"»",
                "w «Operator|=» «String|%w(»",
                "«String|  a (b)»",
                "«String|)»",
                "done «Operator|=» «Number|1»",
            ),
        )
    }

    @Test
    fun unterminatedConstructs() {
        assertGolden(
            "crystal",
            goldenLines(
                "s = \"open #{x",
                "y = 'z'",
                "w = %w(never",
                "z = 1",
            ),
            goldenLines(
                "s «Operator|=» «String|\"open »«Punctuation|#{»x",
                "y «Operator|=» «String|'z'»",
                "w «Operator|=» «String|%w(never»",
                "«String|z = 1»",
            ),
        )
    }

    @Test
    fun theLineCapCutsInsideAHeredocOpener() {
        assertGolden(
            "crystal",
            goldenLines(
                "x = <<-EOSXYZ",
                "body",
                "EOS",
            ),
            goldenLines(
                "x «Operator|=» «String|<<-EO»SXYZ",
                "«String|body»",
                "«String|EOS»",
            ),
            HighlightLimits(maxLineLength = 9),
        )
    }

    @Test
    fun crlfAndSurrogates() {
        assertGolden(
            "crystal",
            "puts \"😀 #{x}\"\r\n# \uD83D\r\nx = :\uDE00a\r\n",
            goldenLines(
                "«Function|puts» «String|\"😀 »«Punctuation|#{»x«Punctuation|}»«String|\"»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "x «Operator|=» «Punctuation|:»⟨DE00⟩a␍",
                "",
            ),
        )
    }
}
