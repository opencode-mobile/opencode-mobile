package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written GraphQL fixtures: the contract for [GraphqlLexer]. One escaped string per line keeps `$`, backslashes, CRLF, and lone surrogates exact. */
class GraphqlGoldenTest {
    @Test
    fun schemaDefinitions() {
        assertGolden(
            "graphql",
            goldenLines(
                "\"\"\"A session.\"\"\"",
                "type Session implements Node & Entity @key(fields: \"id\") {",
                "  id: ID!",
                "  \"The title.\"",
                "  title(first: Int = 10, after: String): [String!]!",
                "  type: Kind",
                "}",
                "interface Node { id: ID! }",
                "input Filter { name: String = \"x\", tags: [String!] }",
                "enum Status { ACTIVE ARCHIVED @deprecated(reason: \"old\") }",
                "union Result = Session | Error",
                "scalar DateTime @specifiedBy(url: \"https://x\")",
                "extend type Query { me: User }",
                "directive @auth(requires: Role = ADMIN) repeatable on FIELD_DEFINITION | OBJECT",
                "schema { query: Query mutation: Mutation }",
            ),
            goldenLines(
                "«String|\"\"\"A session.\"\"\"»",
                "«Keyword|type» «Type|Session» «Keyword|implements» «Type|Node» «Operator|&» «Type|Entity» «Function|@key»«Punctuation|(»«Variable|fields»«Punctuation|:» «String|\"id\"»«Punctuation|)» «Punctuation|{»",
                "  «Property|id»«Punctuation|:» «Type|ID»«Operator|!»",
                "  «String|\"The title.\"»",
                "  «Property|title»«Punctuation|(»«Variable|first»«Punctuation|:» «Type|Int» «Punctuation|=» «Number|10»«Punctuation|,» «Variable|after»«Punctuation|:» «Type|String»«Punctuation|):» «Punctuation|[»«Type|String»«Operator|!»«Punctuation|]»«Operator|!»",
                "  «Property|type»«Punctuation|:» «Type|Kind»",
                "«Punctuation|}»",
                "«Keyword|interface» «Type|Node» «Punctuation|{» «Property|id»«Punctuation|:» «Type|ID»«Operator|!» «Punctuation|}»",
                "«Keyword|input» «Type|Filter» «Punctuation|{» «Property|name»«Punctuation|:» «Type|String» «Punctuation|=» «String|\"x\"»«Punctuation|,» «Property|tags»«Punctuation|:» «Punctuation|[»«Type|String»«Operator|!»«Punctuation|]» «Punctuation|}»",
                "«Keyword|enum» «Type|Status» «Punctuation|{» «Constant|ACTIVE» «Constant|ARCHIVED» «Function|@deprecated»«Punctuation|(»«Variable|reason»«Punctuation|:» «String|\"old\"»«Punctuation|)» «Punctuation|}»",
                "«Keyword|union» «Type|Result» «Punctuation|=» «Type|Session» «Punctuation||» «Type|Error»",
                "«Keyword|scalar» «Type|DateTime» «Function|@specifiedBy»«Punctuation|(»«Variable|url»«Punctuation|:» «String|\"https://x\"»«Punctuation|)»",
                "«Keyword|extend» «Keyword|type» «Type|Query» «Punctuation|{» «Property|me»«Punctuation|:» «Type|User» «Punctuation|}»",
                "«Keyword|directive» «Function|@auth»«Punctuation|(»«Variable|requires»«Punctuation|:» «Type|Role» «Punctuation|=» «Constant|ADMIN»«Punctuation|)» «Keyword|repeatable» «Keyword|on» «Constant|FIELD_DEFINITION» «Punctuation||» «Constant|OBJECT»",
                "«Keyword|schema» «Punctuation|{» «Keyword|query»«Punctuation|:» «Type|Query» «Keyword|mutation»«Punctuation|:» «Type|Mutation» «Punctuation|}»",
            ),
        )
    }

