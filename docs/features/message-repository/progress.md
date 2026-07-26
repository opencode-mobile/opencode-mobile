# Message Repository — Progress

## Shared planning

- [x] Feature docs and CONTEXT glossary entry
- [x] Messages OpenAPI spec (`api/opencode-messages.openapi.yaml`)

## Design-system

- [x] Extended `OpenCodeRenderedMarkdown` streaming behavior on iOS and Android
  so large live code stays plain until final highlighting without blocking the
  timeline

## Android

- [x] Messages API + repository + adapter + Hilt module
- [x] SessionChatViewModel + unit tests
- [x] Session Chat wired to repository
- [x] `MessageSync` + `InMemoryMessageStore` preserve SSE part order and append
  text deltas
- [x] OPE-82: handle SSE `message.part.delta` for live assistant text streaming
- [x] OPE-82 finalization: authoritative REST reload and `session.idle` clear
  streaming state; REST-seeded part updates retain the safe reload fallback
- [x] OPE-82 hardening: pre-load event buffering, revision-gated REST writes,
  failed-finalization recovery, serialized store access, and callback-safe
  timeline coalescing

## iOS

- [x] Messages API client + repository + adapter + Factory
- [x] SessionChatViewModel + unit tests
- [x] Session Chat wired to repository
- [x] `MessageSync` + `InMemoryMessageStore` preserve SSE part order and append
  text deltas
- [x] OPE-82: handle SSE `message.part.delta` for live assistant text streaming
- [x] OPE-82 finalization: authoritative REST reload and `session.idle` clear
  streaming state; REST-seeded part updates retain the safe reload fallback
- [x] OPE-82 hardening: pre-load event buffering, revision-gated REST writes,
  failed-finalization recovery, cancellable history enrichment, and strict
  concurrency-safe markdown preparation

## Remaining

- No remaining implementation work in this repository slice.
- Run the server-backed startup, finalization-failure recovery, cross-device,
  and large-history responsiveness cases in
  [Manual Test](./manual-test.md) before merge acceptance.
- Server-backed model and Variant options shipped in
  [Model Selection](../model-selection/progress.md); agent mode is also sent with
  async prompts from Session Chat.
- Connection Profile persistence shipped in
  [Server Connection](../server-connection/progress.md).
