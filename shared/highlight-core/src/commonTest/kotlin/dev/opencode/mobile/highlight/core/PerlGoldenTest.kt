package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Perl fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class PerlGoldenTest {
    @Test
    fun subsVariablesAndInterpolation() {
        assertGolden(
            "perl",
            goldenLines(
                "#!/usr/bin/perl",
                "use strict;",
                "use Data::Dumper qw(Dumper);",
                "package My::Pkg;",
                "sub label {",
                "    my (\$count, @rest) = @_;",
                "    my %opts = (verbose => 1, \$count => 2);",
                "    return \"sessions: \$count @rest \${count}s \$opts{verbose}\\n\";",
                "}",
                "my \$obj = Foo::Bar->new(name => 'x\\'y');",
                "\$obj->run(\\%opts, &helper, \$h{key}, \$r->{key}, \$#w, @\$ref);",
            ),
            goldenLines(
                "«Comment|#!/usr/bin/perl»",
                "«Keyword|use» «Type|strict»«Punctuation|;»",
                "«Keyword|use» «Type|Data::Dumper» «String|qw(Dumper)»«Punctuation|;»",
                "«Keyword|package» «Type|My::Pkg»«Punctuation|;»",
                "«Keyword|sub» «Function|label» «Punctuation|{»",
                "    «Keyword|my» «Punctuation|(»«Variable|\$count»«Punctuation|,» «Variable|@rest»«Punctuation|)» «Operator|=» «Variable|@_»«Punctuation|;»",
                "    «Keyword|my» «Variable|%opts» «Operator|=» «Punctuation|(»«String|verbose» «Operator|=>» «Number|1»«Punctuation|,» «Variable|\$count» «Operator|=>» «Number|2»«Punctuation|);»",
                "    «Keyword|return» «String|\"sessions: »«Variable|\$count»«String| »«Variable|@rest»«String| »«Variable|\${count}»«String|s »«Variable|\$opts»«String|{verbose}»«Constant|\\n»«String|\"»«Punctuation|;»",
                "«Punctuation|}»",
                "«Keyword|my» «Variable|\$obj» «Operator|=» «Type|Foo::Bar»«Operator|->»«Function|new»«Punctuation|(»«String|name» «Operator|=>» «String|'x»«Constant|\\'»«String|y'»«Punctuation|);»",
                "«Variable|\$obj»«Operator|->»«Function|run»«Punctuation|(»«Operator|\\»«Variable|%opts»«Punctuation|,» «Function|&helper»«Punctuation|,» «Variable|\$h»«Punctuation|{»«String|key»«Punctuation|},» «Variable|\$r»«Operator|->»«Punctuation|{»«String|key»«Punctuation|},» «Variable|\$#w»«Punctuation|,» «Variable|@\$ref»«Punctuation|);»",
            ),
        )
    }

    @Test
    fun regexesAndQuoteLikeOperators() {
        assertGolden(
            "perl",
            goldenLines(
                "my \$re = qr/^\\d+\$/i;",
                "if (\$line =~ /foo(\\w+)\$/ && \$x !~ m{a{2}b}x) { print STDERR \"ok: \$1\\n\"; }",
                "(my \$t = \$s) =~ s/\\s+/ /g;",
                "\$s =~ tr/a-z/A-Z/; y/a-c/A-C/;",
                "my @w = qw(a b c);",
                "my \$n = scalar(@w) / 2 + \$#w;",
                "\$h{s} = 1; %h = (q => 2, y => 3);",
                "my \$x = \$y % 3; my %z;",
                "@list = map { \$_ * 2 } grep { /x/ } @in;",
                "\$x = \$a // \$b; \$re =~ m[a]i; my \$sep = \"-\" x 20;",
                "while (my \$l = <STDIN>) { last if -s \$file; }",
            ),
            goldenLines(
                "«Keyword|my» «Variable|\$re» «Operator|=» «String|qr/^»«Constant|\\d»«String|+\$/i»«Punctuation|;»",
                "«Keyword|if» «Punctuation|(»«Variable|\$line» «Operator|=~» «String|/foo(»«Constant|\\w»«String|+)\$/» «Operator|&&» «Variable|\$x» «Operator|!~» «String|m{a{2}b}x»«Punctuation|)» «Punctuation|{» «Function|print» «Constant|STDERR» «String|\"ok: »«Variable|\$1»«Constant|\\n»«String|\"»«Punctuation|;» «Punctuation|}»",
                "«Punctuation|(»«Keyword|my» «Variable|\$t» «Operator|=» «Variable|\$s»«Punctuation|)» «Operator|=~» «String|s/»«Constant|\\s»«String|+/ /g»«Punctuation|;»",
                "«Variable|\$s» «Operator|=~» «String|tr/a-z/A-Z/»«Punctuation|;» «String|y/a-c/A-C/»«Punctuation|;»",
                "«Keyword|my» «Variable|@w» «Operator|=» «String|qw(a b c)»«Punctuation|;»",
                "«Keyword|my» «Variable|\$n» «Operator|=» «Function|scalar»«Punctuation|(»«Variable|@w»«Punctuation|)» «Operator|/» «Number|2» «Operator|+» «Variable|\$#w»«Punctuation|;»",
                "«Variable|\$h»«Punctuation|{»«String|s»«Punctuation|}» «Operator|=» «Number|1»«Punctuation|;» «Variable|%h» «Operator|=» «Punctuation|(»«String|q» «Operator|=>» «Number|2»«Punctuation|,» «String|y» «Operator|=>» «Number|3»«Punctuation|);»",
                "«Keyword|my» «Variable|\$x» «Operator|=» «Variable|\$y» «Operator|%» «Number|3»«Punctuation|;» «Keyword|my» «Variable|%z»«Punctuation|;»",
                "«Variable|@list» «Operator|=» «Function|map» «Punctuation|{» «Variable|\$_» «Operator|*» «Number|2» «Punctuation|}» «Function|grep» «Punctuation|{» «String|/x/» «Punctuation|}» «Variable|@in»«Punctuation|;»",
                "«Variable|\$x» «Operator|=» «Variable|\$a» «Operator|//» «Variable|\$b»«Punctuation|;» «Variable|\$re» «Operator|=~» «String|m[a]i»«Punctuation|;» «Keyword|my» «Variable|\$sep» «Operator|=» «String|\"-\"» «Operator|x» «Number|20»«Punctuation|;»",
                "«Keyword|while» «Punctuation|(»«Keyword|my» «Variable|\$l» «Operator|=» «Variable|<STDIN>»«Punctuation|)» «Punctuation|{» «Keyword|last» «Keyword|if» «Operator|-»s «Variable|\$file»«Punctuation|;» «Punctuation|}»",
            ),
        )
    }

    @Test
    fun twoPartLiteralsSpanLines() {
        assertGolden(
            "perl",
            goldenLines(
                "\$s =~ s{a}",
                "  {b}x;",
                "\$t = s(x)<y>;",
                "my \$u = q{a {b}",
                "c} . qq(open",
                "still) . \"\$v\";",
            ),
            goldenLines(
                "«Variable|\$s» «Operator|=~» «String|s{a}»",
                "  «String|{b}x»«Punctuation|;»",
                "«Variable|\$t» «Operator|=» «String|s(x)<y>»«Punctuation|;»",
                "«Keyword|my» «Variable|\$u» «Operator|=» «String|q{a {b}»",
                "«String|c}» «Operator|.» «String|qq(open»",
                "«String|still)» «Operator|.» «String|\"»«Variable|\$v»«String|\"»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun heredocs() {
        assertGolden(
            "perl",
            goldenLines(
                "print <<\"EOF\", \"after\\n\";",
                "Hello \$name",
                "EOF",
                "my \$raw = <<'RAW';",
                "no \$interp here",
                "RAW",
                "my \$txt = <<~EOT;",
                "    indented \$x",
                "    EOT",
                "print <<EOF;",
                "body \$x",
                "not the end",
            ),
            goldenLines(
                "«Function|print» «String|<<\"EOF\"»«Punctuation|,» «String|\"after»«Constant|\\n»«String|\"»«Punctuation|;»",
                "«String|Hello »«Variable|\$name»",
                "«String|EOF»",
                "«Keyword|my» «Variable|\$raw» «Operator|=» «String|<<'RAW'»«Punctuation|;»",
                "«String|no \$interp here»",
                "«String|RAW»",
                "«Keyword|my» «Variable|\$txt» «Operator|=» «String|<<~EOT»«Punctuation|;»",
                "«String|    indented »«Variable|\$x»",
                "    «String|EOT»",
                "«Function|print» «String|<<EOF»«Punctuation|;»",
                "«String|body »«Variable|\$x»",
                "«String|not the end»",
            ),
        )
    }

    @Test
    fun podAndData() {
        assertGolden(
            "perl",
            goldenLines(
                "my \$x = 1;",
                "=pod",
                "",
                "Docs here \$x",
                "",
                "=cut",
                "print \"done\";",
                "__END__",
                "data \$x \"q\"",
            ),
            goldenLines(
                "«Keyword|my» «Variable|\$x» «Operator|=» «Number|1»«Punctuation|;»",
                "«Comment|=pod»",
                "",
                "«Comment|Docs here \$x»",
                "",
                "«Comment|=cut»",
                "«Function|print» «String|\"done\"»«Punctuation|;»",
                "«Keyword|__END__»",
                "«Comment|data \$x \"q\"»",
            ),
        )
    }

    @Test
    fun crlfEmojiAndLoneSurrogates() {
        assertGolden(
            "perl",
            "my \$s = \"😀 \$x\";\r\n# \uD83D\r\nmy \$t = \uD83D q{a\r\nb};\r\n",
            goldenLines(
                "«Keyword|my» «Variable|\$s» «Operator|=» «String|\"😀 »«Variable|\$x»«String|\"»«Punctuation|;»␍",
                "«Comment|# ⟨D83D⟩»␍",
                "«Keyword|my» «Variable|\$t» «Operator|=» ⟨D83D⟩ «String|q{a»␍",
                "«String|b}»«Punctuation|;»␍",
                "",
            ),
        )
    }

    @Test
    fun longLinesAreCapped() {
        assertGolden(
            "perl",
            goldenLines(
                "my \$s = q{abc def}",
                "print \$s;",
            ),
            goldenLines(
                "«Keyword|my» «Variable|\$s» «Operator|=» «String|q{ab»c def}",
                "«String|print \$s;»",
            ),
            HighlightLimits(maxLineLength = 12),
        )
    }
}
