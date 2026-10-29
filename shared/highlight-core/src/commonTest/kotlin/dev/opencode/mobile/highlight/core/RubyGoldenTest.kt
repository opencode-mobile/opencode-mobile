package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Ruby fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class RubyGoldenTest {
    @Test
    fun classesMethodsAndInterpolation() {
        assertGolden(
            "ruby",
            goldenLines(
                "# frozen_string_literal: true",
                "require \"json\"",
                "class Session < Base",
                "  attr_reader :id, :title",
                "  MAX_SIZE = 10",
                "  def label(count, opts = {})",
                "    \"sessions: #{count} #{opts[:x] + \"#{1}\"} #@id\"",
                "  end",
                "",
                "  def self.build(**kw) = new(**kw)",
                "  def ==(other) = id == other.id",
                "  def name=(value); @name = value; end",
                "end",
            ),
            goldenLines(
                "«Comment|# frozen_string_literal: true»",
                "«Keyword|require» «String|\"json\"»",
                "«Keyword|class» «Type|Session» «Operator|<» «Type|Base»",
                "  «Keyword|attr_reader» «Constant|:id»«Punctuation|,» «Constant|:title»",
                "  «Constant|MAX_SIZE» «Operator|=» «Number|10»",
                "  «Keyword|def» «Function|label»«Punctuation|(»«Variable|count»«Punctuation|,» «Variable|opts» «Operator|=» «Punctuation|{})»",
                "    «String|\"sessions: »«Punctuation|#{»count«Punctuation|}»«String| »«Punctuation|#{»opts«Punctuation|[»«Constant|:x»«Punctuation|]» «Operator|+» «String|\"»«Punctuation|#{»«Number|1»«Punctuation|}»«String|\"»«Punctuation|}»«String| »«Punctuation|#»«Variable|@id»«String|\"»",
                "  «Keyword|end»",
                "",
                "  «Keyword|def» «Variable|self»«Punctuation|.»«Function|build»«Punctuation|(»«Operator|**»«Variable|kw»«Punctuation|)» «Operator|=» «Function|new»«Punctuation|(»«Operator|**»kw«Punctuation|)»",
                "  «Keyword|def» «Function|==»«Punctuation|(»«Variable|other»«Punctuation|)» «Operator|=» id «Operator|==» other«Punctuation|.»«Property|id»",
                "  «Keyword|def» «Function|name=»«Punctuation|(»«Variable|value»«Punctuation|);» «Variable|@name» «Operator|=» value«Punctuation|;» «Keyword|end»",
                "«Keyword|end»",
            ),
        )
    }

    @Test
    fun blocksSymbolsAndLabels() {
        assertGolden(
            "rb",
            goldenLines(
                "items.map { |item, i| item.name.upcase if item&.valid? }",
                "list.each do |x| puts x end",
                "hash = { key: 1, \"str\" => 2, sym: :value, op: :+, q: :\"quoted\" }",
                "return nil unless defined?(foo) && \$stdout && @@count",
            ),
            goldenLines(
                "items«Punctuation|.»«Property|map» «Punctuation|{» «Punctuation||»«Variable|item»«Punctuation|,» «Variable|i»«Punctuation||» item«Punctuation|.»«Property|name»«Punctuation|.»«Property|upcase» «Keyword|if» item«Punctuation|&.»«Function|valid?» «Punctuation|}»",
                "list«Punctuation|.»«Property|each» «Keyword|do» «Punctuation||»«Variable|x»«Punctuation||» «Function|puts» x «Keyword|end»",
                "hash «Operator|=» «Punctuation|{» «Constant|key:» «Number|1»«Punctuation|,» «String|\"str\"» «Operator|=>» «Number|2»«Punctuation|,» «Constant|sym:» «Constant|:value»«Punctuation|,» «Constant|op:» «Constant|:+»«Punctuation|,» «Constant|q:» «Constant|:\"quoted\"» «Punctuation|}»",
                "«Keyword|return» «Constant|nil» «Keyword|unless» «Keyword|defined?»«Punctuation|(»foo«Punctuation|)» «Operator|&&» «Variable|\$stdout» «Operator|&&» «Variable|@@count»",
            ),
        )
    }

    @Test
    fun percentLiteralsAndRegexes() {
        assertGolden(
            "ruby",
            goldenLines(
                "words = %w[a b c] + %i(x y) + %W<#{a} b>",
                "s = %q(a (nested) b) + %Q{x #{y} z} + %(plain)",
                "re = /\\d+\\s#{MAX}/i",
                "m = %r!a/b!m",
                "x = a / 2 + b % 3",
                "ok = ?a",
            ),
            goldenLines(
                "words «Operator|=» «String|%w[a b c]» «Operator|+» «Constant|%i(x y)» «Operator|+» «String|%W<»«Punctuation|#{»a«Punctuation|}»«String| b>»",
                "s «Operator|=» «String|%q(a (nested) b)» «Operator|+» «String|%Q{x »«Punctuation|#{»y«Punctuation|}»«String| z}» «Operator|+» «String|%(plain)»",
                "re «Operator|=» «String|/»«Constant|\\d»«String|+»«Constant|\\s»«Punctuation|#{»«Constant|MAX»«Punctuation|}»«String|/i»",
                "m «Operator|=» «String|%r!a/b!m»",
                "x «Operator|=» a «Operator|/» «Number|2» «Operator|+» b «Operator|%» «Number|3»",
                "ok «Operator|=» «String|?a»",
            ),
        )
    }

    @Test
    fun heredocsAcrossLines() {
        assertGolden(
            "rake",
            goldenLines(
                "text = <<~EOS.strip",
                "  Hello #{name}",
                "    indented \\n",
                "  EOS",
                "raw = <<-'RAW'",
                "  #{not} interpolated",
                "  RAW",
                "call(<<~A, 1)",
                "  inside",
                "A",
            ),
            goldenLines(
                "text «Operator|=» «String|<<~EOS»«Punctuation|.»«Property|strip»",
                "«String|  Hello »«Punctuation|#{»name«Punctuation|}»",
                "«String|    indented »«Constant|\\n»",
                "  «String|EOS»",
                "raw «Operator|=» «String|<<-'RAW'»",
                "«String|  #{not} interpolated»",
                "  «String|RAW»",
                "«Function|call»«Punctuation|(»«String|<<~A»«Punctuation|,» «Number|1»«Punctuation|)»",
                "«String|  inside»",
                "«String|A»",
            ),
        )
    }

    @Test
    fun multiLineStringsAndNesting() {
        assertGolden(
            "ruby",
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
    fun beginEndCommentsAndData() {
        assertGolden(
            "ruby",
            goldenLines(
                "x = 1",
                "=begin",
                "doc #{x}",
                "=end",
                "y = 2",
                "__END__",
                "data \"here\"",
            ),
            goldenLines(
                "x «Operator|=» «Number|1»",
                "«Comment|=begin»",
                "«Comment|doc #{x}»",
                "«Comment|=end»",
                "y «Operator|=» «Number|2»",
                "«Keyword|__END__»",
                "«Comment|data \"here\"»",
            ),
        )
    }

    @Test
    fun unterminatedConstructs() {
        assertGolden(
            "ruby",
            goldenLines(
                "s = \"open #{x",
                "y = 'z'",
            ),
            goldenLines(
                "s «Operator|=» «String|\"open »«Punctuation|#{»x",
                "y «Operator|=» «String|'z'»",
            ),
        )
    }

    @Test
    fun scopedConstantsKeepTheirRole() {
        assertGolden(
            "ruby",
            goldenLines(
                "raise Store::NotFound, Errno::ENOENT if JSON::ParserError",
                "x = Foo::Bar.new.call(Net::HTTP::Get)",
            ),
            goldenLines(
                "«Keyword|raise» «Type|Store»«Punctuation|::»«Type|NotFound»«Punctuation|,» «Type|Errno»«Punctuation|::»«Constant|ENOENT» «Keyword|if» «Constant|JSON»«Punctuation|::»«Type|ParserError»",
                "x «Operator|=» «Type|Foo»«Punctuation|::»«Type|Bar»«Punctuation|.»«Property|new»«Punctuation|.»«Function|call»«Punctuation|(»«Type|Net»«Punctuation|::»«Constant|HTTP»«Punctuation|::»«Type|Get»«Punctuation|)»",
            ),
        )
    }

    @Test
    fun crlfAndSurrogates() {
        assertGolden(
            "ruby",
            "puts \"😀 #{x}\"\r\n# \uD83D\r\nx = :\uDE00a\r\n",
            goldenLines(
                "«Function|puts» «String|\"😀 »«Punctuation|#{»x«Punctuation|}»«String|\"»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "x «Operator|=» «Operator|:»⟨DE00⟩a␍",
                "",
            ),
        )
    }
}