    @Test
    fun operationsFragmentsAndVariables() {
        assertGolden(
            "graphql",
            goldenLines(
                "query ActiveSessions(\$id: ID!, \$n: Int = 10, \$f: Filter = {name: \"a\"}) {",
                "  sessions(active: true, first: \$n, filter: {name: \"x\", status: ACTIVE, ids: [1, -2.5e3]}) @include(if: \$flag) {",
                "    alias: id",
                "    ...Frag",
                "    ... on User { name }",
                "    ... @skip(if: false) { title }",
                "  }",
                "}",
                "fragment Frag on Session { id }",
                "mutation { create(input: {title: null}) { id } }",
                "subscription OnEvent { event { type } }",
                "{ me { id } }",
            ),
            goldenLines(
                "«Keyword|query» «Function|ActiveSessions»«Punctuation|(»«Variable|\$id»«Punctuation|:» «Type|ID»«Operator|!»«Punctuation|,» «Variable|\$n»«Punctuation|:» «Type|Int» «Punctuation|=» «Number|10»«Punctuation|,» «Variable|\$f»«Punctuation|:» «Type|Filter» «Punctuation|=» «Punctuation|{»«Variable|name»«Punctuation|:» «String|\"a\"»«Punctuation|})» «Punctuation|{»",
                "  «Property|sessions»«Punctuation|(»«Variable|active»«Punctuation|:» «Constant|true»«Punctuation|,» «Variable|first»«Punctuation|:» «Variable|\$n»«Punctuation|,» «Variable|filter»«Punctuation|:» «Punctuation|{»«Variable|name»«Punctuation|:» «String|\"x\"»«Punctuation|,» «Variable|status»«Punctuation|:» «Constant|ACTIVE»«Punctuation|,» «Variable|ids»«Punctuation|:» «Punctuation|[»«Number|1»«Punctuation|,» «Number|-2.5e3»«Punctuation|]})» «Function|@include»«Punctuation|(»«Variable|if»«Punctuation|:» «Variable|\$flag»«Punctuation|)» «Punctuation|{»",
                "    «Property|alias»«Punctuation|:» «Property|id»",
                "    «Operator|...»«Function|Frag»",
                "    «Operator|...» «Keyword|on» «Type|User» «Punctuation|{» «Property|name» «Punctuation|}»",
                "    «Operator|...» «Function|@skip»«Punctuation|(»«Variable|if»«Punctuation|:» «Constant|false»«Punctuation|)» «Punctuation|{» «Property|title» «Punctuation|}»",
                "  «Punctuation|}»",
                "«Punctuation|}»",
                "«Keyword|fragment» «Function|Frag» «Keyword|on» «Type|Session» «Punctuation|{» «Property|id» «Punctuation|}»",
                "«Keyword|mutation» «Punctuation|{» «Property|create»«Punctuation|(»«Variable|input»«Punctuation|:» «Punctuation|{»«Variable|title»«Punctuation|:» «Constant|null»«Punctuation|})» «Punctuation|{» «Property|id» «Punctuation|}» «Punctuation|}»",
                "«Keyword|subscription» «Function|OnEvent» «Punctuation|{» «Property|event» «Punctuation|{» «Property|type» «Punctuation|}» «Punctuation|}»",
                "«Punctuation|{» «Property|me» «Punctuation|{» «Property|id» «Punctuation|}» «Punctuation|}»",
            ),
        )
    }

    @Test
    fun stringsBlockStringsAndComments() {
        assertGolden(
            "graphql",
            goldenLines(
                "# leading comment",
                "type A {",
                "  \"\"\"",
                "  Block \"quoted\" \\\"\"\" still",
                "  \"\"\"",
                "  f(a: String = \"esc \\\" \\u00e9 \\n\"): Int # trailing",
                "  g(b: String = \"open",
                "}",
                "\"\"\"😀 \uD83D\r",
                "never closed\r",
                "",
            ),
            goldenLines(
                "«Comment|# leading comment»",
                "«Keyword|type» «Type|A» «Punctuation|{»",
                "  «String|\"\"\"»",
                "«String|  Block \"quoted\" \\\"\"\" still»",
                "«String|  \"\"\"»",
                "  «Property|f»«Punctuation|(»«Variable|a»«Punctuation|:» «Type|String» «Punctuation|=» «String|\"esc »«Constant|\\\"»«String| »«Constant|\\u00e9»«String| »«Constant|\\n»«String|\"»«Punctuation|):» «Type|Int» «Comment|# trailing»",
                "  «Property|g»«Punctuation|(»«Variable|b»«Punctuation|:» «Type|String» «Punctuation|=» «String|\"open»",
                "«Punctuation|}»",
                "«String|\"\"\"😀 ⟨D83D⟩»␍",
                "«String|never closed»␍",
                "",
            ),
        )
    }
}
