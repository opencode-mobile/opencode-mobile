package dev.opencode.mobile.highlight.bench

/**
 * Typical code for javascript and the Basic languages (#872), repeated by [HighlightBenchCorpus.code]
 * with `{n}` replaced by the copy's index. Each is one realistic file of about 1-2.5K characters
 * that exercises the language's own constructs: heredocs, block scalars, embedded `<script>` and
 * `<style>`, PHP islands, fences of several languages, raw strings, and so on.
 */
internal object HighlightBenchCorpusTemplates {
    val basic: Map<String, String> =
        mapOf(
            "javascript" to
                """
                |/**
                | * Session client {n}: fetches sessions and streams their events.
                | * @module client{n}
                | */
                |"use strict";
                |import { EventEmitter } from "node:events";
                |const { readFile } = require("node:fs/promises");
                |
                |const DEFAULTS_{n} = Object.freeze({ retries: 3, timeoutMs: 1_500, base: "https://example.com/api/" });
                |const ID_PATTERN_{n} = /^ses_[a-z0-9]+${'$'}/i;
                |
                |export class SessionClient{n} extends EventEmitter {
                |  #cache = new Map();
                |  static instances = 0;
                |
                |  constructor(options = {}) {
                |    super();
                |    this.options = { ...DEFAULTS_{n}, ...options };
                |    SessionClient{n}.instances += 1;
                |  }
                |
                |  async *events(id, { signal } = {}) {
                |    if (!ID_PATTERN_{n}.test(id)) throw new TypeError(`bad id: ${'$'}{id}`);
                |    const response = await fetch(`${'$'}{this.options.base}session/${'$'}{encodeURIComponent(id)}/event`, { signal });
                |    const reader = response.body?.getReader();
                |    for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
                |      yield JSON.parse(new TextDecoder().decode(chunk.value));
                |    }
                |  }
                |
                |  async load(id) {
                |    if (this.#cache.has(id)) return this.#cache.get(id);
                |    try {
                |      const body = await (await fetch(this.options.base + "session/" + id)).json();
                |      this.#cache.set(id, body);
                |      return body?.session ?? null;
                |    } catch (error) {
                |      console.error('load failed', error, 0x1f, 2e-3);
                |      return null;
                |    } finally {
                |      this.emit("loaded", id);
                |    }
                |  }
                |}
                |
                |export default function createClient{n}(options) {
                |  return new SessionClient{n}(options);
                |}
                |
                |const squares_{n} = [1, 2, 3].map((x) => x ** 2).filter(Boolean); // [1, 4, 9]
                |
                """.trimMargin(),
            "shellscript" to
                """
                |#!/usr/bin/env bash
                |# Release script {n}: builds every target and writes a summary.
                |set -euo pipefail
                |IFS=${'$'}'\n\t'
                |
                |readonly SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
                |VERSION_{n}="${'$'}{VERSION:-1.{n}.0}"
                |declare -a TARGETS=(android ios "web app")
                |declare -A SIZES=([small]=2048 [large]=${'$'}((1024 * 1024)))
                |
                |log_{n}() {
                |  local level="${'$'}1"; shift
                |  printf '%s [%s] %s\n' "${'$'}(date +%H:%M:%S)" "${'$'}{level^^}" "${'$'}*" >&2
                |}
                |
                |build_target() {
                |  local target=${'$'}1
                |  case "${'$'}target" in
                |    android) ./gradlew assembleRelease --quiet ;;
                |    ios|macos) xcodebuild -scheme "App ${'$'}{target}" -configuration Release | tee "build-${'$'}{target}.log" ;;
                |    *) log_{n} warn "unknown target: ${'$'}{target}"; return 1 ;;
                |  esac
                |}
                |
                |for target in "${'$'}{TARGETS[@]}"; do
                |  if [[ -n "${'$'}{SKIP_BUILD:-}" && "${'$'}target" != "web app" ]]; then
                |    continue
                |  elif ! build_target "${'$'}target"; then
                |    log_{n} error "build failed for ${'$'}target (exit ${'$'}?)"
                |  fi
                |done
                |
                |count=0
                |while read -r line; do
                |  (( count += ${'$'}{#line} ))
                |done < <(find "${'$'}SCRIPT_DIR" -name '*.sh' -type f 2>/dev/null)
                |
                |cat <<EOF > "release-{n}.txt"
                |version: ${'$'}{VERSION_{n}}
                |built: ${'$'}(date -u +%Y-%m-%dT%H:%M:%SZ)
                |targets: ${'$'}{TARGETS[*]} (${'$'}{#SIZES[@]} sizes)
                |EOF
                |
                |cat <<'RAW'
                |No ${'$'}expansion or ${'$'}(substitution) happens in here.
                |RAW
                |
                |[ "${'$'}count" -gt 0 ] && echo "scanned ${'$'}count bytes" || echo "nothing to scan"
                |export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
                |
                """.trimMargin(),
            "yaml" to
                """
                |---
                |# CI workflow {n}: builds both apps on every push.
                |name: Build {n}
                |on:
                |  push:
                |    branches: [main, "release/**"]
                |  pull_request:
                |    types: [opened, synchronize]
                |env:
                |  JAVA_VERSION: "17"
                |  RETRIES: 3
                |  TIMEOUT: 12.5
                |  DEBUG: false
                |  EMPTY: ~
                |defaults: &defaults_{n}
                |  runs-on: ubuntu-latest
                |  timeout-minutes: 30
                |jobs:
                |  build-{n}:
                |    <<: *defaults_{n}
                |    strategy:
                |      matrix:
                |        platform: [android, ios]
                |        include:
                |          - platform: android
                |            task: assembleDebug
                |          - platform: ios
                |            task: 'test -scheme "App"'
                |    steps:
                |      - uses: actions/checkout@v4
                |        with:
                |          fetch-depth: 0
                |      - name: Build ${'$'}{{ matrix.platform }}
                |        run: |
                |          echo "Building {n} for ${'$'}{{ matrix.platform }}"
                |          ./scripts/build.sh --task "${'$'}{{ matrix.task }}" \
                |            --retries ${'$'}{RETRIES}
                |      - name: Summarize
                |        if: ${'$'}{{ always() && github.event_name == 'push' }}
                |        run: >-
                |          echo "folded text continues
                |          on this line"
                |---
                |# Second document {n}: anchors, flow collections, tags.
                |anchors:
                |  base: &base_{n} {name: base, ports: [80, 443], ratio: 0.75}
                |  derived:
                |    <<: *base_{n}
                |    name: derived-{n}
                |    created: 2024-05-01T12:00:00Z
                |    notes: !!str 0x1F
                |    "quoted key": 'single ''quoted'' value'
                |    ? complex key
                |    : complex value
                |    kept: |+
                |      trailing newline is kept
                |
                |...
                |
                """.trimMargin(),
            "markdown" to
                """
                |# Release notes {n}
                |
                |Version **1.{n}.0** adds _offline_ mode, `inline code`, and [links](https://example.com/docs/{n}).
                |See the ![diagram](images/arch-{n}.png "Architecture") for details.
                |
                |## Changes
                |
                |- Faster *session* loading
                |  - nested item with `code`
                |- [x] Done task
                |- [ ] Open task
                |
                |1. First
                |2. Second ~~removed~~
                |
                |> **Note**
                |> Quotes can hold `code` and *emphasis*.
                |
                || Engine | 2K | 16K |
                ||:-------|---:|----:|
                || first | 12.5 | 40 |
                || second | 3 | 7.25 |
                |
                |```kotlin
                |fun greet{n}(name: String) = "Hello, ${'$'}{name}!"
                |```
                |
                |```typescript
                |export const answer{n}: number = 42; // comment
                |```
                |
                |```bash
                |echo "${'$'}HOME" | grep -c "example"
                |```
                |
                |```python
                |def area_{n}(r: float) -> float:
                |    return 3.14159 * r ** 2
                |```
                |
                |~~~json
                |{"id": {n}, "tags": ["a", "b"], "ok": true}
                |~~~
                |
                |    indented code block {n}
                |
                |<details><summary>HTML block</summary>
                |
                |Inline <kbd>Ctrl</kbd>+<kbd>C</kbd> and a footnote[^{n}].
                |
                |</details>
                |
                |[^{n}]: The footnote text.
                |
                |Setext heading {n}
                |------------------
                |
                |A hard line break\
                |and a \*literal\* asterisk &copy; <https://example.com>.
                |
                |***
                |
                """.trimMargin(),
            "html" to
                """
                |<!DOCTYPE html>
                |<!-- Dashboard page {n} -->
                |<html lang="en">
                |<head>
                |  <meta charset="utf-8">
                |  <meta name="viewport" content="width=device-width, initial-scale=1">
                |  <title>Sessions {n} &mdash; Dashboard</title>
                |  <link rel="stylesheet" href="/styles/app.css">
                |  <style>
                |    :root { --accent-{n}: #3b82f6; }
                |    .session-{n} > .title { color: var(--accent-{n}); font: 600 14px/1.4 system-ui, sans-serif; }
                |    @media (max-width: 600px) { .session-{n} { padding: 0.5rem 1em; } }
                |  </style>
                |</head>
                |<body class="theme-dark" data-page="{n}">
                |  <header id="top-{n}">
                |    <nav aria-label="Main"><a href="/" class="active">Home</a> | <a href="/sessions?page={n}&amp;sort=desc">Sessions</a></nav>
                |  </header>
                |  <main>
                |    <form action="/search" method="get" novalidate>
                |      <label for="q-{n}">Search</label>
                |      <input id="q-{n}" type="search" name="q" placeholder="Find a session&hellip;" required autofocus>
                |      <button type="submit" disabled>Go</button>
                |    </form>
                |    <table>
                |      <thead><tr><th scope="col">Title</th><th>Updated</th></tr></thead>
                |      <tbody><tr><td>Refactor &lt;store&gt;</td><td><time datetime="2024-05-01">May 1</time></td></tr></tbody>
                |    </table>
                |    <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" alt="" width="1" height="1">
                |  </main>
                |  <script type="module">
                |    import { render } from "./render.js";
                |    const rows{n} = document.querySelectorAll(".session-{n}");
                |    for (const row of rows{n}) {
                |      row.addEventListener("click", (event) => render(row.dataset.id, `<b>${'$'}{event.type}</b>`));
                |    }
                |    if (rows{n}.length < 1 && window.innerWidth > 0x200) console.log('empty </div> in a string');
                |  </script>
                |  <script src="/vendor/analytics.js" async defer></script>
                |</body>
                |</html>
                |
                """.trimMargin(),
            "css" to
                """
                |/* Theme {n}: dashboard styles. */
                |@import url("reset.css") screen;
                |
                |:root {
                |  --accent-{n}: #3b82f6;
                |  --radius: 8px;
                |  --font-stack: "Inter", system-ui, -apple-system, sans-serif;
                |}
                |
                |.session-{n},
                |.session-{n} > .title::before {
                |  display: grid;
                |  grid-template-columns: repeat(auto-fill, minmax(12rem, 1fr));
                |  gap: calc(var(--radius) * 2) 1.5em;
                |  color: rgb(59 130 246 / 80%);
                |  background: linear-gradient(135deg, #fff 0%, #f3f4f6 100%) no-repeat;
                |  transition: transform 150ms ease-in-out, opacity .2s;
                |  content: "\201C";
                |}
                |
                |#top-{n}:hover:not(.disabled) a[href^="https://"] {
                |  text-decoration: underline dotted !important;
                |}
                |
                |@media (prefers-color-scheme: dark) and (min-width: 600px) {
                |  .session-{n} { color: hsl(220deg 14% 96%); border: 1px solid currentColor; }
                |}
                |
                |@supports (display: grid) {
                |  .grid-{n} { inline-size: min(100%, 72ch); }
                |}
                |
                |@keyframes pulse-{n} {
                |  from { opacity: 1; }
                |  50% { opacity: 0.4; transform: scale(1.05); }
                |  to { opacity: 1; }
                |}
                |
                |@font-face {
                |  font-family: "Mono {n}";
                |  src: url(fonts/mono.woff2) format("woff2");
                |  unicode-range: U+0000-00FF;
                |}
                |
                |.nested-{n} {
                |  & .child { margin: -0.25rem auto 0; }
                |  &:focus-visible { outline: 2px solid var(--accent-{n}, Highlight); }
                |}
                |
                """.trimMargin(),
            "rust" to
                """
                |//! Session store {n}: keeps the latest revision per key.
                |use std::collections::HashMap;
                |use std::fmt::{self, Display};
                |
                |/// Status of a session.
                |#[derive(Debug, Clone, PartialEq, Eq)]
                |pub enum Status{n} {
                |    Idle,
                |    Busy { since: u64 },
                |    Failed(String, i32),
                |}
                |
                |pub trait Loader{n} {
                |    fn load(&self, id: &str) -> Result<Option<String>, Box<dyn std::error::Error + Send + Sync>>;
                |}
                |
                |pub struct SessionStore{n}<'a, T: Clone + Default> {
                |    items: HashMap<&'a str, T>,
                |    capacity: usize,
                |}
                |
                |impl<'a, T: Clone + Default> SessionStore{n}<'a, T> {
                |    pub const MAX: usize = 1_024;
                |
                |    pub fn new(capacity: usize) -> Self {
                |        Self { items: HashMap::with_capacity(capacity), capacity }
                |    }
                |
                |    pub fn insert(&mut self, id: &'a str, value: T) -> Option<T> {
                |        if self.items.len() >= self.capacity {
                |            let first = *self.items.keys().next()?;
                |            self.items.remove(first);
                |        }
                |        self.items.insert(id, value)
                |    }
                |
                |    pub async fn refresh<L: Loader{n} + ?Sized>(&mut self, loader: &L) -> Result<usize, String> {
                |        let body = loader.load("ses_{n}").map_err(|e| e.to_string())?.unwrap_or_default();
                |        let note = r#"raw "quoted" \n text"#;
                |        let bytes = b"bytes\x7f";
                |        let ch = '\u{1F600}';
                |        match body.len() {
                |            0 => Ok(0),
                |            len @ 1..=99 => Ok(len + note.len() + bytes.len() + ch.len_utf8()),
                |            _ => Err(format!("too large: {} bytes, {:#x}", body.len(), 0xFF_u8)),
                |        }
                |    }
                |}
                |
                |impl Display for Status{n} {
                |    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                |        write!(f, "{self:?}")
                |    }
                |}
                |
                |macro_rules! square_{n} {
                |    (${'$'}x:expr) => { ${'$'}x * ${'$'}x };
                |}
                |
                |#[cfg(test)]
                |mod tests_{n} {
                |    #[test]
                |    fn squares() {
                |        assert_eq!(square_{n}!(3_i64), 9); /* block /* nested */ comment */
                |    }
                |}
                |
                """.trimMargin(),
            "go" to
                """
                |// Package store{n} keeps the latest revision per session.
                |package store{n}
                |
                |import (
                |	"context"
                |	"errors"
                |	"fmt"
                |	"sync"
                |	"time"
                |)
                |
                |// ErrNotFound{n} is returned when a session is missing.
                |var ErrNotFound{n} = errors.New("session not found")
                |
                |const (
                |	maxRetries = 3
                |	ratio      = 0.75
                |	mask       = 0x1F
                |	separator  = '/'
                |)
                |
                |type Status{n} int
                |
                |const (
                |	Idle{n} Status{n} = iota
                |	Busy{n}
                |	Failed{n}
                |)
                |
                |type Session{n} struct {
                |	ID      string    `json:"id"`
                |	Title   string    `json:"title,omitempty"`
                |	Updated time.Time `json:"updated"`
                |}
                |
                |type Store{n}[T any] struct {
                |	mu       sync.RWMutex
                |	items    map[string]T
                |	capacity int
                |}
                |
                |func NewStore{n}[T any](capacity int) *Store{n}[T] {
                |	return &Store{n}[T]{items: make(map[string]T, capacity), capacity: capacity}
                |}
                |
                |func (s *Store{n}[T]) Load(ctx context.Context, id string, fetch func(context.Context, string) (T, error)) (T, error) {
                |	s.mu.RLock()
                |	if item, ok := s.items[id]; ok {
                |		s.mu.RUnlock()
                |		return item, nil
                |	}
                |	s.mu.RUnlock()
                |	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
                |	defer cancel()
                |	item, err := fetch(ctx, fmt.Sprintf("/session/%s?limit=%d", id, s.capacity*2))
                |	if err != nil {
                |		var zero T
                |		return zero, fmt.Errorf("load %q: %w", id, err)
                |	}
                |	s.mu.Lock()
                |	defer s.mu.Unlock()
                |	s.items[id] = item
                |	return item, nil
                |}
                |
                |func (s *Store{n}[T]) Watch(ctx context.Context) <-chan string {
                |	out := make(chan string, 1)
                |	go func() {
                |		defer close(out)
                |		for {
                |			select {
                |			case <-ctx.Done():
                |				return
                |			case out <- `raw string with "quotes" and \n`:
                |			}
                |		}
                |	}()
                |	return out
                |}
                |
                """.trimMargin(),
            "java" to
                """
                |/*
                | * Session store {n}: keeps the latest revision per key.
                | */
                |package dev.example.store{n};
                |
                |import java.util.LinkedHashMap;
                |import java.util.Map;
                |import java.util.Optional;
                |import java.util.concurrent.CompletableFuture;
                |import java.util.function.Function;
                |
                |/** A cache of sessions with {@code capacity} entries. */
                |@SuppressWarnings("unchecked")
                |public final class SessionStore{n}<T extends Comparable<? super T>> implements AutoCloseable {
                |    private static final int DEFAULT_CAPACITY = 0x40;
                |    private static final double LOAD_FACTOR = 0.75d;
                |    private final Map<String, T> items = new LinkedHashMap<>(DEFAULT_CAPACITY, (float) LOAD_FACTOR, true);
                |    private volatile boolean closed;
                |
                |    public sealed interface Status permits Idle, Busy {}
                |    public record Idle() implements Status {}
                |    public record Busy(long since, String reason) implements Status {}
                |
                |    public CompletableFuture<Optional<T>> load(String id, Function<String, T> fetch) {
                |        if (id == null || !id.matches("^ses_[a-z0-9]+${'$'}")) {
                |            throw new IllegalArgumentException("bad id: " + id);
                |        }
                |        return CompletableFuture.supplyAsync(() -> {
                |            T loaded = fetch.apply("/session/" + id + "?limit=" + (DEFAULT_CAPACITY * 2));
                |            synchronized (items) {
                |                items.put(id, loaded);
                |            }
                |            return Optional.ofNullable(loaded);
                |        });
                |    }
                |
                |    public String describe(Status status) {
                |        return switch (status) {
                |            case Idle idle -> "idle";
                |            case Busy(long since, String reason) when since > 1_000L -> ""${'"'}
                |                busy since %d
                |                because "%s"
                |                ""${'"'}.formatted(since, reason);
                |            case Busy busy -> 'b' + busy.reason();
                |        };
                |    }
                |
                |    @Override
                |    public void close() throws Exception {
                |        closed = true;
                |        items.clear(); // release everything
                |    }
                |}
                |
                """.trimMargin(),
            "c" to
                """
                |/* Session ring {n}: a fixed-size ring of messages. */
                |#include <stdio.h>
                |#include <stdlib.h>
                |#include <string.h>
                |#include "session.h"
                |
                |#define CAPACITY_{n} 64
                |#define MIN(a, b) ((a) < (b) ? (a) : (b))
                |#ifndef NDEBUG
                |#  define LOG(fmt, ...) fprintf(stderr, "[%s:%d] " fmt "\n", __FILE__, __LINE__, __VA_ARGS__)
                |#else
                |#  define LOG(fmt, ...) ((void)0)
                |#endif
                |
                |typedef enum { IDLE_{n}, BUSY_{n} = 0x10, FAILED_{n} } status_{n}_t;
                |
                |typedef struct ring_{n} {
                |    char *items[CAPACITY_{n}];
                |    size_t head, count;
                |    volatile unsigned int revision;
                |    status_{n}_t status;
                |} ring_{n}_t;
                |
                |static const char *const NAMES_{n}[] = { "idle", "busy", "failed" };
                |
                |ring_{n}_t *ring_{n}_new(void) {
                |    ring_{n}_t *ring = calloc(1, sizeof *ring);
                |    if (ring == NULL) {
                |        perror("calloc");
                |        return NULL;
                |    }
                |    return ring;
                |}
                |
                |int ring_{n}_push(ring_{n}_t *ring, const char *message) {
                |    size_t length = strlen(message);
                |    char *copy = malloc(length + 1u);
                |    if (!copy) return -1;
                |    memcpy(copy, message, length + 1);
                |    size_t slot = (ring->head + ring->count) % CAPACITY_{n};
                |    if (ring->count == CAPACITY_{n}) {
                |        free(ring->items[ring->head]);
                |        ring->head = (ring->head + 1) % CAPACITY_{n};
                |    } else {
                |        ring->count++;
                |    }
                |    ring->items[slot] = copy;
                |    LOG("pushed %zu bytes, rev %u, ratio %.2f", length, ++ring->revision, 0.75f);
                |    return (int) MIN(ring->count, 1000UL);
                |}
                |
                |const char *ring_{n}_status(const ring_{n}_t *ring) {
                |    switch (ring->status) {
                |    case IDLE_{n}: return NAMES_{n}[0];
                |    case BUSY_{n}: return NAMES_{n}[1];
                |    default: return ring->count > 0 ? NAMES_{n}[2] : "\x3f";
                |    }
                |}
                |
                |void ring_{n}_free(ring_{n}_t *ring) {
                |    for (size_t i = 0; i < ring->count; i++) {
                |        free(ring->items[(ring->head + i) % CAPACITY_{n}]);
                |    }
                |    free(ring); // done
                |}
                |
                """.trimMargin(),
            "cpp" to
                """
                |// Session cache {n}: header-only LRU with C++20 features.
                |#pragma once
                |#include <list>
                |#include <optional>
                |#include <string>
                |#include <string_view>
                |#include <unordered_map>
                |
                |namespace store{n} {
                |
                |using namespace std::literals;
                |
                |template <typename Key, typename Value, std::size_t Capacity = 64>
                |    requires std::equality_comparable<Key>
                |class LruCache{n} final {
                |public:
                |    using value_type = std::pair<const Key, Value>;
                |
                |    explicit LruCache{n}() noexcept = default;
                |    LruCache{n}(const LruCache{n}&) = delete;
                |    auto operator=(LruCache{n}&&) -> LruCache{n}& = default;
                |
                |    [[nodiscard]] std::optional<Value> get(const Key& key) {
                |        if (auto it = index_.find(key); it != index_.end()) {
                |            order_.splice(order_.begin(), order_, it->second);
                |            return it->second->second;
                |        }
                |        return std::nullopt;
                |    }
                |
                |    template <class... Args>
                |    void emplace(const Key& key, Args&&... args) {
                |        if (index_.size() >= Capacity) {
                |            index_.erase(order_.back().first);
                |            order_.pop_back();
                |        }
                |        order_.emplace_front(key, Value(std::forward<Args>(args)...));
                |        index_[key] = order_.begin();
                |    }
                |
                |    static constexpr std::size_t capacity() { return Capacity; }
                |
                |private:
                |    std::list<std::pair<Key, Value>> order_;
                |    std::unordered_map<Key, typename std::list<std::pair<Key, Value>>::iterator> index_;
                |};
                |
                |inline auto describe{n}(std::string_view id) {
                |    constexpr auto pattern = R"re(^ses_[a-z0-9]+${'$'})re";
                |    const auto note = R"(raw "quoted" \n text)"s;
                |    auto score = [&, factor = 0.75](int x) mutable -> double { return x * factor + 0x1F + 1'000; };
                |    return std::string(id).append(pattern).size() + note.size() + static_cast<std::size_t>(score(2));
                |}
                |
                |}  // namespace store{n}
                |
                """.trimMargin(),
            "csharp" to
                """
                |// Session store {n}: keeps the latest revision per key.
                |using System;
                |using System.Collections.Generic;
                |using System.Text.RegularExpressions;
                |using System.Threading;
                |using System.Threading.Tasks;
                |
                |namespace Example.Store{n};
                |
                |public enum Status{n} { Idle, Busy = 0x10, Failed }
                |
                |public sealed record Session{n}(string Id, string Title, DateTimeOffset Updated);
                |
                |[Serializable]
                |public partial class SessionStore{n}<T> : IAsyncDisposable where T : class, new()
                |{
                |    private const int DefaultCapacity = 64;
                |    private static readonly Regex IdPattern = new(@"^ses_[a-z0-9]+${'$'}", RegexOptions.IgnoreCase);
                |    private readonly Dictionary<string, T> _items = new();
                |
                |    public int Capacity { get; init; } = DefaultCapacity;
                |    public Status{n} Status { get; private set; } = Status{n}.Idle;
                |
                |    public async Task<T?> LoadAsync(string id, Func<string, CancellationToken, Task<T?>> fetch, CancellationToken token = default)
                |    {
                |        if (!IdPattern.IsMatch(id)) throw new ArgumentException(${'$'}"bad id: {id}", nameof(id));
                |        Status = Status{n}.Busy;
                |        var loaded = await fetch(${'$'}"/session/{Uri.EscapeDataString(id)}?limit={Capacity * 2}", token).ConfigureAwait(false);
                |        if (loaded is null) return null;
                |        lock (_items) { _items[id] = loaded; }
                |        var note = ""${'"'}
                |            raw "quoted" text
                |            with {braces} and \n kept
                |            ""${'"'};
                |        var verbatim = @"C:\Users\example\project\""quoted""${'"'};
                |        Status = loaded switch
                |        {
                |            { } when note.Length + verbatim.Length > 1_000 => Status{n}.Failed,
                |            _ => Status{n}.Idle,
                |        };
                |        return loaded;
                |    }
                |
                |    public IEnumerable<string> Keys()
                |    {
                |        foreach (var (key, _) in _items) yield return key;
                |    }
                |
                |    public ValueTask DisposeAsync()
                |    {
                |        _items.Clear(); // release everything
                |        return ValueTask.CompletedTask;
                |    }
                |}
                |
                """.trimMargin(),
            "ruby" to
                """
                |# frozen_string_literal: true
                |
                |# Session store {n}: keeps the latest revision per key.
                |require "json"
                |require_relative "support/loader"
                |
                |module Store{n}
                |  class NotFound < StandardError; end
                |
                |  class SessionStore
                |    include Enumerable
                |    attr_reader :capacity, :items
                |
                |    DEFAULT_CAPACITY = 64
                |    ID_PATTERN = /\Ases_[a-z0-9]+\z/i
                |
                |    def initialize(capacity: DEFAULT_CAPACITY, &on_evict)
                |      @capacity = capacity
                |      @items = {}
                |      @on_evict = on_evict
                |      @@instances ||= 0
                |      @@instances += 1
                |    end
                |
                |    def load(id, fetch = method(:default_fetch))
                |      raise NotFound, "bad id: #{id.inspect}" unless id.match?(ID_PATTERN)
                |
                |      loaded = fetch.call("/session/#{id}?limit=#{capacity * 2}") or return nil
                |      @items[id] = loaded
                |      evict! while @items.size > capacity
                |      loaded
                |    rescue JSON::ParserError => e
                |      warn "load failed: #{e.message}"
                |      nil
                |    ensure
                |      ${'$'}stdout.flush
                |    end
                |
                |    def each(&block) = @items.each_value(&block)
                |
                |    def summary
                |      <<~TEXT
                |        Store #{self.class.name} has #{@items.size} items
                |        capacity: #{capacity}, ratio: #{0.75 * 100}%
                |      TEXT
                |    end
                |
                |    private
                |
                |    def evict!
                |      key, value = @items.first
                |      @items.delete(key)
                |      @on_evict&.call(value)
                |    end
                |
                |    def default_fetch(path) = JSON.parse(%q({"session": "x"}), symbolize_names: true)[:session]
                |  end
                |end
                |
                |store_{n} = Store{n}::SessionStore.new(capacity: 0x10) { |item| puts "evicted #{item}" }
                |%w[alpha beta gamma].each_with_index.map { |name, i| [name.to_sym, i ** 2] }.to_h
                |puts store_{n}.summary if __FILE__ == ${'$'}PROGRAM_NAME
                |
                """.trimMargin(),
            "php" to
                """
                |<?php
                |declare(strict_types=1);
                |
                |namespace App\Store{n};
                |
                |use InvalidArgumentException;
                |use JsonException;
                |
                |/**
                | * Session store {n}: keeps the latest revision per key.
                | */
                |final class SessionStore{n} implements \Countable
                |{
                |    public const DEFAULT_CAPACITY = 64;
                |    private array ${'$'}items = [];
                |
                |    public function __construct(
                |        private readonly int ${'$'}capacity = self::DEFAULT_CAPACITY,
                |        private ?\Closure ${'$'}onEvict = null,
                |    ) {}
                |
                |    public function load(string ${'$'}id, callable ${'$'}fetch): ?array
                |    {
                |        if (!preg_match('/^ses_[a-z0-9]+${'$'}/i', ${'$'}id)) {
                |            throw new InvalidArgumentException("bad id: {${'$'}id}");
                |        }
                |        try {
                |            ${'$'}body = json_decode(${'$'}fetch("/session/${'$'}id?limit={${'$'}this->capacity}"), true, 512, JSON_THROW_ON_ERROR);
                |        } catch (JsonException ${'$'}e) {
                |            error_log(sprintf('load failed: %s (%d)', ${'$'}e->getMessage(), 0x1F));
                |            return null;
                |        }
                |        ${'$'}this->items[${'$'}id] = ${'$'}body['session'] ?? null;
                |        while (count(${'$'}this->items) > ${'$'}this->capacity) {
                |            ${'$'}first = array_key_first(${'$'}this->items);
                |            (${'$'}this->onEvict)?->__invoke(${'$'}this->items[${'$'}first]);
                |            unset(${'$'}this->items[${'$'}first]);
                |        }
                |        return match (true) {
                |            empty(${'$'}body) => null,
                |            default => ${'$'}body,
                |        };
                |    }
                |
                |    public function count(): int
                |    {
                |        return count(${'$'}this->items);
                |    }
                |
                |    public function summary(): string
                |    {
                |        return <<<EOT
                |            Store {${'$'}this->capacity} has {${'$'}this->count()} items
                |            and costs \${'$'}1.50 per item
                |            EOT;
                |    }
                |}
                |
                |${'$'}store = new SessionStore{n}(capacity: 16, onEvict: fn(array ${'$'}s) => print_r(${'$'}s));
                |${'$'}query = <<<'SQL'
                |    SELECT * FROM sessions WHERE id = ${'$'}1
                |    SQL;
                |?>
                |<!-- PHP island {n} inside HTML -->
                |<ul class="sessions">
                |  <?php foreach (${'$'}store as ${'$'}key => ${'$'}session): ?>
                |    <li data-key="<?= htmlspecialchars(${'$'}key) ?>"><?= ${'$'}session['title'] ?? 'untitled' ?></li>
                |  <?php endforeach; ?>
                |</ul>
                |<script>const count{n} = <?= json_encode(${'$'}store->count()) ?>;</script>
                |
                """.trimMargin(),
            "sql" to
                """
                |-- Session schema {n}: tables, indexes, and a report query.
                |CREATE TABLE IF NOT EXISTS sessions_{n} (
                |    id          VARCHAR(64) PRIMARY KEY,
                |    title       TEXT NOT NULL DEFAULT 'Untitled',
                |    parent_id   VARCHAR(64) REFERENCES sessions_{n} (id) ON DELETE CASCADE,
                |    tokens      BIGINT CHECK (tokens >= 0),
                |    cost        NUMERIC(10, 4) DEFAULT 0.0,
                |    archived    BOOLEAN DEFAULT FALSE,
                |    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                |);
                |
                |CREATE INDEX idx_sessions_{n}_parent ON sessions_{n} (parent_id) WHERE archived = FALSE;
                |
                |/* Upsert one session; ${'$'}1..${'$'}3 are bound parameters. */
                |INSERT INTO sessions_{n} (id, title, tokens)
                |VALUES (${'$'}1, ${'$'}2, ${'$'}3)
                |ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, tokens = sessions_{n}.tokens + EXCLUDED.tokens;
                |
                |WITH RECURSIVE tree AS (
                |    SELECT id, parent_id, title, 0 AS depth
                |    FROM sessions_{n}
                |    WHERE parent_id IS NULL
                |    UNION ALL
                |    SELECT s.id, s.parent_id, s.title, t.depth + 1
                |    FROM sessions_{n} s
                |    JOIN tree t ON s.parent_id = t.id
                |)
                |SELECT
                |    t.id,
                |    UPPER(t.title) AS "Title",
                |    COUNT(m.id) FILTER (WHERE m.role = 'assistant') AS replies,
                |    ROUND(AVG(m.tokens)::numeric, 2) AS avg_tokens,
                |    CASE WHEN t.depth > 2 THEN 'deep' ELSE 'shallow' END AS shape
                |FROM tree t
                |LEFT OUTER JOIN messages m ON m.session_id = t.id AND m.created_at > NOW() - INTERVAL '7 days'
                |WHERE t.title NOT LIKE '%draft%' AND t.id IN ('ses_a{n}', 'ses_b{n}')
                |GROUP BY t.id, t.title, t.depth
                |HAVING COUNT(*) > 1e3 OR SUM(m.tokens) BETWEEN 10 AND 1000
                |ORDER BY replies DESC NULLS LAST
                |LIMIT 50 OFFSET 0;
                |
                |UPDATE sessions_{n} SET archived = TRUE WHERE created_at < DATE '2024-01-01';
                |DELETE FROM sessions_{n} WHERE title = 'It''s a test';
                |GRANT SELECT ON sessions_{n} TO reporting;
                |
                """.trimMargin(),
            "toml" to
                """
                |# Project config {n}
                |[project-{n}]
                |title = "Example project {n}"
                |version = "1.{n}.0"
                |enabled = true
                |ratio = 0.75
                |max-size = 1_000_000
                |mask = 0x1F
                |mode = 0o755
                |flags = 0b1010
                |epsilon = 1e-6
                |limit = inf
                |released = 1979-05-27T07:32:00-08:00
                |local-date = 2024-05-01
                |alarm = 07:32:00
                |'literal key' = 'C:\Users\example\no escapes'
                |"quoted.key" = "tab\tand unicode \u00E9"
                |
                |[server-{n}]
                |host = "example.com"
                |ports = [8_000, 8_001, 8_002]
                |tags = ["alpha", 'beta', ""${'"'}multi""${'"'}]
                |
                |[server-{n}.tls]
                |enabled = false
                |description = ""${'"'}
                |A multi-line basic string \
                |with a line continuation and "quotes".
                |""${'"'}
                |pattern = '''
                |^ses_[a-z0-9]+${'$'} with 'single' quotes
                |'''
                |
                |[[profiles]]
                |name = "dev-{n}"
                |inline = { retries = 3, timeout = 12.5, hosts = ["a", "b"] }
                |
                |[[profiles]]
                |name = "prod-{n}"
                |dependencies.serde = { version = "1.0", features = ["derive"] }
                |
                |[dependencies-{n}]
                |tokio = { version = "1", features = ["full"], optional = true }
                |
                """.trimMargin(),
            "docker" to
                """
                |# syntax=docker/dockerfile:1.7
                |# Build image {n}: compiles the server and ships a slim runtime.
                |ARG NODE_VERSION=22
                |FROM node:${'$'}{NODE_VERSION}-bookworm AS build-{n}
                |LABEL org.opencontainers.image.title="server-{n}" \
                |      org.opencontainers.image.version="1.{n}.0"
                |WORKDIR /app
                |ENV NODE_ENV=production \
                |    PORT=8080
                |COPY --chown=node:node package*.json ./
                |RUN --mount=type=cache,target=/root/.npm \
                |    npm ci --omit=dev && \
                |    npm cache clean --force
                |COPY . .
                |RUN set -eux; \
                |    npm run build; \
                |    if [ -n "${'$'}SKIP_TESTS" ]; then echo "skipping tests"; else npm test; fi
                |RUN <<EOF
                |apt-get update
                |apt-get install -y --no-install-recommends curl ca-certificates
                |rm -rf /var/lib/apt/lists/*
                |EOF
                |
                |FROM gcr.io/distroless/nodejs22-debian12 AS runtime-{n}
                |COPY --from=build-{n} /app/dist /app
                |USER nonroot:nonroot
                |EXPOSE 8080/tcp
                |VOLUME ["/data"]
                |HEALTHCHECK --interval=30s --timeout=3s CMD ["node", "/app/health.js"]
                |STOPSIGNAL SIGTERM
                |ENTRYPOINT ["node", "/app/server.js"]
                |CMD ["--port", "8080"]
                |
                """.trimMargin(),
            "xml" to
                """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<!-- Android manifest and resources {n} -->
                |<!DOCTYPE resources [
                |  <!ENTITY app "Example {n}">
                |]>
                |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                |    xmlns:tools="http://schemas.android.com/tools"
                |    package="dev.example.app{n}">
                |
                |    <uses-permission android:name="android.permission.INTERNET" />
                |
                |    <application
                |        android:name=".App{n}"
                |        android:label="&app;"
                |        android:allowBackup="false"
                |        tools:targetApi="34">
                |        <activity android:name=".MainActivity" android:exported="true">
                |            <intent-filter>
                |                <action android:name="android.intent.action.MAIN" />
                |                <category android:name="android.intent.category.LAUNCHER" />
                |            </intent-filter>
                |        </activity>
                |    </application>
                |</manifest>
                |<resources>
                |    <string name="title_{n}">Sessions &amp; files</string>
                |    <string name="escaped_{n}">It\'s &lt;b&gt;bold&lt;/b&gt; &#x1F600;</string>
                |    <plurals name="items_{n}">
                |        <item quantity="one">%d item</item>
                |        <item quantity="other">%d items</item>
                |    </plurals>
                |    <dimen name="gap_{n}">16dp</dimen>
                |    <![CDATA[ raw <markup> & text {n} ]]>
                |    <?processing instruction="value"?>
                |</resources>
                |<project xmlns="http://maven.apache.org/POM/4.0.0">
                |    <modelVersion>4.0.0</modelVersion>
                |    <groupId>dev.example</groupId>
                |    <artifactId>store-{n}</artifactId>
                |    <properties><java.version>17</java.version></properties>
                |</project>
                |
                """.trimMargin(),
        )
}
