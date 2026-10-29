package dev.opencode.mobile.highlight.core

/**
 * Vue single-file components: `<template>` markup with `{{ }}` interpolation and directive
 * attributes (`v-if`, `:prop`, `@event`, `#slot`) whose values are expressions, `<script>` and
 * `<script setup>` as JavaScript or TypeScript (`lang="ts"`), and `<style>` as CSS (SCSS and
 * Less tolerated); see [ComponentMarkupLexer].
 *
 * Known gaps: custom delimiters, `v-pre`, and `<template lang="pug">` are lexed as plain
 * templates; template expressions are always TypeScript's.
 */
internal object VueLexer : ComponentMarkupLexer(svelte = false)
