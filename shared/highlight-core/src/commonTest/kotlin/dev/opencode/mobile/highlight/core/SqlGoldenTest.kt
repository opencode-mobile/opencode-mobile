package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written SQL fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class SqlGoldenTest {
    @Test
    fun queriesKeywordsAndFunctions() {
        assertGolden(
            "sql",
            goldenLines(
                "-- active sessions",
                "SELECT id, title, COUNT(*) AS n, s.name, coalesce(x, 0)",
                "FROM sessions s",
                "LEFT JOIN users u ON u.id = s.user_id",
                "WHERE active = TRUE AND deleted_at IS NULL AND title LIKE 'it''s%'",
                "GROUP BY id HAVING n >= 2",
                "ORDER BY n DESC",
                "LIMIT 10;",
            ),
            goldenLines(
                "«Comment|-- active sessions»",
                "«Keyword|SELECT» id«Punctuation|,» title«Punctuation|,» «Function|COUNT»«Punctuation|(»«Operator|*»«Punctuation|)» «Keyword|AS» n«Punctuation|,» s«Punctuation|.»name«Punctuation|,» «Function|coalesce»«Punctuation|(»x«Punctuation|,» «Number|0»«Punctuation|)»",
                "«Keyword|FROM» sessions s",
                "«Keyword|LEFT» «Keyword|JOIN» users u «Keyword|ON» u«Punctuation|.»id «Operator|=» s«Punctuation|.»user_id",
                "«Keyword|WHERE» active «Operator|=» «Constant|TRUE» «Keyword|AND» deleted_at «Keyword|IS» «Constant|NULL» «Keyword|AND» title «Keyword|LIKE» «String|'it»«Constant|''»«String|s%'»",
                "«Keyword|GROUP» «Keyword|BY» id «Keyword|HAVING» n «Operator|>=» «Number|2»",
                "«Keyword|ORDER» «Keyword|BY» n «Keyword|DESC»",
                "«Keyword|LIMIT» «Number|10»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun ddlTypesAndQuotedIdentifiers() {
        assertGolden(
            "sql",
            goldenLines(
                "create table \"Users\" (",
                "  id serial primary key,",
                "  email varchar(255) not null default '',",
                "  `order` int,",
                "  [group] nvarchar(max),",
                "  meta jsonb",
                ");",
            ),
            goldenLines(
                "«Keyword|create» «Keyword|table» «Variable|\"Users\"» «Punctuation|(»",
                "  id «Type|serial» «Keyword|primary» «Keyword|key»«Punctuation|,»",
                "  email «Type|varchar»«Punctuation|(»«Number|255»«Punctuation|)» «Keyword|not» «Constant|null» «Keyword|default» «String|''»«Punctuation|,»",
                "  «Variable|`order`» «Type|int»«Punctuation|,»",
                "  «Variable|[group]» «Type|nvarchar»«Punctuation|(»max«Punctuation|),»",
                "  meta «Type|jsonb»",
                "«Punctuation|);»",
            ),
        )
    }

    @Test
    fun parametersCastsAndPrefixedStrings() {
        assertGolden(
            "sql",
            goldenLines(
                "select x::int, \$1, ?, :name, @var, @@version, E'a\\'b', N'x', X'ff', b'01'",
                "from t where arr[1] = 1.5e3 and y <> -2 and z || 'a' = 'ba';",
            ),
            goldenLines(
                "«Keyword|select» x«Operator|::»«Type|int»«Punctuation|,» «Variable|\$1»«Punctuation|,» «Variable|?»«Punctuation|,» «Variable|:name»«Punctuation|,» «Variable|@var»«Punctuation|,» «Variable|@@version»«Punctuation|,» «String|E'a»«Constant|\\'»«String|b'»«Punctuation|,» «String|N'x'»«Punctuation|,» «String|X'ff'»«Punctuation|,» «String|b'01'»",
                "«Keyword|from» t «Keyword|where» arr«Punctuation|[»«Number|1»«Punctuation|]» «Operator|=» «Number|1.5e3» «Keyword|and» y «Operator|<>» «Operator|-»«Number|2» «Keyword|and» z «Operator|||» «String|'a'» «Operator|=» «String|'ba'»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun dollarQuotedBodiesAndBlockComments() {
        assertGolden(
            "sql",
            goldenLines(
                "/* block",
                "   comment */",
                "CREATE FUNCTION f() RETURNS int AS \$body\$",
                "BEGIN RETURN \$1 + 1; END;",
                "\$body\$ LANGUAGE plpgsql;",
                "SELECT \$\$a 'quoted' \$x\$ b\$\$;",
            ),
            goldenLines(
                "«Comment|/* block»",
                "«Comment|   comment */»",
                "«Keyword|CREATE» «Keyword|FUNCTION» «Function|f»«Punctuation|()» «Keyword|RETURNS» «Type|int» «Keyword|AS» «String|\$body\$»",
                "«String|BEGIN RETURN \$1 + 1; END;»",
                "«String|\$body\$» «Keyword|LANGUAGE» plpgsql«Punctuation|;»",
                "«Keyword|SELECT» «String|\$\$a 'quoted' \$x\$ b\$\$»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun keywordsAfterDotsAndCallableKeywords() {
        assertGolden(
            "sql",
            goldenLines(
                "SELECT t.select, t.order, left(name, 2), replace(a, 'b', 'c'), current_date FROM t;",
            ),
            goldenLines(
                "«Keyword|SELECT» t«Punctuation|.»select«Punctuation|,» t«Punctuation|.»order«Punctuation|,» «Function|left»«Punctuation|(»name«Punctuation|,» «Number|2»«Punctuation|),» «Function|replace»«Punctuation|(»a«Punctuation|,» «String|'b'»«Punctuation|,» «String|'c'»«Punctuation|),» «Function|current_date» «Keyword|FROM» t«Punctuation|;»",
            ),
        )
    }

    @Test
    fun unterminatedStringsAndIdentifiers() {
        assertGolden(
            "sql",
            goldenLines(
                "SELECT 'open",
                "still' AS \"a",
                "b\", 1;",
            ),
            goldenLines(
                "«Keyword|SELECT» «String|'open»",
                "«String|still'» «Keyword|AS» «Variable|\"a»",
                "«Variable|b\"»«Punctuation|,» «Number|1»«Punctuation|;»",
            ),
        )
    }

    @Test
    fun tableNamesBeforeColumnListsAreNotCalls() {
        assertGolden(
            "sql",
            goldenLines(
                "INSERT INTO sessions (id, title) VALUES (1, 'a') ON CONFLICT (id) DO NOTHING;",
                "CREATE TABLE IF NOT EXISTS logs (id INT REFERENCES users (id));",
                "CREATE INDEX idx ON logs USING btree (id);",
                "SELECT upper(title) FROM sessions;",
            ),
            goldenLines(
                "«Keyword|INSERT» «Keyword|INTO» sessions «Punctuation|(»id«Punctuation|,» title«Punctuation|)» «Keyword|VALUES» «Punctuation|(»«Number|1»«Punctuation|,» «String|'a'»«Punctuation|)» «Keyword|ON» «Keyword|CONFLICT» «Punctuation|(»id«Punctuation|)» «Keyword|DO» «Keyword|NOTHING»«Punctuation|;»",
                "«Keyword|CREATE» «Keyword|TABLE» «Keyword|IF» «Keyword|NOT» «Keyword|EXISTS» logs «Punctuation|(»id «Type|INT» «Keyword|REFERENCES» users «Punctuation|(»id«Punctuation|));»",
                "«Keyword|CREATE» «Keyword|INDEX» idx «Keyword|ON» logs «Keyword|USING» btree «Punctuation|(»id«Punctuation|);»",
                "«Keyword|SELECT» «Function|upper»«Punctuation|(»title«Punctuation|)» «Keyword|FROM» sessions«Punctuation|;»",
            ),
        )
    }

    @Test
    fun crlfAndSurrogates() {
        assertGolden(
            "sql",
            "SELECT '😀'\r\nFROM \uD83D t; -- é\r\n",
            goldenLines(
                "«Keyword|SELECT» «String|'😀'»␍",
                "«Keyword|FROM» ⟨D83D⟩ t«Punctuation|;» «Comment|-- é»␍",
                "",
            ),
        )
    }
}
