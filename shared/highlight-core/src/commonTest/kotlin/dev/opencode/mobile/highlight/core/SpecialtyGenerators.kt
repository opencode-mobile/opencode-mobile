package dev.opencode.mobile.highlight.core

/**
 * Random pieces for Vue, Svelte, GraphQL, MATLAB, and assembly, merged into
 * [HighlightGenerators]: script and style elements, interpolations, expressions, block tags,
 * block strings, block comments, and transposes appear opened, closed, and damaged, next to
 * CRLF, tabs, emoji, and lone surrogates.
 */
internal object SpecialtyGenerators {
    val languages = listOf("vue", "svelte", "graphql", "matlab", "asm")

    private val common = listOf(" ", " ", " ", "\n", "\n", "\n", "\r\n", "\t", "\\", "😀", "\uD83D", "\uDE00", "​", "\r")

    private val markup =
        listOf(
            "<", ">", "</", "/>", "<div", "</div>", "<p class=\"a\">", " id='x'", " hidden", "=", "\"", "'", "&amp;", "<!--", "-->",
            "<!DOCTYPE html>", "<script>", "</script>", "<script lang=\"ts\">", "<script setup lang=\"ts\">", "<style>",
            "<style lang=\"scss\">", "</style>", "<template>", "</template>", "`", "${'$'}{", "{", "}", "color: red;", "const x = 1;",
            "\"s", "/*", "*/", "//", "text",
        )

    val pieces: Map<String, List<String>> =
        mapOf(
            "vue" to
                common + markup +
                listOf(
                    "{{", "}}", "{{ a + b }}", "{{ msg", " v-if=\"ok\"", " v-else", " v-for=\"(item, i) in items\"", " :class=\"{ a: b }\"",
                    " :prop=", " @click=\"count++\"", " @update:model-value.once=\"", " #default=\"{ item }\"", " v-bind:[key]=\"v\"",
                    " style=\"color: red\"", " :a=b", " .prop=\"x\"", "'s'",
                ),
            "svelte" to
                common + markup +
                listOf(
                    "{#if ok}", "{:else if x}", "{:else}", "{/if}", "{#each items as item, i (item.id)}", "{/each}", "{#await p then v}",
                    "{:then v}", "{:catch e}", "{/await}", "{@html raw}", "{@const a = 1}", "{#snippet row(x)}", "{@render row(1)}",
                    "{count}", "{", "}", "{{", "}}", " on:click={handler}", " on:click|once|preventDefault={() => {", " bind:value={x}",
                    " class:active={on}", " use:action", " transition:fade", " {...rest}", " {value}", " title=\"a {b} c\"",
                    "<Button>", "</Button>", "<svelte:head>", "'s'", "\"q\"",
                ),
            "graphql" to
                common +
                listOf(
                    "query", "mutation", "subscription", "fragment", "on", "type", "interface", "input", "enum", "union", "scalar",
                    "schema", "extend", "directive", "implements", "repeatable", "Name", "field", "id", "ID!", "[String!]!", "{", "}",
                    "(", ")", "[", "]", ":", "=", "|", "&", "!", ",", "...", "... on User", "\$id", "\$n: Int = 10", "@include(if: \$x)",
                    "@deprecated", "\"s\\\"q\\u00e9\"", "\"open", "\"\"\"", "\"\"\"block\n", "\\\"\"\"", "# comment", "#", "true", "null",
                    "ACTIVE", "12", "-1.5e3", "alias: field", "FIELD | QUERY",
                ),
            "matlab" to
                common +
                listOf(
                    "function", "function [a, b] = f(x, ~)", "function y = g(x)", "end", "if", "elseif", "else", "for", "while",
                    "switch", "case", "otherwise", "try", "catch", "classdef", "properties", "methods", "x", "y", "Name", "'",
                    "a'", "x.'", "'str'", "'it''s'", "\"dq\"", "\"\"", "'open", "\"open", "%", "% c", "%%", "\n%{\n", "\n%}\n", "%{", "%}",
                    "...", "... more", "(", ")", "[", "]", "{", "}", ";", ",", ".", "=", "==", "~=", ".*", "@(x)", ":", "1", "2.5e-3",
                    "3i", "0x1F", "hold on", "format long", "\n!ls", "pi", "NaN", "nargin", "s.field", "a(end)",
                ),
            "asm" to
                common +
                listOf(
                    "section .text", "global _start", "_start:", ".L1:", "1:", "mov", "eax", "%eax", "\$1", "\$\$", "\$", "rax", "r8d",
                    "xmm0", "x0", "w1", "v0.4s", "sp", "lr", "#4", "# c", "#include <x.h>", "#define X 1", "; comment", ";", "// c",
                    "/* c */", "/*", "*/", "@ c", "@function", ".globl", ".section", ".data", "db", "msg db 'hi', 0", "equ", "dword",
                    "ptr", "[", "]", ",", "+", "-", "*", "<<", ":", "0x80", "0FFh", "1010b", "17q", "1.5e3", "\"s\\n\"", "'c'", "`b\\t`",
                    "\"open", "lock", "rep movsb", "int 0x80", "ret", "%define", "%1", "main PROC", "lsl",
                ),
        )
}
