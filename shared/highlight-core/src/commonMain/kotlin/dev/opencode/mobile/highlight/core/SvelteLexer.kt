package dev.opencode.mobile.highlight.core

/**
 * Svelte components: markup with `{expression}` and block tags (`{#if}`, `{:else}`, `{/if}`,
 * `{#each}`, `{#await}`, `{@html}`), directives (`on:event`, `bind:value`), `<script>` as
 * JavaScript or TypeScript (`lang="ts"`), and `<style>` as CSS; see [ComponentMarkupLexer].
 *
 * Known gaps: an expression's end is found by counting braces outside quoted strings, so a
 * brace inside a regex, a comment, or a template literal continued from an earlier line counts.
 */
internal object SvelteLexer : ComponentMarkupLexer(svelte = true)
